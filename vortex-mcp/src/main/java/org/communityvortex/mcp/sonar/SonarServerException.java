package org.communityvortex.mcp.sonar;

public class SonarServerException extends RuntimeException {

  private final int status;

  public SonarServerException(int status, String message) {
    super(message);
    this.status = status;
  }

  public SonarServerException(String message, Throwable cause) {
    super(message, cause);
    this.status = -1;
  }

  /** HTTP status, or -1 when the server could not be reached. */
  public int status() {
    return status;
  }
}
