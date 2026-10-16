package org.communityvortex.server;

import com.google.gson.JsonObject;

/** Status and JSON body of a service answer. */
public record ApiResponse(int status, JsonObject body) {

  public static ApiResponse ok(JsonObject body) {
    return new ApiResponse(200, body);
  }

  public static ApiResponse error(int status, String message) {
    return of(new RequestException(status, message));
  }

  public static ApiResponse of(RequestException e) {
    return new ApiResponse(e.status(), e.toJson());
  }
}
