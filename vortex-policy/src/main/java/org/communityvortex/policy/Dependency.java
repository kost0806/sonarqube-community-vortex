package org.communityvortex.policy;

/**
 * A declared third-party dependency.
 *
 * @param ecosystem {@code maven}, {@code npm}, {@code pypi}, ...
 * @param name      ecosystem-specific name; Maven uses {@code groupId:artifactId}
 * @param version   declared version or range, may be {@code null}
 * @param file      manifest path relative to the workspace
 * @param line      1-based line of the declaration in {@code file}
 */
public record Dependency(String ecosystem, String name, String version, String file, int line) {

  public Dependency {
    if (ecosystem == null || ecosystem.isBlank()) {
      throw new IllegalArgumentException("ecosystem is required");
    }
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name is required");
    }
  }
}
