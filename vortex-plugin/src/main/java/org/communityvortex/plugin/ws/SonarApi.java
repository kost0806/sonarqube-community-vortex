package org.communityvortex.plugin.ws;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.sonar.api.server.ws.LocalConnector;

/**
 * Calls other SonarQube web services in-process. The call runs in the current request thread,
 * so the caller's permissions apply exactly as if the agent had called the API itself.
 */
public final class SonarApi {

  private static final String JSON = "application/json";

  private final LocalConnector connector;

  public SonarApi(LocalConnector connector) {
    this.connector = connector;
  }

  public JsonObject get(String path, Map<String, String> params) {
    var response = connector.call(new GetRequest(path, Map.copyOf(params)));
    var body = new String(response.getBytes(), StandardCharsets.UTF_8);
    if (response.getStatus() != 200) {
      throw new SonarApiException(response.getStatus(), path, firstErrorMessage(body).orElse("HTTP " + response.getStatus()));
    }
    try {
      return JsonParser.parseString(body).getAsJsonObject();
    } catch (JsonParseException | IllegalStateException e) {
      throw new SonarApiException(502, path, "unexpected response format");
    }
  }

  public static Map<String, String> params(String... keyValues) {
    if (keyValues.length % 2 != 0) {
      throw new IllegalArgumentException("params must be key/value pairs");
    }
    var params = new LinkedHashMap<String, String>();
    for (int i = 0; i < keyValues.length; i += 2) {
      if (keyValues[i + 1] != null) {
        params.put(keyValues[i], keyValues[i + 1]);
      }
    }
    return params;
  }

  private static Optional<String> firstErrorMessage(String body) {
    try {
      var errors = JsonParser.parseString(body).getAsJsonObject().getAsJsonArray("errors");
      return errors == null || errors.isEmpty()
        ? Optional.empty()
        : Optional.of(errors.get(0).getAsJsonObject().get("msg").getAsString());
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  private record GetRequest(String path, Map<String, String> params) implements LocalConnector.LocalRequest {

    @Override
    public String getPath() {
      return path;
    }

    @Override
    public String getMediaType() {
      return JSON;
    }

    @Override
    public String getMethod() {
      return "GET";
    }

    @Override
    public boolean hasParam(String key) {
      return params.containsKey(key);
    }

    @Override
    public String getParam(String key) {
      return params.get(key);
    }

    @Override
    public List<String> getMultiParam(String key) {
      return params.containsKey(key) ? List.of(params.get(key)) : List.of();
    }

    @Override
    public Optional<String> getHeader(String name) {
      return Optional.empty();
    }

    @Override
    public Map<String, String[]> getParameterMap() {
      var map = new LinkedHashMap<String, String[]>();
      params.forEach((k, v) -> map.put(k, new String[] {v}));
      return map;
    }
  }
}
