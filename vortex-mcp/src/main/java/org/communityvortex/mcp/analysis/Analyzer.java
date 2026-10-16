package org.communityvortex.mcp.analysis;

import java.nio.file.Path;
import java.util.List;

/** Analyses workspace files with the project's server-side rules. */
public interface Analyzer {

  record Finding(String file, int line, int endLine, String ruleKey, String ruleName, String severity, String type,
    String message, boolean hasQuickFix) {
  }

  record Report(List<Finding> findings, List<String> unsupportedFiles, List<String> failedFiles, List<String> warnings) {
    public Report {
      findings = List.copyOf(findings);
      unsupportedFiles = List.copyOf(unsupportedFiles);
      failedFiles = List.copyOf(failedFiles);
      warnings = List.copyOf(warnings);
    }
  }

  Report analyze(List<Path> files);
}
