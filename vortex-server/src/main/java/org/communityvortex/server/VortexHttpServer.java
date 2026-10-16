package org.communityvortex.server;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP front of the analysis service. The plugin forwards {@code POST /api/v2/a3s/analyses} here as
 * {@code POST /analyses} and {@code GET /api/v2/a3s/org-entitlement/*} as {@code GET /entitlement}.
 */
public final class VortexHttpServer implements AutoCloseable {

  /** The service operations, so the HTTP layer can be tested on its own. */
  public interface Handler {
    ApiResponse analyses(String authorization, String body);

    ApiResponse entitlement(String authorization);
  }

  static final int MAX_BODY_BYTES = 16 * 1024 * 1024 + 64 * 1024;
  private static final Logger LOG = LoggerFactory.getLogger(VortexHttpServer.class);

  private final HttpServer server;
  private final ExecutorService executor;

  public VortexHttpServer(InetSocketAddress address, Handler handler, int threads) throws IOException {
    server = HttpServer.create(address, 0);
    executor = Executors.newFixedThreadPool(threads);
    server.setExecutor(executor);
    server.createContext("/health", exchange -> {
      var json = new JsonObject();
      json.addProperty("status", "UP");
      respond(exchange, "GET", new ApiResponse(200, json), null);
    });
    server.createContext("/entitlement", exchange ->
      respond(exchange, "GET", null, () -> handler.entitlement(authorization(exchange))));
    server.createContext("/analyses", exchange ->
      respond(exchange, "POST", null, () -> {
        var body = readBody(exchange.getRequestBody());
        if (body == null) {
          var meta = new JsonObject();
          meta.addProperty("maxRequestSize", MAX_BODY_BYTES);
          return ApiResponse.of(new RequestException(413, "Request too large", meta));
        }
        return handler.analyses(authorization(exchange), body);
      }));
    server.createContext("/", exchange -> respond(exchange, null, ApiResponse.error(404, "Resource not found."), null));
  }

  public void start() {
    server.start();
    LOG.info("Community Vortex analysis service listening on {}", server.getAddress());
  }

  public int port() {
    return server.getAddress().getPort();
  }

  @Override
  public void close() {
    server.stop(1);
    executor.shutdownNow();
  }

  private interface Call {
    ApiResponse run() throws IOException;
  }

  private static void respond(HttpExchange exchange, String method, ApiResponse fixed, Call call) throws IOException {
    try (exchange) {
      ApiResponse response;
      if (method != null && !method.equals(exchange.getRequestMethod())) {
        response = ApiResponse.error(405, "Method not allowed");
      } else if (fixed != null) {
        response = fixed;
      } else {
        try {
          response = call.run();
        } catch (RuntimeException e) {
          LOG.error("Request failed", e);
          response = ApiResponse.error(500, "Internal error");
        }
      }
      var bytes = response.body().toString().getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
      exchange.sendResponseHeaders(response.status(), bytes.length);
      exchange.getResponseBody().write(bytes);
    }
  }

  private static String authorization(HttpExchange exchange) {
    return exchange.getRequestHeaders().getFirst("Authorization");
  }

  /** @return the body, or {@code null} when it exceeds {@link #MAX_BODY_BYTES} */
  private static String readBody(InputStream in) throws IOException {
    var out = new ByteArrayOutputStream();
    var buffer = new byte[64 * 1024];
    for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
      out.write(buffer, 0, read);
      if (out.size() > MAX_BODY_BYTES) {
        // drain (bounded) so the client receives the 413 instead of a reset connection
        long drained = 0;
        for (int skip = in.read(buffer); skip >= 0 && drained < 4L * MAX_BODY_BYTES; skip = in.read(buffer)) {
          drained += skip;
        }
        return null;
      }
    }
    return out.toString(StandardCharsets.UTF_8);
  }
}
