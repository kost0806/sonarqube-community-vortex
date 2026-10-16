package org.communityvortex.policy;

import java.util.Objects;

/** {@code deny <from> -> <to>}: code matching {@code from} must not depend on code matching {@code to}. */
public record ArchitectureRule(int line, GlobPattern from, GlobPattern to) {

  public ArchitectureRule {
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(to, "to");
  }

  public boolean forbids(String source, String target) {
    return from.matches(source) && to.matches(target);
  }

  public String text() {
    return "deny " + from.text() + " -> " + to.text();
  }
}
