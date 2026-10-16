package org.communityvortex.mcp.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceTest {

  @TempDir
  Path root;

  @Test
  void resolvesRelativeAndAbsolutePathsInside() throws Exception {
    Files.createDirectories(root.resolve("src"));
    var file = Files.writeString(root.resolve("src/A.java"), "class A {}");
    var workspace = new Workspace(root);

    assertThat(workspace.resolveFile("src/A.java")).isEqualTo(file.toRealPath());
    assertThat(workspace.resolveFile(file.toString())).isEqualTo(file.toRealPath());
    assertThat(workspace.relative(workspace.resolveFile("src/A.java"))).isEqualTo("src/A.java");
  }

  @Test
  void rejectsPathsOutsideOrMissing(@TempDir Path elsewhere) throws Exception {
    var outside = Files.writeString(elsewhere.resolve("secret.txt"), "x");
    Files.createDirectories(root.resolve("dir"));
    var workspace = new Workspace(root);

    assertThatThrownBy(() -> workspace.resolveFile("../secret.txt")).hasMessageContaining("outside the workspace");
    assertThatThrownBy(() -> workspace.resolveFile(outside.toString())).hasMessageContaining("outside the workspace");
    assertThatThrownBy(() -> workspace.resolveFile("dir")).hasMessageContaining("not a file");
    assertThatThrownBy(() -> workspace.resolveFile(" ")).hasMessageContaining("required");
  }

  @Test
  void rejectsSymlinksEscapingTheWorkspace(@TempDir Path elsewhere) throws Exception {
    var outside = Files.writeString(elsewhere.resolve("secret.txt"), "x");
    try {
      Files.createSymbolicLink(root.resolve("link.txt"), outside);
    } catch (IOException | UnsupportedOperationException e) {
      assumeTrue(false, "symbolic links not permitted on this machine");
    }
    var workspace = new Workspace(root);

    assertThatThrownBy(() -> workspace.resolveFile("link.txt")).hasMessageContaining("outside the workspace");
  }

  @Test
  void listsFilesSkippingIgnoredDirectories() throws Exception {
    Files.createDirectories(root.resolve("src"));
    Files.createDirectories(root.resolve("node_modules/x"));
    Files.createDirectories(root.resolve("target"));
    Files.writeString(root.resolve("src/b.ts"), "");
    Files.writeString(root.resolve("a.py"), "");
    Files.writeString(root.resolve("node_modules/x/i.js"), "");
    Files.writeString(root.resolve("target/A.class"), "");

    var workspace = new Workspace(root);

    assertThat(workspace.files()).extracting(workspace::relative).containsExactly("a.py", "src/b.ts");
  }

  @Test
  void buildOutputNamesInsideSourceTreesAreKept() throws Exception {
    Files.createDirectories(root.resolve("src/main/java/com/acme/build"));
    Files.createDirectories(root.resolve("module/build/classes"));
    Files.writeString(root.resolve("src/main/java/com/acme/build/Step.java"), "");
    Files.writeString(root.resolve("module/build/classes/Gen.java"), "");

    var workspace = new Workspace(root);

    assertThat(workspace.files()).extracting(workspace::relative).containsExactly("src/main/java/com/acme/build/Step.java");
  }

  @Test
  void missingRootFailsFast() {
    assertThatThrownBy(() -> new Workspace(root.resolve("missing"))).isInstanceOf(UncheckedIOException.class);
  }
}
