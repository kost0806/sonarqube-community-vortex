package org.communityvortex.plugin.ws;

/** A failed in-process web service call, carrying the HTTP status to relay to the client. */
public class SonarApiException extends RuntimeException {

  private final int status;

  public SonarApiException(int status, String path, String message) {
    super(path + ": " + message);
    this.status = status;
  }

  public int status() {
    return status;
  }
}
