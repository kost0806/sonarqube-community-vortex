package org.communityvortex.mcp.sonar;

import java.util.LinkedHashMap;
import java.util.Map;

public final class Params {

  private Params() {
  }

  /** Builds an ordered parameter map from key/value pairs, skipping {@code null} values. */
  public static Map<String, String> of(String... keyValues) {
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
}
