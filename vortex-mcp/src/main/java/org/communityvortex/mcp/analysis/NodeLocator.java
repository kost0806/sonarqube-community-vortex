package org.communityvortex.mcp.analysis;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.sonarsource.sonarlint.core.commons.Version;

/** Finds Node.js for the JavaScript/TypeScript analyzer. */
final class NodeLocator {

  record Node(Path path, Version version) {
  }

  private NodeLocator() {
  }

  static Optional<Node> locate(Path configured, String pathVariable) {
    var candidate = configured != null ? Optional.of(configured) : onPath(pathVariable);
    return candidate.flatMap(NodeLocator::withVersion);
  }

  static Optional<Path> onPath(String pathVariable) {
    if (pathVariable == null) {
      return Optional.empty();
    }
    var windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    var executable = windows ? "node.exe" : "node";
    for (var entry : pathVariable.split(java.io.File.pathSeparator)) {
      var directory = entry.trim();
      if (directory.length() >= 2 && directory.startsWith("\"") && directory.endsWith("\"")) {
        directory = directory.substring(1, directory.length() - 1);
      }
      try {
        var dir = Path.of(directory);
        // relative entries would resolve against the (untrusted) workspace
        if (directory.isEmpty() || !dir.isAbsolute()) {
          continue;
        }
        var file = dir.resolve(executable);
        if (Files.isRegularFile(file) && Files.isExecutable(file)) {
          return Optional.of(file);
        }
      } catch (InvalidPathException e) {
        // malformed PATH entry: skip it
      }
    }
    return Optional.empty();
  }

  private static Optional<Node> withVersion(Path node) {
    try {
      var builder = new ProcessBuilder(node.toString(), "--version").redirectErrorStream(true);
      builder.environment().remove("SONAR_TOKEN");
      var process = builder.start();
      if (!process.waitFor(10, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        return Optional.empty();
      }
      var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
      var version = output.startsWith("v") ? output.substring(1) : output;
      return Optional.of(new Node(node, Version.create(version)));
    } catch (IOException | RuntimeException e) {
      return Optional.empty();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    }
  }
}
