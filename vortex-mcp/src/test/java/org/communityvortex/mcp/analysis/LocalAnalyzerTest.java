package org.communityvortex.mcp.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.communityvortex.mcp.FakeSonar;
import org.communityvortex.mcp.workspace.Workspace;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Runs the real SonarLint engine with the real Python analyzer. */
class LocalAnalyzerTest {

  @TempDir
  static Path workspaceDir;
  @TempDir
  static Path cacheDir;

  private static FakeSonar server;
  private static LocalAnalyzer analyzer;
  private static Workspace workspace;

  @BeforeAll
  static void setUp() throws Exception {
    Files.createDirectories(workspaceDir.resolve("app"));
    Files.writeString(workspaceDir.resolve("app/service.py"), """
      def compute():
          x = 9  # trailing comment
          return 1
      """);
    Files.writeString(workspaceDir.resolve("app/clean.py"), """
      def add(a, b):
          return a + b
      """);
    Files.writeString(workspaceDir.resolve("notes.unknownext"), "hello");
    Files.writeString(workspaceDir.resolve("Main.java"), "class Main {}");
    server = PythonProjectServer.create();
    workspace = new Workspace(workspaceDir);
    analyzer = new LocalAnalyzer(workspace, new PluginCache(server, cacheDir), new RuleRepository(server, "acme"), cacheDir, null);
  }

  @AfterAll
  static void tearDown() {
    analyzer.close();
  }

  @Test
  void reportsIssuesOfActiveRulesWithServerSeverity() {
    var report = analyzer.analyze(List.of(workspace.resolveFile("app/service.py")));

    assertThat(report.failedFiles()).isEmpty();
    assertThat(report.findings())
      .extracting(Analyzer.Finding::ruleKey, Analyzer.Finding::file, Analyzer.Finding::line, Analyzer.Finding::severity)
      .containsExactlyInAnyOrder(
        org.assertj.core.groups.Tuple.tuple("python:S1481", "app/service.py", 2, "MAJOR"),
        org.assertj.core.groups.Tuple.tuple("python:S139", "app/service.py", 2, "INFO"));
    assertThat(report.findings()).allSatisfy(f -> {
      assertThat(f.ruleName()).isNotBlank();
      assertThat(f.type()).isEqualTo("CODE_SMELL");
      assertThat(f.message()).isNotBlank();
    });
  }

  @Test
  void cleanFileHasNoFindingsAndAnalyzerIsDownloadedOnce() {
    var report = analyzer.analyze(List.of(workspace.resolveFile("app/clean.py")));
    analyzer.analyze(List.of(workspace.resolveFile("app/clean.py")));

    assertThat(report.findings()).isEmpty();
    assertThat(server.count("api/plugins/download")).isEqualTo(1);
    assertThat(cacheDir.resolve("plugins/abc123/sonar-python-plugin-5.32.0.37250.jar")).isRegularFile();
  }

  @Test
  void filesWithoutAnalyzerAreReportedAsUnsupported() {
    var report = analyzer.analyze(List.of(workspace.resolveFile("notes.unknownext"), workspace.resolveFile("Main.java")));

    assertThat(report.findings()).isEmpty();
    assertThat(report.unsupportedFiles()).containsExactlyInAnyOrder("notes.unknownext", "Main.java");
    assertThat(report.warnings()).anySatisfy(w -> assertThat(w).contains("no IDE-compatible analyzer for Java"));
  }

  @Test
  void unsupportedLanguagesDoNotReloadTheEngine() {
    var files = List.of(workspace.resolveFile("app/clean.py"), workspace.resolveFile("Main.java"));
    analyzer.analyze(files);
    var loads = analyzer.engineLoads();

    var report = analyzer.analyze(files);

    assertThat(analyzer.engineLoads()).isEqualTo(loads);
    assertThat(report.unsupportedFiles()).containsExactly("Main.java");
    assertThat(report.warnings()).anySatisfy(w -> assertThat(w).contains("no IDE-compatible analyzer for Java"));
  }
}
