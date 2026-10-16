package org.communityvortex.mcp.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;
import org.communityvortex.mcp.sonar.Params;
import org.communityvortex.mcp.sonar.SonarServer;
import org.communityvortex.mcp.sonar.SonarServerException;
import org.communityvortex.policy.ArchitectureRule;
import org.communityvortex.policy.DependencyPolicy;
import org.communityvortex.policy.GlobPattern;

/** The project's policies from {@code api/vortex/policy}, cached briefly because verification runs in a loop. */
public final class PolicySource {

  static final long TTL_NANOS = 60_000_000_000L;
  private static final String NOT_INSTALLED =
    "The Community Vortex plugin is not installed on the SonarQube server: architecture and dependency policies are not checked";

  public record Policy(List<ArchitectureRule> architectureRules, DependencyPolicy dependencies, String guidelines, List<String> warnings) {
    public Policy {
      architectureRules = List.copyOf(architectureRules);
      warnings = List.copyOf(warnings);
    }

    static Policy unavailable(String warning) {
      return new Policy(List.of(), DependencyPolicy.of(List.of(), List.of()), null, List.of(warning));
    }
  }

  private final SonarServer server;
  private final String projectKey;
  private final LongSupplier clock;
  private Policy cached;
  private long fetchedAt;

  public PolicySource(SonarServer server, String projectKey) {
    this(server, projectKey, System::nanoTime);
  }

  PolicySource(SonarServer server, String projectKey, LongSupplier clock) {
    this.server = server;
    this.projectKey = projectKey;
    this.clock = clock;
  }

  public synchronized Policy get() {
    var now = clock.getAsLong();
    if (cached == null || now - fetchedAt > TTL_NANOS) {
      cached = fetch();
      fetchedAt = now;
    }
    return cached;
  }

  private Policy fetch() {
    try {
      var policy = server.get("api/vortex/policy", Params.of("project", projectKey)).getAsJsonObject("policy");
      // rebuild rules from the server's parse so messages cite the line numbers of the setting itself
      var rules = new ArrayList<ArchitectureRule>();
      var warnings = new ArrayList<String>();
      for (var element : policy.getAsJsonArray("architectureRules")) {
        var rule = element.getAsJsonObject();
        try {
          rules.add(new ArchitectureRule(rule.get("line").getAsInt(),
            GlobPattern.parse(rule.get("from").getAsString()), GlobPattern.parse(rule.get("to").getAsString())));
        } catch (IllegalArgumentException e) {
          warnings.add("Architecture rule ignored: " + e.getMessage());
        }
      }
      policy.getAsJsonArray("architectureRuleErrors").forEach(e -> warnings.add("Architecture rule ignored: " + e.getAsString()));
      var denied = new ArrayList<String>();
      policy.getAsJsonArray("deniedDependencies").forEach(d -> denied.add(d.getAsString()));
      var allowed = new ArrayList<String>();
      policy.getAsJsonArray("allowedDependencies").forEach(a -> allowed.add(a.getAsString()));
      var guidelines = policy.has("guidelines") && !policy.get("guidelines").isJsonNull() ? policy.get("guidelines").getAsString() : null;
      return new Policy(rules, DependencyPolicy.of(denied, allowed), guidelines, warnings);
    } catch (SonarServerException e) {
      if (e.status() == 404 && e.getMessage() != null && e.getMessage().contains("Unknown url")) {
        return Policy.unavailable(NOT_INSTALLED);
      }
      return Policy.unavailable("Policies unavailable: " + e.getMessage());
    }
  }
}
