package org.communityvortex.mcp.analysis;

import java.nio.file.Path;
import java.util.List;

/** Analyses workspace files with the project's server-side rules. */
public interface Analyzer {

  /** Lines are 1-based; offsets are 0-based columns within the start and end line. */
  record Finding(String file, int line, int endLine, int startOffset, int endOffset, String ruleKey, String ruleName,
    String severity, String type, String message, boolean hasQuickFix) {

    public Finding(String file, int line, int endLine, String ruleKey, String ruleName, String severity, String type,
      String message, boolean hasQuickFix) {
      this(file, line, endLine, 0, 0, ruleKey, ruleName, severity, type, message, hasQuickFix);
    }
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
