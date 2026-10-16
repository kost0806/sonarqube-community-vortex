package org.communityvortex.mcp.branch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.communityvortex.mcp.FakeSonar;
import org.communityvortex.mcp.branch.BranchResolver.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BranchResolverTest {

  private static final String BRANCHES = """
    {"branches":[{"name":"main","isMain":true,"type":"BRANCH"},{"name":"feature/cart","isMain":false,"type":"BRANCH"}]}""";

  private final FakeSonar server = new FakeSonar().reply("api/project_branches/list", BRANCHES);

  private BranchResolver resolver(String configuredBranch, String configuredPr, String gitBranch) {
    return new BranchResolver(server, "acme", configuredBranch, configuredPr, () -> Optional.ofNullable(gitBranch));
  }

  @Test
  void argumentWinsOverConfigurationAndGit() {
    var scope = resolver("dev", null, "feature/cart").resolve(null, "7");

    assertThat(scope.pullRequest()).isEqualTo("7");
    assertThat(scope.source()).isEqualTo(Source.ARGUMENT);
    assertThatThrownBy(() -> resolver(null, null, null).resolve("a", "1")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void configurationWinsOverGit() {
    var scope = resolver("dev", null, "feature/cart").resolve(null, null);

    assertThat(scope.branch()).isEqualTo("dev");
    assertThat(scope.source()).isEqualTo(Source.CONFIGURATION);
    assertThat(server.calls()).isEmpty();
  }

  @Test
  void usesAnalysedGitBranchFromTheBranchPlugin() {
    var scope = resolver(null, null, "feature/cart").resolve(null, null);

    assertThat(scope.branch()).isEqualTo("feature/cart");
    assertThat(scope.source()).isEqualTo(Source.GIT);
  }

  @Test
  void fallsBackToMainBranch() {
    assertThat(resolver(null, null, "main").resolve(null, null).note()).contains("is the main branch");
    assertThat(resolver(null, null, "feature/new").resolve(null, null).note()).contains("has not been analysed");
    assertThat(resolver(null, null, null).resolve(null, null).note()).isEqualTo("no git branch detected");
    server.fail("api/project_branches/list", 403, "denied");
    var scope = resolver(null, null, "feature/cart").resolve(null, null);
    assertThat(scope.source()).isEqualTo(Source.MAIN);
    assertThat(scope.note()).contains("could not list branches");
  }

  @Test
  void readsGitHeadIncludingWorktrees(@TempDir Path root) throws Exception {
    Files.createDirectories(root.resolve("repo/.git"));
    Files.writeString(root.resolve("repo/.git/HEAD"), "ref: refs/heads/feature/cart\n");
    Files.createDirectories(root.resolve("repo/sub/dir"));
    assertThat(GitHead.currentBranch(root.resolve("repo/sub/dir"))).contains("feature/cart");

    Files.createDirectories(root.resolve("gitdirs/wt"));
    Files.writeString(root.resolve("gitdirs/wt/HEAD"), "ref: refs/heads/hotfix\n");
    Files.createDirectories(root.resolve("worktree"));
    Files.writeString(root.resolve("worktree/.git"), "gitdir: ../gitdirs/wt\n");
    assertThat(GitHead.currentBranch(root.resolve("worktree"))).contains("hotfix");

    Files.writeString(root.resolve("repo/.git/HEAD"), "3f2a9c1\n");
    assertThat(GitHead.currentBranch(root.resolve("repo"))).isEmpty();
  }

  @Test
  void productionConstructorReadsTheWorkspace(@TempDir Path root) {
    var scope = new BranchResolver(server, "acme", null, null, root).resolve(null, null);

    assertThat(scope.source()).isEqualTo(Source.MAIN);
  }
}
