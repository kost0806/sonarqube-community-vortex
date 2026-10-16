package org.communityvortex.mcp.branch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** Reads the checked-out branch from {@code .git/HEAD}, supporting worktrees ({@code .git} file with {@code gitdir:}). */
public final class GitHead {

  private static final String REF_PREFIX = "ref: refs/heads/";
  private static final String GITDIR_PREFIX = "gitdir:";

  private GitHead() {
  }

  public static Optional<String> currentBranch(Path workspace) {
    for (var dir = workspace.toAbsolutePath(); dir != null; dir = dir.getParent()) {
      var dotGit = dir.resolve(".git");
      if (Files.exists(dotGit)) {
        return gitDir(dotGit).flatMap(GitHead::branchFromHead);
      }
    }
    return Optional.empty();
  }

  private static Optional<Path> gitDir(Path dotGit) {
    if (Files.isDirectory(dotGit)) {
      return Optional.of(dotGit);
    }
    return readFirstLine(dotGit)
      .filter(line -> line.startsWith(GITDIR_PREFIX))
      .map(line -> dotGit.getParent().resolve(line.substring(GITDIR_PREFIX.length()).trim()).normalize());
  }

  private static Optional<String> branchFromHead(Path gitDir) {
    return readFirstLine(gitDir.resolve("HEAD"))
      .filter(line -> line.startsWith(REF_PREFIX))
      .map(line -> line.substring(REF_PREFIX.length()).trim())
      .filter(branch -> !branch.isEmpty());
  }

  private static Optional<String> readFirstLine(Path file) {
    try (var lines = Files.lines(file, StandardCharsets.UTF_8)) {
      return lines.findFirst().map(String::trim);
    } catch (IOException | RuntimeException e) {
      return Optional.empty();
    }
  }
}
