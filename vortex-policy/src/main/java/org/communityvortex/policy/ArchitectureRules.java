package org.communityvortex.policy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Parses and evaluates the architecture rule DSL.
 *
 * <pre>
 * # comment
 * deny com.acme.domain.** -> com.acme.infra.**, java.sql.**
 * deny src/ui/** -> src/db/**
 * </pre>
 */
public final class ArchitectureRules {

  private static final String KEYWORD = "deny";
  private static final String ARROW = "->";
  private static final String SYNTAX_HINT = "expected 'deny <from> -> <to>'";

  private ArchitectureRules() {
  }

  public record Parsed(List<ArchitectureRule> rules, List<String> errors) {
    public Parsed {
      rules = List.copyOf(rules);
      errors = List.copyOf(errors);
    }
  }

  public static Parsed parse(String text) {
    var rules = new ArrayList<ArchitectureRule>();
    var errors = new ArrayList<String>();
    if (text == null) {
      return new Parsed(rules, errors);
    }
    var lines = text.split("\\R", -1);
    for (int i = 0; i < lines.length; i++) {
      var lineNumber = i + 1;
      var line = stripComment(lines[i]);
      if (line.isEmpty()) {
        continue;
      }
      try {
        rules.addAll(parseLine(lineNumber, line));
      } catch (IllegalArgumentException e) {
        errors.add("line " + lineNumber + ": " + e.getMessage());
      }
    }
    return new Parsed(rules, errors);
  }

  public static List<ArchitectureViolation> check(String source, Collection<String> targets, List<ArchitectureRule> rules) {
    var violations = new ArrayList<ArchitectureViolation>();
    for (var target : targets) {
      for (var rule : rules) {
        if (rule.forbids(source, target)) {
          violations.add(new ArchitectureViolation(rule, source, target));
        }
      }
    }
    return List.copyOf(violations);
  }

  private static List<ArchitectureRule> parseLine(int lineNumber, String line) {
    var keywordEnd = firstWhitespace(line);
    var keyword = keywordEnd < 0 ? line : line.substring(0, keywordEnd);
    if (!KEYWORD.equalsIgnoreCase(keyword)) {
      throw new IllegalArgumentException("unknown keyword '" + keyword + "' (expected '" + KEYWORD + "')");
    }
    var body = keywordEnd < 0 ? "" : line.substring(keywordEnd).trim();
    var arrow = body.indexOf(ARROW);
    if (arrow < 0) {
      throw new IllegalArgumentException(SYNTAX_HINT);
    }
    var fromText = body.substring(0, arrow).trim();
    var targetTexts = body.substring(arrow + ARROW.length()).split(",", -1);
    if (fromText.isEmpty() || anyBlank(targetTexts)) {
      throw new IllegalArgumentException(SYNTAX_HINT);
    }
    var from = GlobPattern.parse(fromText);
    var rules = new ArrayList<ArchitectureRule>();
    for (var targetText : targetTexts) {
      rules.add(new ArchitectureRule(lineNumber, from, GlobPattern.parse(targetText)));
    }
    return rules;
  }

  private static String stripComment(String line) {
    var hash = line.indexOf('#');
    return (hash >= 0 ? line.substring(0, hash) : line).trim();
  }

  private static int firstWhitespace(String text) {
    for (int i = 0; i < text.length(); i++) {
      if (Character.isWhitespace(text.charAt(i))) {
        return i;
      }
    }
    return -1;
  }

  private static boolean anyBlank(String[] values) {
    for (var value : values) {
      if (value.isBlank()) {
        return true;
      }
    }
    return false;
  }
}
