package org.communityvortex.plugin.a3s;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import org.communityvortex.plugin.VortexProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.config.Configuration;
import org.sonar.api.server.http.HttpRequest;
import org.sonar.api.server.http.HttpResponse;
import org.sonar.api.web.FilterChain;
import org.sonar.api.web.HttpFilter;
import org.sonar.api.web.UrlPattern;

/**
 * Answers the analysis endpoints the SonarQube CLI (LGPL) calls on a SonarQube Server, by forwarding them
 * to the Community Vortex analysis service. This is an independent implementation of the publicly visible
 * request/response format; no SonarSource service or license is involved or emulated beyond that.
 * <ul>
 *   <li>{@code GET  /api/v2/a3s/org-entitlement/{id}}: whether this server provides the analysis</li>
 *   <li>{@code POST /api/v2/a3s/analyses}: analyse the posted files</li>
 * </ul>
 * The service authenticates every call with the caller's own token against SonarQube.
 */
public class A3sCompatibilityFilter extends HttpFilter {

  static final String PREFIX = "/api/v2/a3s/";
  static final int MAX_BODY_CHARS = 16 * 1024 * 1024;
  private static final Logger LOG = LoggerFactory.getLogger(A3sCompatibilityFilter.class);
  private static final Duration TIMEOUT = Duration.ofMinutes(5);
  private static final String JSON = "application/json";

  private final Configuration configuration;
  private final HttpClient http;

  public A3sCompatibilityFilter(Configuration configuration) {
    this(configuration, HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(5))
      .followRedirects(HttpClient.Redirect.NEVER)
      .build());
  }

  A3sCompatibilityFilter(Configuration configuration, HttpClient http) {
    this.configuration = configuration;
    this.http = http;
  }

  @Override
  public UrlPattern doGetPattern() {
    return UrlPattern.create(PREFIX + "*");
  }

  @Override
  public void doFilter(HttpRequest request, HttpResponse response, FilterChain chain) throws IOException {
    var path = request.getRequestURI().substring(request.getContextPath().length());
    var service = serviceUrl();
    if (service.isEmpty()) {
      // not provided on this server: the CLI treats a 404 here as "Vortex not applicable"
      sendError(response, 404, "Vortex analysis is not configured on this server (" + VortexProperties.ANALYSIS_URL + ")");
      return;
    }
    if ("GET".equals(request.getMethod()) && path.startsWith(PREFIX + "org-entitlement/")) {
      forward(request, response, service.get().resolve("entitlement"), null);
    } else if ("POST".equals(request.getMethod()) && path.equals(PREFIX + "analyses")) {
      var body = readBody(request);
      if (body == null) {
        sendTooLarge(response);
        return;
      }
      forward(request, response, service.get().resolve("analyses"), body);
    } else {
      sendError(response, 404, "Resource not found.");
    }
  }

  private Optional<URI> serviceUrl() {
    return configuration.get(VortexProperties.ANALYSIS_URL)
      .map(String::trim)
      .filter(url -> !url.isEmpty())
      .map(url -> URI.create(url.endsWith("/") ? url : url + "/"));
  }

  private void forward(HttpRequest request, HttpResponse response, URI target, String body) throws IOException {
    var builder = java.net.http.HttpRequest.newBuilder(target).timeout(TIMEOUT).header("Accept", JSON);
    var authorization = request.getHeader("Authorization");
    if (authorization != null) {
      builder.header("Authorization", authorization);
    }
    if (body == null) {
      builder.GET();
    } else {
      builder.header("Content-Type", JSON).POST(BodyPublishers.ofString(body, StandardCharsets.UTF_8));
    }
    java.net.http.HttpResponse<String> upstream;
    try {
      upstream = http.send(builder.build(), BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (IOException e) {
      LOG.warn("Community Vortex analysis service unreachable at {}: {}", target.getHost(), e.getMessage());
      sendError(response, 503, "Vortex analysis service is unavailable");
      return;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      sendError(response, 503, "Vortex analysis service is unavailable");
      return;
    }
    response.setStatus(upstream.statusCode());
    response.setContentType(JSON);
    response.setCharacterEncoding("UTF-8");
    response.getWriter().write(upstream.body());
  }

  /** @return the body, or {@code null} when it exceeds {@link #MAX_BODY_CHARS} */
  private static String readBody(HttpRequest request) throws IOException {
    var body = new StringBuilder();
    var buffer = new char[64 * 1024];
    try (Reader reader = request.getReader()) {
      for (int read = reader.read(buffer); read >= 0; read = reader.read(buffer)) {
        body.append(buffer, 0, read);
        if (body.length() > MAX_BODY_CHARS) {
          return null;
        }
      }
    }
    return body.toString();
  }

  private static void sendTooLarge(HttpResponse response) throws IOException {
    var json = new JsonObject();
    json.addProperty("message", "Request too large");
    var meta = new JsonObject();
    meta.addProperty("maxRequestSize", MAX_BODY_CHARS);
    json.add("meta", meta);
    send(response, 413, json);
  }

  private static void sendError(HttpResponse response, int status, String message) throws IOException {
    var json = new JsonObject();
    json.addProperty("message", message);
    send(response, status, json);
  }

  private static void send(HttpResponse response, int status, JsonObject json) throws IOException {
    response.setStatus(status);
    response.setContentType(JSON);
    response.setCharacterEncoding("UTF-8");
    response.getWriter().write(json.toString());
  }
}
