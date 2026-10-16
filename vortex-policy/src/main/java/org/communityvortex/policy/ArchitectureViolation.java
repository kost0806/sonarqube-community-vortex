package org.communityvortex.policy;

/** A dependency from {@code source} to {@code target} that an {@link ArchitectureRule} forbids. */
public record ArchitectureViolation(ArchitectureRule rule, String source, String target) {

  public String message() {
    return "'" + source + "' must not depend on '" + target + "' (rule line " + rule.line() + ": " + rule.text() + ")";
  }
}
