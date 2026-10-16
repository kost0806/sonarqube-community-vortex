package org.communityvortex.mcp.verify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.communityvortex.mcp.FakeSonar;
import org.communityvortex.mcp.analysis.Analyzer;
import org.communityvortex.mcp.index.CodeIndex;
import org.communityvortex.mcp.workspace.Workspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VerifierTest {

  @TempDir
  Path root;
  private final List<List<Path>> analysed = new ArrayList<>();
  private Analyzer.Report analysis = new Analyzer.Report(List.of(), List.of(), List.of(), List.of());
  private Verifier verifier;
  private FakeSonar server;

  @BeforeEach
  void setUp() throws Exception {
    write("src/main/java/com/acme/domain/Order.java", """
      package com.acme.domain;

      import java.util.List;
      import com.acme.infra.Db;

      class Order {}
      """);
    write("web/src/ui/App.ts", "import { q } from '../db/query';\n");
    write("pom.xml", "<project><dependencies><dependency><groupId>log4j</groupId><artifactId>log4j</artifactId></dependency></dependencies></project>");
    write("package.json", "{\"dependencies\":{\"react\":\"18\"}}");
    var workspace = new Workspace(root);
    server = new FakeSonar().reply("api/vortex/policy", PolicySourceTest.POLICY);
    Analyzer fakeAnalyzer = files -> {
      analysed.add(files);
      return analysis;
    };
    verifier = new Verifier(workspace, fakeAnalyzer, new CodeIndex(workspace), new PolicySource(server, "acme"));
  }

  private void write(String relative, String content) throws Exception {
    var file = root.resolve(relative);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
  }

  @Test
  void reportsArchitectureViolationsWithImportLines() {
    var report = verifier.verify(List.of("src/main/java/com/acme/domain/Order.java", "web/src/ui/App.ts"));

    assertThat(report.passed()).isFalse();
    assertThat(report.architectureViolations())
      .extracting(Verifier.ArchitectureFinding::file, Verifier.ArchitectureFinding::line, Verifier.ArchitectureFinding::target)
      .containsExactly(
        org.assertj.core.groups.Tuple.tuple("src/main/java/com/acme/domain/Order.java", 4, "com.acme.infra.Db"),
        org.assertj.core.groups.Tuple.tuple("web/src/ui/App.ts", 1, "web/src/db/query"));
    assertThat(analysed).singleElement().satisfies(files -> assertThat(files).hasSize(2));
  }

  @Test
  void checksManifestsAgainstDependencyPolicyWithoutAnalysingThem() {
    var report = verifier.verify(List.of("pom.xml", "package.json"));

    assertThat(report.dependencyViolations())
      .extracting(Verifier.DependencyFinding::dependency, Verifier.DependencyFinding::message)
      .containsExactly(org.assertj.core.groups.Tuple.tuple("log4j:log4j",
        "maven dependency 'log4j:log4j' (pom.xml:1) is denied by policy 'maven:log4j:log4j'"));
    assertThat(analysed).isEmpty();
    assertThat(report.passed()).isFalse();
  }

  @Test
  void passesWhenNothingIsReportedAndCountsIssuesBySeverity() {
    assertThat(verifier.verify(List.of("package.json")).passed()).isTrue();

    analysis = new Analyzer.Report(List.of(
      new Analyzer.Finding("web/src/ui/App.ts", 1, 1, "ts:S1", "r", "MAJOR", "BUG", "m", false),
      new Analyzer.Finding("web/src/ui/App.ts", 1, 1, "ts:S2", "r", null, null, "m", true)), List.of("x.txt"), List.of(), List.of("w"));
    var report = verifier.verify(List.of("web/src/ui/App.ts"));

    assertThat(report.issuesBySeverity()).containsEntry("MAJOR", 1).containsEntry("UNKNOWN", 1);
    assertThat(report.unsupportedFiles()).containsExactly("x.txt");
    assertThat(report.warnings()).contains("w");
  }

  @Test
  void deduplicatesRepeatedPaths() {
    verifier.verify(List.of("web/src/ui/App.ts", "./web/src/ui/App.ts"));

    assertThat(analysed.get(0)).hasSize(1);
  }

  @Test
  void validatesInput() {
    assertThatThrownBy(() -> verifier.verify(List.of())).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> verifier.verify(Collections.nCopies(Verifier.MAX_FILES + 1, "pom.xml")))
      .hasMessageContaining("at most");
    assertThatThrownBy(() -> verifier.verify(List.of("../outside.txt"))).hasMessageContaining("outside the workspace");
  }

  @Test
  void withoutPluginOnlyAnalysisRunsAndAWarningExplainsWhy() {
    server.fail("api/vortex/policy", 404, "Unknown url : api/vortex/policy");
    var workspace = new Workspace(root);
    var plain = new Verifier(workspace, files -> analysis, new CodeIndex(workspace), new PolicySource(server, "acme"));

    var report = plain.verify(List.of("src/main/java/com/acme/domain/Order.java"));

    assertThat(report.architectureViolations()).isEmpty();
    assertThat(report.warnings()).anySatisfy(w -> assertThat(w).contains("not installed"));
  }

  @Test
  void importLineFallsBackToLastSegment() {
    assertThat(Verifier.importLine("x\nimport { q } from '../db/query';\n", "web/src/db/query")).isEqualTo(2);
    assertThat(Verifier.importLine("nothing here", "a.b.C")).isZero();
  }
}
