package org.communityvortex.mcp.sonar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HttpSonarServerTest {

  private HttpServer http;
  private HttpSonarServer client;
  private final AtomicReference<String> authorization = new AtomicReference<>();
  private final AtomicReference<String> query = new AtomicReference<>();

  @BeforeEach
  void start() throws Exception {
    http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    route("/sonar/api/ok", 200, "{\"value\":1}");
    route("/sonar/api/forbidden", 403, "{\"errors\":[{\"msg\":\"Insufficient privileges\"}]}");
    route("/sonar/api/unauthorized", 401, "");
    route("/sonar/api/broken", 200, "<html>");
    route("/sonar/api/missing", 404, "not json");
    route("/sonar/api/oops", 500, "");
    route("/sonar/api/plugins/download", 200, "JARBYTES");
    http.createContext("/sonar/api/redirect", exchange -> {
      exchange.getResponseHeaders().add("Location", "http://evil.example/steal");
      exchange.sendResponseHeaders(302, -1);
      exchange.close();
    });
    http.start();
    client = new HttpSonarServer(URI.create("http://127.0.0.1:" + http.getAddress().getPort() + "/sonar/"), "secret-token");
  }

  private void route(String path, int status, String body) {
    http.createContext(path, exchange -> {
      authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
      query.set(exchange.getRequestURI().getRawQuery());
      var bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
      if (bytes.length > 0) {
        exchange.getResponseBody().write(bytes);
      }
      exchange.close();
    });
  }

  @AfterEach
  void stop() {
    http.stop(0);
  }

  @Test
  void sendsBearerTokenAndEncodedParameters() {
    var json = client.get("api/ok", Params.of("component", "a b&c", "branch", "feature/x", "skipped", null));

    assertThat(json.get("value").getAsInt()).isEqualTo(1);
    assertThat(authorization.get()).isEqualTo("Bearer secret-token");
    assertThat(query.get()).isEqualTo("component=a+b%26c&branch=feature%2Fx");
  }

  @Test
  void mapsErrorResponses() {
    assertThatThrownBy(() -> client.get("api/forbidden", Params.of()))
      .isInstanceOfSatisfying(SonarServerException.class, e -> {
        assertThat(e.status()).isEqualTo(403);
        assertThat(e.getMessage()).isEqualTo("api/forbidden: Insufficient privileges");
      });
    assertThatThrownBy(() -> client.get("api/unauthorized", Params.of())).hasMessageContaining("check SONAR_TOKEN");
    assertThatThrownBy(() -> client.get("api/missing", Params.of())).hasMessage("api/missing: not found");
    assertThatThrownBy(() -> client.get("api/oops", Params.of())).hasMessage("api/oops: HTTP 500");
    assertThatThrownBy(() -> client.get("api/broken", Params.of())).hasMessageContaining("unexpected response format");
  }

  @Test
  void doesNotFollowRedirectsWithTheToken() {
    assertThatThrownBy(() -> client.get("api/redirect", Params.of()))
      .isInstanceOfSatisfying(SonarServerException.class, e -> {
        assertThat(e.status()).isEqualTo(302);
        assertThat(e.getMessage()).contains("redirect").contains("SONAR_HOST_URL");
      });
  }

  @Test
  void downloadsAtomically(@TempDir Path dir) throws Exception {
    var target = dir.resolve("plugins/h/python.jar");

    client.download("api/plugins/download", Params.of("plugin", "python"), target);

    assertThat(Files.readString(target)).isEqualTo("JARBYTES");
    assertThat(query.get()).isEqualTo("plugin=python");
    try (var files = Files.list(target.getParent())) {
      assertThat(files).containsExactly(target);
    }
    assertThatThrownBy(() -> client.download("api/forbidden", Params.of(), dir.resolve("x.jar")))
      .isInstanceOfSatisfying(SonarServerException.class, e -> assertThat(e.status()).isEqualTo(403));
  }

  @Test
  void unreachableServer() {
    var offline = new HttpSonarServer(URI.create("http://127.0.0.1:1/"), "t");

    assertThatThrownBy(() -> offline.get("api/ok", Params.of()))
      .isInstanceOfSatisfying(SonarServerException.class, e -> {
        assertThat(e.status()).isEqualTo(-1);
        assertThat(e.getMessage()).startsWith("Cannot reach SonarQube").doesNotContain("t\"");
      });
    assertThatThrownBy(() -> Params.of("odd")).isInstanceOf(IllegalArgumentException.class);
  }
}
