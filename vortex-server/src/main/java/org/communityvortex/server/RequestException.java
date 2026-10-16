package org.communityvortex.server;

import com.google.gson.JsonObject;

/** A request the service refuses, with the HTTP status and the error body the SonarQube CLI understands. */
public class RequestException extends RuntimeException {

  private final int status;
  private final transient JsonObject meta;

  public RequestException(int status, String message) {
    this(status, message, null);
  }

  public RequestException(int status, String message, JsonObject meta) {
    super(message);
    this.status = status;
    this.meta = meta;
  }

  public int status() {
    return status;
  }

  public JsonObject toJson() {
    var json = new JsonObject();
    json.addProperty("message", getMessage());
    if (meta != null) {
      json.add("meta", meta);
    }
    return json;
  }
}
