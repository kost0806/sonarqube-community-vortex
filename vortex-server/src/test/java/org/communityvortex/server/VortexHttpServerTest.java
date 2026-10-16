package org.communityvortex.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VortexHttpServerTest {

  private final AtomicReference<String> seenAuthorization = new AtomicReference<>();
  private final AtomicReference<String> seenBody = new AtomicReference<>();
  private VortexHttpServer server;
  private final HttpClient http = HttpClient.newHttpClient();

  @BeforeEach
  void start() throws Exception {
    server = new VortexHttpServer(new InetSocketAddress("127.0.0.1", 0), new VortexHttpServer.Handler() {
      @Override
      public ApiResponse analyses(String authorization, String body) {
        seenAuthorization.set(authorization);
        seenBody.set(body);
        if (body.contains("explode")) {
          throw new IllegalStateException("secret detail");
        }
        var json = new JsonObject();
        json.addProperty("id", "a1");
        return ApiResponse.ok(json);
      }

      @Override
      public ApiResponse entitlement(String authorization) {
        var json = new JsonObject();
        json.addProperty("allowed", authorization != null);
        return ApiResponse.ok(json);
      }
    }, 2);
    server.start();
  }

  @AfterEach
  void stop() {
    server.close();
  }

  private HttpResponse<String> send(HttpRequest.Builder builder) throws Exception {
    return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpRequest.Builder to(String path) {
    return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path));
  }

  @Test
  void forwardsAnalysesWithAuthorizationAndBody() throws Exception {
    var response = send(to("/analyses").header("Authorization", "Bearer abc").POST(HttpRequest.BodyPublishers.ofString("{\"x\":1}")));

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(v -> assertThat(v).startsWith("application/json"));
    assertThat(JsonParser.parseString(response.body()).getAsJsonObject().get("id").getAsString()).isEqualTo("a1");
    assertThat(seenAuthorization.get()).isEqualTo("Bearer abc");
    assertThat(seenBody.get()).isEqualTo("{\"x\":1}");
  }

  @Test
  void entitlementHealthAndUnknownRoutes() throws Exception {
    assertThat(send(to("/entitlement").header("Authorization", "Bearer t").GET()).body()).contains("\"allowed\":true");
    assertThat(send(to("/health").GET()).body()).contains("UP");
    assertThat(send(to("/nope").GET()).statusCode()).isEqualTo(404);
    assertThat(send(to("/analyses").GET()).statusCode()).isEqualTo(405);
  }

  @Test
  void oversizedBodiesGetA413WithTheLimit() throws Exception {
    var body = "x".repeat(VortexHttpServer.MAX_BODY_BYTES + 1);

    var response = send(to("/analyses").POST(HttpRequest.BodyPublishers.ofString(body)));

    assertThat(response.statusCode()).isEqualTo(413);
    assertThat(JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonObject("meta").get("maxRequestSize").getAsInt())
      .isEqualTo(VortexHttpServer.MAX_BODY_BYTES);
  }

  @Test
  void unexpectedFailuresDoNotLeakDetails() throws Exception {
    var response = send(to("/analyses").POST(HttpRequest.BodyPublishers.ofString("explode")));

    assertThat(response.statusCode()).isEqualTo(500);
    assertThat(response.body()).doesNotContain("secret detail");
  }

  @Test
  void configurationFromEnvironment() {
    var env = new HashMap<String, String>();
    assertThatThrownBy(() -> ServerConfig.fromEnvironment(env, Path.of("/home/v"))).hasMessageContaining("SONAR_HOST_URL");

    env.put("SONAR_HOST_URL", "http://sonarqube:9000");
    var config = ServerConfig.fromEnvironment(env, Path.of("/home/v"));
    assertThat(config.sonarUrl()).hasToString("http://sonarqube:9000/");
    assertThat(config.port()).isEqualTo(9100);
    assertThat(config.bind()).isEqualTo("0.0.0.0");
    assertThat(config.cacheDir()).isEqualTo(Path.of("/home/v/.vortex-server"));
    assertThat(config.maxFiles()).isEqualTo(100);
    assertThat(config.nodePath()).isNull();

    env.put("VORTEX_PORT", "x");
    assertThatThrownBy(() -> ServerConfig.fromEnvironment(env, Path.of("/h"))).hasMessageContaining("number");
    env.put("VORTEX_PORT", "9200");
    env.put("VORTEX_NODE", "/usr/bin/node");
    env.put("VORTEX_CACHE_DIR", "/cache");
    assertThat(ServerConfig.fromEnvironment(env, Path.of("/h")).port()).isEqualTo(9200);
    env.put("SONAR_HOST_URL", "http://u:p@sonarqube:9000");
    assertThatThrownBy(() -> ServerConfig.fromEnvironment(env, Path.of("/h"))).hasMessageContaining("credentials");
    env.put("SONAR_HOST_URL", "ftp://sonarqube");
    assertThatThrownBy(() -> ServerConfig.fromEnvironment(env, Path.of("/h"))).hasMessageContaining("http(s)");
  }
}
