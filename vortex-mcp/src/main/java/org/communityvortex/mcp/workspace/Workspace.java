package org.communityvortex.mcp.workspace;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** The directory the agent works in. Every path coming from a tool call is resolved and confined here. */
public final class Workspace {

  /** Never source code, wherever they appear. */
  public static final Set<String> IGNORED_DIRECTORIES = Set.of(
    ".git", ".hg", ".svn", ".idea", ".vscode", ".gradle", ".mvn", ".next", ".nuxt", ".venv", "venv",
    "__pycache__", "node_modules", ".vortex");

  /** Build output, unless inside a {@code src} tree where e.g. {@code com/acme/build} is a package. */
  public static final Set<String> BUILD_OUTPUT_DIRECTORIES = Set.of("target", "build", "dist", "out", "bin", "obj", "coverage");

  static final int MAX_FILES = 50_000;

  private final Path root;

  public Workspace(Path root) {
    try {
      this.root = root.toRealPath();
    } catch (IOException e) {
      throw new UncheckedIOException("Workspace not readable: " + root, e);
    }
  }

  public Path root() {
    return root;
  }

  /**
   * Resolves a workspace-relative or absolute path to an existing regular file inside the workspace.
   *
   * @throws IllegalArgumentException when the path escapes the workspace (also through symlinks) or is not a file
   */
  public Path resolveFile(String userPath) {
    if (userPath == null || userPath.isBlank()) {
      throw new IllegalArgumentException("path is required");
    }
    var candidate = root.resolve(userPath.trim()).normalize();
    if (!candidate.startsWith(root)) {
      throw new IllegalArgumentException("path is outside the workspace: " + userPath);
    }
    if (!Files.isRegularFile(candidate)) {
      throw new IllegalArgumentException("not a file in the workspace: " + userPath);
    }
    try {
      var real = candidate.toRealPath();
      if (!real.startsWith(root)) {
        throw new IllegalArgumentException("path is outside the workspace: " + userPath);
      }
      return real;
    } catch (IOException e) {
      throw new IllegalArgumentException("cannot read " + userPath, e);
    }
  }

  /** Workspace-relative path with forward slashes. */
  public String relative(Path file) {
    return root.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
  }

  /** Regular files outside ignored directories, in a stable order, capped at {@value #MAX_FILES}. */
  public List<Path> files() {
    var files = new ArrayList<Path>();
    try {
      Files.walkFileTree(root, new SimpleFileVisitor<>() {
        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
          return dir.equals(root) || !isIgnored(root.relativize(dir)) ? FileVisitResult.CONTINUE : FileVisitResult.SKIP_SUBTREE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
          if (attrs.isRegularFile()) {
            files.add(file);
          }
          return files.size() >= MAX_FILES ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exc) {
          return FileVisitResult.CONTINUE;
        }
      });
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot list workspace files", e);
    }
    files.sort(null);
    return List.copyOf(files);
  }

  private static boolean isIgnored(Path relativeDir) {
    var name = relativeDir.getFileName().toString();
    if (IGNORED_DIRECTORIES.contains(name)) {
      return true;
    }
    if (!BUILD_OUTPUT_DIRECTORIES.contains(name)) {
      return false;
    }
    for (int i = 0; i < relativeDir.getNameCount() - 1; i++) {
      if (relativeDir.getName(i).toString().equals("src")) {
        return false;
      }
    }
    return true;
  }
}
