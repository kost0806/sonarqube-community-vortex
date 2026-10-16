package org.communityvortex.policy;

import java.util.regex.Pattern;

/**
 * A glob over dotted names ({@code com.acme.**}) or slash paths ({@code src/ui/**}).
 * <p>
 * {@code **} matches any number of segments (including none), {@code *} matches within one segment
 * and {@code ?} matches one character within a segment. A pattern containing {@code /} is a path
 * pattern; otherwise segments are separated by {@code .}.
 */
public final class GlobPattern {

  private static final String DOUBLE_STAR = "**";

  private final String text;
  private final char separator;
  private final Pattern regex;

  private GlobPattern(String text, char separator, Pattern regex) {
    this.text = text;
    this.separator = separator;
    this.regex = regex;
  }

  public static GlobPattern parse(String raw) {
    var text = raw == null ? "" : raw.trim();
    if (text.isEmpty()) {
      throw new IllegalArgumentException("pattern must not be empty");
    }
    if (text.chars().anyMatch(Character::isWhitespace)) {
      throw new IllegalArgumentException("pattern '" + text + "' must not contain whitespace");
    }
    var separator = text.indexOf('/') >= 0 ? '/' : '.';
    return new GlobPattern(text, separator, Pattern.compile(toRegex(text, separator)));
  }

  public boolean matches(String candidate) {
    var normalized = separator == '/' ? candidate.replace('\\', '/') : candidate;
    return regex.matcher(normalized).matches();
  }

  public String text() {
    return text;
  }

  @Override
  public String toString() {
    return text;
  }

  private static String toRegex(String text, char separator) {
    var sep = Pattern.quote(String.valueOf(separator));
    var segments = collapseDoubleStars(text.split(Pattern.quote(String.valueOf(separator)), -1));
    var regex = new StringBuilder();
    var separatorConsumed = false;
    for (int i = 0; i < segments.length; i++) {
      var first = i == 0;
      var last = i == segments.length - 1;
      if (DOUBLE_STAR.equals(segments[i])) {
        regex.append(doubleStarRegex(sep, first, last));
        separatorConsumed = !last;
      } else {
        if (!first && !separatorConsumed) {
          regex.append(sep);
        }
        regex.append(segmentRegex(segments[i], separator));
        separatorConsumed = false;
      }
    }
    return regex.toString();
  }

  /** {@code a/**}{@code /**}{@code /b} means the same as {@code a/**}{@code /b}; collapsing also avoids redundant backtracking. */
  private static String[] collapseDoubleStars(String[] segments) {
    var collapsed = new java.util.ArrayList<String>();
    for (var segment : segments) {
      if (!DOUBLE_STAR.equals(segment)) {
        // inside a segment '**' means the same as '*'
        collapsed.add(segment.replaceAll("\\*{2,}", "*"));
      } else if (collapsed.isEmpty() || !DOUBLE_STAR.equals(collapsed.get(collapsed.size() - 1))) {
        collapsed.add(segment);
      }
    }
    return collapsed.toArray(String[]::new);
  }

  private static String doubleStarRegex(String sep, boolean first, boolean last) {
    if (first && last) {
      return ".*";
    }
    if (first) {
      return "(?:.*" + sep + ")?";
    }
    if (last) {
      return "(?:" + sep + ".*)?";
    }
    return sep + "(?:.*" + sep + ")?";
  }

  private static String segmentRegex(String segment, char separator) {
    var notSeparator = "[^" + Pattern.quote(String.valueOf(separator)) + "]";
    var regex = new StringBuilder();
    var literal = new StringBuilder();
    for (char c : segment.toCharArray()) {
      if (c == '*' || c == '?') {
        flushLiteral(regex, literal);
        regex.append(notSeparator).append(c == '*' ? "*" : "");
      } else {
        literal.append(c);
      }
    }
    flushLiteral(regex, literal);
    return regex.toString();
  }

  private static void flushLiteral(StringBuilder regex, StringBuilder literal) {
    if (!literal.isEmpty()) {
      regex.append(Pattern.quote(literal.toString()));
      literal.setLength(0);
    }
  }
}
