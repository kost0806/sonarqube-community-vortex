package org.communityvortex.mcp.branch;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.communityvortex.mcp.sonar.Params;
import org.communityvortex.mcp.sonar.SonarServer;
import org.communityvortex.mcp.sonar.SonarServerException;

/**
 * Chooses which SonarQube branch or pull request the agent's context refers to:
 * explicit tool argument, then configuration, then the checked-out git branch when the server
 * knows it (branch support via the community branch plugin), otherwise the main branch.
 */
public final class BranchResolver {

  public enum Source { ARGUMENT, CONFIGURATION, GIT, MAIN }

  public record Scope(String branch, String pullRequest, Source source, String note) {
    public static Scope main(String note) {
      return new Scope(null, null, Source.MAIN, note);
    }
  }

  private final SonarServer server;
  private final String projectKey;
  private final String configuredBranch;
  private final String configuredPullRequest;
  private final Supplier<Optional<String>> gitBranch;

  public BranchResolver(SonarServer server, String projectKey, String configuredBranch, String configuredPullRequest, Path workspace) {
    this(server, projectKey, configuredBranch, configuredPullRequest, () -> GitHead.currentBranch(workspace));
  }

  BranchResolver(SonarServer server, String projectKey, String configuredBranch, String configuredPullRequest,
    Supplier<Optional<String>> gitBranch) {
    this.server = server;
    this.projectKey = projectKey;
    this.configuredBranch = configuredBranch;
    this.configuredPullRequest = configuredPullRequest;
    this.gitBranch = gitBranch;
  }

  public Scope resolve(String requestedBranch, String requestedPullRequest) {
    if (requestedBranch != null && requestedPullRequest != null) {
      throw new IllegalArgumentException("Pass only one of 'branch' and 'pullRequest'");
    }
    if (requestedBranch != null || requestedPullRequest != null) {
      return new Scope(requestedBranch, requestedPullRequest, Source.ARGUMENT, null);
    }
    if (configuredBranch != null || configuredPullRequest != null) {
      return new Scope(configuredBranch, configuredPullRequest, Source.CONFIGURATION, null);
    }
    var checkedOut = gitBranch.get();
    if (checkedOut.isEmpty()) {
      return Scope.main("no git branch detected");
    }
    var branch = checkedOut.get();
    Set<String> known;
    String mainBranch;
    try {
      known = new HashSet<>();
      mainBranch = null;
      for (var element : server.get("api/project_branches/list", Params.of("project", projectKey)).getAsJsonArray("branches")) {
        var json = element.getAsJsonObject();
        var name = json.get("name").getAsString();
        known.add(name);
        if (json.has("isMain") && json.get("isMain").getAsBoolean()) {
          mainBranch = name;
        }
      }
    } catch (SonarServerException e) {
      return Scope.main("could not list branches: " + e.getMessage());
    }
    if (branch.equals(mainBranch)) {
      return Scope.main("git branch '" + branch + "' is the main branch");
    }
    if (known.contains(branch)) {
      return new Scope(branch, null, Source.GIT, null);
    }
    return Scope.main("git branch '" + branch + "' has not been analysed on the server");
  }
}
