package org.communityvortex.mcp.verify;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.communityvortex.mcp.analysis.Analyzer;
import org.communityvortex.mcp.deps.ManifestScanner;
import org.communityvortex.mcp.index.CodeIndex;
import org.communityvortex.mcp.workspace.Workspace;
import org.communityvortex.policy.ArchitectureRules;
import org.communityvortex.policy.ArchitectureViolation;

/**
 * The verification loop: local analysis with the server's rules, plus the project's architecture and
 * dependency policies, on the files the agent just changed.
 */
public final class Verifier {

  public static final int MAX_FILES = 50;

  public record ArchitectureFinding(String file, int line, String source, String target, String rule, String message) {
  }

  public record DependencyFinding(String file, int line, String ecosystem, String dependency, String message) {
  }

  public record Report(boolean passed, Map<String, Integer> issuesBySeverity, List<Analyzer.Finding> issues,
    List<ArchitectureFinding> architectureViolations, List<DependencyFinding> dependencyViolations,
    List<String> unsupportedFiles, List<String> failedFiles, List<String> warnings) {
  }

  private final Workspace workspace;
  private final Analyzer analyzer;
  private final CodeIndex index;
  private final PolicySource policies;

  public Verifier(Workspace workspace, Analyzer analyzer, CodeIndex index, PolicySource policies) {
    this.workspace = workspace;
    this.analyzer = analyzer;
    this.index = index;
    this.policies = policies;
  }

  public Report verify(List<String> paths) {
    if (paths == null || paths.isEmpty()) {
      throw new IllegalArgumentException("paths must list at least one file");
    }
    if (paths.size() > MAX_FILES) {
      throw new IllegalArgumentException("at most " + MAX_FILES + " files per call");
    }
    var files = new ArrayList<Path>(new LinkedHashSet<>(paths.stream().map(workspace::resolveFile).toList()));
    var policy = policies.get();
    var warnings = new ArrayList<>(policy.warnings());

    var sources = new ArrayList<Path>();
    var dependencyFindings = new ArrayList<DependencyFinding>();
    for (var file : files) {
      var relative = workspace.relative(file);
      if (ManifestScanner.isManifest(relative)) {
        dependencyFindings.addAll(checkManifest(file, relative, policy));
      } else {
        sources.add(file);
      }
    }
    var architectureFindings = new ArrayList<ArchitectureFinding>();
    if (!policy.architectureRules().isEmpty()) {
      for (var file : sources) {
        architectureFindings.addAll(checkArchitecture(file, policy));
      }
    }
    var analysis = sources.isEmpty() ? new Analyzer.Report(List.of(), List.of(), List.of(), List.of()) : analyzer.analyze(sources);
    warnings.addAll(analysis.warnings());

    var bySeverity = new TreeMap<String, Integer>();
    analysis.findings().forEach(f -> bySeverity.merge(f.severity() == null ? "UNKNOWN" : f.severity(), 1, Integer::sum));
    var passed = analysis.findings().isEmpty() && architectureFindings.isEmpty() && dependencyFindings.isEmpty()
      && analysis.failedFiles().isEmpty();
    return new Report(passed, bySeverity, analysis.findings(), architectureFindings, dependencyFindings,
      analysis.unsupportedFiles(), analysis.failedFiles(), warnings);
  }

  private List<DependencyFinding> checkManifest(Path file, String relative, PolicySource.Policy policy) {
    if (policy.dependencies().isEmpty()) {
      return List.of();
    }
    var findings = new ArrayList<DependencyFinding>();
    for (var dependency : ManifestScanner.scan(relative, read(file))) {
      policy.dependencies().evaluate(dependency).ifPresent(v -> findings.add(
        new DependencyFinding(relative, dependency.line(), dependency.ecosystem(), dependency.name(), v.message())));
    }
    return findings;
  }

  private List<ArchitectureFinding> checkArchitecture(Path file, PolicySource.Policy policy) {
    var facts = index.facts(file);
    if (facts.isEmpty()) {
      return List.of();
    }
    var relative = facts.get().file();
    var unique = new LinkedHashMap<String, ArchitectureViolation>();
    for (var source : new LinkedHashSet<>(List.of(facts.get().unit(), relative))) {
      for (var violation : ArchitectureRules.check(source, facts.get().imports(), policy.architectureRules())) {
        unique.putIfAbsent(violation.rule().text() + "|" + violation.target(), violation);
      }
    }
    var content = read(file);
    return unique.values().stream()
      .map(v -> new ArchitectureFinding(relative, importLine(content, v.target()), v.source(), v.target(), v.rule().text(), v.message()))
      .toList();
  }

  /** Best-effort line of the import statement that introduced {@code target}. */
  static int importLine(String content, String target) {
    var lines = content.split("\\R", -1);
    var lastSegment = target.substring(Math.max(target.lastIndexOf('.'), target.lastIndexOf('/')) + 1);
    var fallback = 0;
    for (int i = 0; i < lines.length; i++) {
      var line = lines[i];
      var isImport = line.contains("import") || line.contains("require(") || line.contains("from ");
      if (isImport && line.contains(target)) {
        return i + 1;
      }
      if (fallback == 0 && isImport && line.contains(lastSegment)) {
        fallback = i + 1;
      }
    }
    return fallback;
  }

  private static String read(Path file) {
    try {
      if (Files.size(file) > ManifestScanner.MAX_BYTES * 2L) {
        throw new IllegalArgumentException(file.getFileName() + " is too large to verify");
      }
      return Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalArgumentException("cannot read " + file.getFileName(), e);
    }
  }
}
