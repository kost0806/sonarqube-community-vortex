package org.communityvortex.server;

import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * Configuration from environment variables.
 *
 * @param sonarUrl {@code SONAR_HOST_URL}: SonarQube as reachable from this service (often an internal URL)
 * @param bind     {@code VORTEX_BIND}, default {@code 0.0.0.0}
 * @param port     {@code VORTEX_PORT}, default {@code 9100}
 * @param cacheDir {@code VORTEX_CACHE_DIR}, analyzers and temporary request files
 * @param nodePath {@code VORTEX_NODE}, Node.js for JavaScript/TypeScript; {@code null} to use PATH
 * @param maxFiles {@code VORTEX_MAX_FILES} per request, default 100
 * @param threads  {@code VORTEX_THREADS}, HTTP worker threads (analyses themselves run one at a time)
 */
public record ServerConfig(URI sonarUrl, String bind, int port, Path cacheDir, Path nodePath, int maxFiles, int threads) {

  public ServerConfig {
    var scheme = sonarUrl.getScheme() == null ? "" : sonarUrl.getScheme().toLowerCase(Locale.ROOT);
    if (!scheme.equals("http") && !scheme.equals("https")) {
      throw new IllegalArgumentException("SONAR_HOST_URL must be an http(s) URL");
    }
    if (sonarUrl.getUserInfo() != null) {
      throw new IllegalArgumentException("SONAR_HOST_URL must not contain credentials");
    }
    if (port < 0 || port > 65535 || maxFiles < 1 || threads < 1) {
      throw new IllegalArgumentException("VORTEX_PORT, VORTEX_MAX_FILES and VORTEX_THREADS must be positive");
    }
  }

  public static ServerConfig fromEnvironment(Map<String, String> env, Path userHome) {
    var url = value(env, "SONAR_HOST_URL", null);
    if (url == null) {
      throw new IllegalArgumentException("SONAR_HOST_URL must be set");
    }
    var cache = value(env, "VORTEX_CACHE_DIR", null);
    var node = value(env, "VORTEX_NODE", null);
    return new ServerConfig(
      URI.create(url.endsWith("/") ? url : url + "/"),
      value(env, "VORTEX_BIND", "0.0.0.0"),
      integer(env, "VORTEX_PORT", 9100),
      cache == null ? userHome.resolve(".vortex-server") : Path.of(cache),
      node == null ? null : Path.of(node),
      integer(env, "VORTEX_MAX_FILES", 100),
      integer(env, "VORTEX_THREADS", 8));
  }

  private static String value(Map<String, String> env, String name, String defaultValue) {
    var value = env.get(name);
    return value == null || value.isBlank() ? defaultValue : value.trim();
  }

  private static int integer(Map<String, String> env, String name, int defaultValue) {
    var value = value(env, name, null);
    try {
      return value == null ? defaultValue : Integer.parseInt(value);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(name + " must be a number");
    }
  }
}
