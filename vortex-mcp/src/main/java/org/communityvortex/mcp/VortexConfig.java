package org.communityvortex.mcp;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * Runtime configuration, read from environment variables so that the token never appears on a command line.
 *
 * @param serverUrl   {@code SONAR_HOST_URL}
 * @param token       {@code SONAR_TOKEN}, a user token with Browse permission on the project
 * @param projectKey  {@code SONAR_PROJECT_KEY}
 * @param workspace   {@code VORTEX_WORKSPACE}, defaults to the working directory
 * @param branch      {@code SONAR_BRANCH}, optional fixed branch (otherwise the current git branch is used when analysed)
 * @param pullRequest {@code SONAR_PULL_REQUEST}, optional fixed pull request
 * @param cacheDir    {@code VORTEX_CACHE_DIR}, defaults to {@code ~/.vortex}
 * @param nodePath    {@code VORTEX_NODE}, Node.js used by the JS/TS analyzer; {@code null} to look it up on PATH
 */
public record VortexConfig(URI serverUrl, String token, String projectKey, Path workspace, String branch,
  String pullRequest, Path cacheDir, Path nodePath) {

  public VortexConfig {
    var scheme = serverUrl.getScheme() == null ? "" : serverUrl.getScheme().toLowerCase(Locale.ROOT);
    if (!scheme.equals("http") && !scheme.equals("https")) {
      throw new IllegalArgumentException("SONAR_HOST_URL must be an http(s) URL");
    }
    if (serverUrl.getUserInfo() != null) {
      throw new IllegalArgumentException("SONAR_HOST_URL must not contain credentials; use SONAR_TOKEN");
    }
    if (branch != null && pullRequest != null) {
      throw new IllegalArgumentException("Set only one of SONAR_BRANCH and SONAR_PULL_REQUEST");
    }
    if (!Files.isDirectory(workspace)) {
      throw new IllegalArgumentException("Workspace is not a directory: " + workspace);
    }
  }

  public static VortexConfig fromEnvironment(Map<String, String> env, Path workingDirectory, Path userHome) {
    var url = required(env, "SONAR_HOST_URL");
    // plain http would expose the token and let a network attacker swap the analyzer jars loaded into this JVM
    var uri = URI.create(url);
    if ("http".equalsIgnoreCase(uri.getScheme()) && !isLoopback(uri.getHost())
      && !"true".equalsIgnoreCase(optional(env, "VORTEX_ALLOW_INSECURE_HTTP"))) {
      throw new IllegalArgumentException("SONAR_HOST_URL must use https for non-local servers "
        + "(set VORTEX_ALLOW_INSECURE_HTTP=true to accept the risk)");
    }
    var workspace = optional(env, "VORTEX_WORKSPACE");
    var cache = optional(env, "VORTEX_CACHE_DIR");
    var node = optional(env, "VORTEX_NODE");
    return new VortexConfig(
      URI.create(url.endsWith("/") ? url : url + "/"),
      required(env, "SONAR_TOKEN"),
      required(env, "SONAR_PROJECT_KEY"),
      (workspace == null ? workingDirectory : Path.of(workspace)).toAbsolutePath().normalize(),
      optional(env, "SONAR_BRANCH"),
      optional(env, "SONAR_PULL_REQUEST"),
      cache == null ? userHome.resolve(".vortex") : Path.of(cache),
      node == null ? null : Path.of(node));
  }

  private static boolean isLoopback(String host) {
    return host != null && (host.equalsIgnoreCase("localhost") || host.startsWith("127.") || host.equals("[::1]") || host.equals("::1"));
  }

  private static String required(Map<String, String> env, String name) {
    var value = optional(env, name);
    if (value == null) {
      throw new IllegalArgumentException(name + " must be set");
    }
    return value;
  }

  private static String optional(Map<String, String> env, String name) {
    var value = env.get(name);
    return value == null || value.isBlank() ? null : value.trim();
  }

  @Override
  public String toString() {
    // never print the token
    return "VortexConfig[serverUrl=" + serverUrl + ", projectKey=" + projectKey + ", workspace=" + workspace + "]";
  }
}
