package org.communityvortex.mcp.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.sonarsource.sonarlint.core.commons.api.SonarLanguage;

class WorkspaceInputFileTest {

  @ParameterizedTest
  @CsvSource({
    "src/test/java/a/FooTest.java, true",
    "src/main/java/a/Foo.java, false",
    "web/src/App.test.tsx, true",
    "tests/test_orders.py, true",
    "shop/orders_test.py, true",
    "shop/orders.py, false",
    "web/__tests__/x.js, true",
  })
  void detectsTestFiles(String path, boolean test) {
    assertThat(WorkspaceInputFile.looksLikeTest(path)).isEqualTo(test);
  }

  @Test
  void detectsLanguageByLongestDefaultSuffix() {
    assertThat(WorkspaceInputFile.detectLanguage("Order.java")).isEqualTo(SonarLanguage.JAVA);
    assertThat(WorkspaceInputFile.detectLanguage("App.TSX")).isEqualTo(SonarLanguage.TS);
    assertThat(WorkspaceInputFile.detectLanguage("orders.py")).isEqualTo(SonarLanguage.PYTHON);
    assertThat(WorkspaceInputFile.detectLanguage("notes.unknownext")).isNull();
  }

  @Test
  void exposesFileToTheEngine(@TempDir Path dir) throws Exception {
    var path = Files.writeString(dir.resolve("a.py"), "x = 1\n");
    var input = new WorkspaceInputFile(path, "a.py", SonarLanguage.PYTHON);

    assertThat(input.contents()).isEqualTo("x = 1\n");
    try (var stream = input.inputStream()) {
      assertThat(stream.readAllBytes()).hasSize(6);
    }
    assertThat(input.uri()).isEqualTo(path.toUri());
    assertThat((Path) input.getClientObject()).isEqualTo(path);
    assertThat(input.getPath()).isEqualTo(path.toString());
    assertThat(input.isTest()).isFalse();
    assertThat(input.language()).isEqualTo(SonarLanguage.PYTHON);
    assertThat(input).hasToString("a.py");
  }

  @Test
  void findsNodeOnPath(@TempDir Path dir) throws Exception {
    assertThat(NodeLocator.onPath(null)).isEmpty();
    assertThat(NodeLocator.onPath(dir.toString())).isEmpty();
    var windows = File.separatorChar == '\\';
    var node = Files.writeString(dir.resolve(windows ? "node.exe" : "node"), "");
    node.toFile().setExecutable(true);
    assertThat(NodeLocator.onPath(" " + File.pathSeparator + dir)).contains(node);
    // quoted and invalid entries are skipped instead of failing startup
    assertThat(NodeLocator.onPath("\"" + dir + "\"")).contains(node);
    assertThat(NodeLocator.onPath("bad\u0000entry" + File.pathSeparator + dir)).contains(node);
    // not a working executable: no version, so treated as absent
    assertThat(NodeLocator.locate(node, null)).isEmpty();
  }
}
