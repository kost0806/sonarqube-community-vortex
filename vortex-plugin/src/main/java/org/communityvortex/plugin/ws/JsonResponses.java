package org.communityvortex.plugin.ws;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.server.ws.Response;

/** Writes JSON bodies and relays failed in-process calls using SonarQube's {@code {"errors":[{"msg":...}]}} format. */
final class JsonResponses {

  private static final Logger LOG = LoggerFactory.getLogger(JsonResponses.class);
  private static final String JSON = "application/json";

  private JsonResponses() {
  }

  static void write(Response response, Supplier<JsonObject> body) {
    JsonObject json;
    int status;
    try {
      json = body.get();
      status = 200;
    } catch (SonarApiException e) {
      LOG.debug("Vortex request failed: {}", e.getMessage());
      status = relayableStatus(e.status());
      json = error(status == e.status() ? e.getMessage() : "Upstream web service failed");
    }
    send(response, status, json);
  }

  private static int relayableStatus(int status) {
    return status == 400 || status == 401 || status == 403 || status == 404 ? status : 502;
  }

  private static JsonObject error(String message) {
    var error = new JsonObject();
    error.addProperty("msg", message);
    var errors = new JsonArray();
    errors.add(error);
    var json = new JsonObject();
    json.add("errors", errors);
    return json;
  }

  private static void send(Response response, int status, JsonObject json) {
    var stream = response.stream().setMediaType(JSON).setStatus(status);
    try {
      stream.output().write(json.toString().getBytes(StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
