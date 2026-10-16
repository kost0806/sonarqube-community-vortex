package org.communityvortex.policy;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Denied and (optional) allowed dependency patterns of the form {@code [ecosystem:]name-glob},
 * e.g. {@code maven:log4j:log4j}, {@code npm:@legacy/*} or {@code moment} (any ecosystem).
 * Denied patterns win. Allow-list entries restrict only the ecosystems they name (an entry without
 * ecosystem restricts all): within those, anything not matched is reported.
 */
public final class DependencyPolicy {

  private static final Map<String, String> ECOSYSTEM_ALIASES = Map.of(
    "maven", "maven",
    "gradle", "maven",
    "npm", "npm",
    "pypi", "pypi",
    "go", "go",
    "nuget", "nuget",
    "cargo", "cargo");

  private final List<DependencyPattern> denied;
  private final List<DependencyPattern> allowed;

  private DependencyPolicy(List<DependencyPattern> denied, List<DependencyPattern> allowed) {
    this.denied = denied;
    this.allowed = allowed;
  }

  public static DependencyPolicy of(List<String> denied, List<String> allowed) {
    return new DependencyPolicy(parseAll(denied), parseAll(allowed));
  }

  public record Violation(Dependency dependency, String reason) {
    public String message() {
      return dependency.ecosystem() + " dependency '" + dependency.name() + "' (" + dependency.file() + ":" + dependency.line() + ") is " + reason;
    }
  }

  public Optional<Violation> evaluate(Dependency dependency) {
    for (var pattern : denied) {
      if (pattern.matches(dependency)) {
        return Optional.of(new Violation(dependency, "denied by policy '" + pattern.text() + "'"));
      }
    }
    var governing = allowed.stream().filter(p -> p.governs(dependency)).toList();
    if (!governing.isEmpty() && governing.stream().noneMatch(p -> p.matches(dependency))) {
      return Optional.of(new Violation(dependency, "not in the allowed dependency list"));
    }
    return Optional.empty();
  }

  public boolean isEmpty() {
    return denied.isEmpty() && allowed.isEmpty();
  }

  public List<String> deniedPatterns() {
    return denied.stream().map(DependencyPattern::text).toList();
  }

  public List<String> allowedPatterns() {
    return allowed.stream().map(DependencyPattern::text).toList();
  }

  static String canonicalEcosystem(String ecosystem) {
    var lower = ecosystem.toLowerCase(Locale.ROOT);
    return ECOSYSTEM_ALIASES.getOrDefault(lower, lower);
  }

  private static List<DependencyPattern> parseAll(List<String> texts) {
    return texts.stream()
      .filter(t -> t != null && !t.isBlank())
      .map(DependencyPattern::parse)
      .toList();
  }

  private record DependencyPattern(String text, String ecosystem, String nameGlob) {

    static DependencyPattern parse(String raw) {
      var text = raw.trim();
      var colon = text.indexOf(':');
      if (colon > 0) {
        var prefix = text.substring(0, colon).toLowerCase(Locale.ROOT);
        if (ECOSYSTEM_ALIASES.containsKey(prefix)) {
          return new DependencyPattern(text, ECOSYSTEM_ALIASES.get(prefix), text.substring(colon + 1));
        }
      }
      return new DependencyPattern(text, null, text);
    }

    /** An allow-list entry only restricts its own ecosystem; an entry without ecosystem restricts all. */
    boolean governs(Dependency dependency) {
      return ecosystem == null || ecosystem.equals(canonicalEcosystem(dependency.ecosystem()));
    }

    boolean matches(Dependency dependency) {
      var dependencyEcosystem = canonicalEcosystem(dependency.ecosystem());
      if (!governs(dependency)) {
        return false;
      }
      var name = normalize(dependency.name(), dependencyEcosystem);
      var glob = normalize(nameGlob, dependencyEcosystem);
      return globToRegex(glob).matcher(name).matches();
    }

    private static String normalize(String name, String ecosystem) {
      var lower = name.toLowerCase(Locale.ROOT);
      // PEP 503: runs of '-', '_' and '.' are equivalent in Python package names
      return "pypi".equals(ecosystem) ? lower.replaceAll("[-_.]+", "-") : lower;
    }

    private static Pattern globToRegex(String glob) {
      var regex = new StringBuilder();
      // runs of '*' are equivalent to one and would only add backtracking
      for (char c : glob.replaceAll("\\*{2,}", "*").toCharArray()) {
        switch (c) {
          case '*' -> regex.append(".*");
          case '?' -> regex.append('.');
          default -> regex.append(Pattern.quote(String.valueOf(c)));
        }
      }
      return Pattern.compile(regex.toString());
    }
  }
}
