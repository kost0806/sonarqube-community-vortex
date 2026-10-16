package org.communityvortex.mcp.sonar;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

/** {@link SonarServer} over HTTP with a bearer user token. */
public final class HttpSonarServer implements SonarServer {

  private static final Duration TIMEOUT = Duration.ofSeconds(60);

  private final URI baseUrl;
  private final String token;
  private final HttpClient http;

  public HttpSonarServer(URI baseUrl, String token) {
    this(baseUrl, token, HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(15))
      // never replay the bearer token to a redirect target
      .followRedirects(HttpClient.Redirect.NEVER)
      .build());
  }

  HttpSonarServer(URI baseUrl, String token, HttpClient http) {
    this.baseUrl = baseUrl;
    this.token = token;
    this.http = http;
  }

  @Override
  public JsonObject get(String path, Map<String, String> params) {
    var response = send(path, params, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() != 200) {
      throw new SonarServerException(response.statusCode(), describeFailure(path, response.statusCode(), response.body()));
    }
    try {
      return JsonParser.parseString(response.body()).getAsJsonObject();
    } catch (JsonParseException | IllegalStateException e) {
      throw new SonarServerException(502, path + ": unexpected response format");
    }
  }

  @Override
  public void download(String path, Map<String, String> params, Path target) {
    try {
      Files.createDirectories(target.getParent());
      var temp = Files.createTempFile(target.getParent(), "download", ".part");
      try {
        var response = send(path, params, HttpResponse.BodyHandlers.ofFile(temp));
        if (response.statusCode() != 200) {
          throw new SonarServerException(response.statusCode(), path + ": HTTP " + response.statusCode());
        }
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } finally {
        Files.deleteIfExists(temp);
      }
    } catch (IOException e) {
      throw new SonarServerException("Cannot download " + path, e);
    }
  }

  private <T> HttpResponse<T> send(String path, Map<String, String> params, HttpResponse.BodyHandler<T> handler) {
    var request = HttpRequest.newBuilder(baseUrl.resolve(path + query(params)))
      .timeout(TIMEOUT)
      .header("Authorization", "Bearer " + token)
      .header("Accept", "application/json")
      .GET()
      .build();
    try {
      return http.send(request, handler);
    } catch (IOException e) {
      // host only: never echo URL user info or paths into tool results
      throw new SonarServerException("Cannot reach SonarQube at " + baseUrl.getHost() + " (" + e.getClass().getSimpleName() + ")", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new SonarServerException("Interrupted while calling " + path, e);
    }
  }

  private static String query(Map<String, String> params) {
    if (params.isEmpty()) {
      return "";
    }
    return params.entrySet().stream()
      .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
      .collect(Collectors.joining("&", "?", ""));
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static String describeFailure(String path, int status, String body) {
    try {
      var errors = JsonParser.parseString(body).getAsJsonObject().getAsJsonArray("errors");
      if (errors != null && !errors.isEmpty()) {
        return path + ": " + errors.get(0).getAsJsonObject().get("msg").getAsString();
      }
    } catch (RuntimeException e) {
      // not a SonarQube error body
    }
    if (status >= 300 && status < 400) {
      return path + ": HTTP " + status + " redirect not followed (the token is never sent to another URL); "
        + "set SONAR_HOST_URL to the final server URL, e.g. https://";
    }
    return switch (status) {
      case 401 -> path + ": authentication failed, check SONAR_TOKEN";
      case 403 -> path + ": insufficient permissions";
      case 404 -> path + ": not found";
      default -> path + ": HTTP " + status;
    };
  }
}
