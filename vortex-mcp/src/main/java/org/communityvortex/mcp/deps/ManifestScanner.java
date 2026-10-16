package org.communityvortex.mcp.deps;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.communityvortex.policy.Dependency;

/**
 * Extracts declared dependencies from build manifests: {@code pom.xml}, {@code build.gradle(.kts)},
 * {@code package.json} and {@code requirements*.txt}.
 */
public final class ManifestScanner {

  // Manifests come from untrusted workspaces: patterns avoid overlapping repetitions (linear time),
  // line-based ones skip lines over MAX_LINE characters, and inputs over MAX_BYTES are not scanned.
  public static final int MAX_BYTES = 1024 * 1024;
  private static final int MAX_LINE = 4096;
  private static final Pattern GRADLE_DEPENDENCY = Pattern.compile(
    "\\b(?:implementation|api|compileOnly|runtimeOnly|testImplementation|testRuntimeOnly|testCompileOnly|annotationProcessor|kapt|ksp|compile|testCompile)"
      + "[ \\t]*(?:\\([ \\t]*)?[\"']([^:\"'\\s]+):([^:\"'\\s]+)(?::([^\"'\\s]+))?[\"']");
  private static final Pattern REQUIREMENT = Pattern.compile("^([A-Za-z0-9][A-Za-z0-9._-]*)(?:\\[[^]]*])?(.*)$");
  private static final List<String> NPM_SECTIONS = List.of("dependencies", "devDependencies", "peerDependencies", "optionalDependencies");

  private ManifestScanner() {
  }

  public static boolean isManifest(String relativePath) {
    var name = fileName(relativePath);
    return name.equals("pom.xml") || name.equals("build.gradle") || name.equals("build.gradle.kts")
      || name.equals("package.json") || (name.startsWith("requirements") && name.endsWith(".txt"));
  }

  public static List<Dependency> scan(String relativePath, String content) {
    if (content.length() > MAX_BYTES) {
      return List.of();
    }
    var name = fileName(relativePath);
    if (name.equals("pom.xml")) {
      return pom(relativePath, content);
    }
    if (name.startsWith("build.gradle")) {
      return gradle(relativePath, content);
    }
    if (name.equals("package.json")) {
      return packageJson(relativePath, content);
    }
    if (name.startsWith("requirements") && name.endsWith(".txt")) {
      return requirements(relativePath, content);
    }
    return List.of();
  }

  private static List<Dependency> pom(String file, String content) {
    var dependencies = new ArrayList<Dependency>();
    var xml = withoutXmlComments(content);
    var lines = new Lines(content);
    var open = "<dependency>";
    var close = "</dependency>";
    // single forward scan: linear even for malformed input
    for (int start = xml.indexOf(open); start >= 0; ) {
      var end = xml.indexOf(close, start + open.length());
      if (end < 0) {
        break;
      }
      var body = xml.substring(start + open.length(), end);
      var groupId = tag(body, "groupId");
      var artifactId = tag(body, "artifactId");
      if (groupId != null && artifactId != null) {
        dependencies.add(new Dependency("maven", groupId + ":" + artifactId, tag(body, "version"), file, lines.lineAt(start)));
      }
      start = xml.indexOf(open, end + close.length());
    }
    return dependencies;
  }

  private static List<Dependency> gradle(String file, String content) {
    var dependencies = new ArrayList<Dependency>();
    var lines = withoutCodeComments(content).split("\\R", -1);
    for (int i = 0; i < lines.length; i++) {
      if (lines[i].length() > MAX_LINE) {
        continue;
      }
      var matcher = GRADLE_DEPENDENCY.matcher(lines[i]);
      while (matcher.find()) {
        dependencies.add(new Dependency("maven", matcher.group(1) + ":" + matcher.group(2), matcher.group(3), file, i + 1));
      }
    }
    return dependencies;
  }

  /** Blanks {@code //} and {@code /* *}{@code /} comments outside string literals, keeping line breaks. */
  static String withoutCodeComments(String code) {
    var out = new StringBuilder(code.length());
    char quote = 0;
    var block = false;
    var line = false;
    for (int i = 0; i < code.length(); i++) {
      var c = code.charAt(i);
      var next = i + 1 < code.length() ? code.charAt(i + 1) : '\0';
      if (block) {
        if (c == '*' && next == '/') {
          block = false;
          out.append("  ");
          i++;
        } else {
          out.append(c == '\n' ? '\n' : ' ');
        }
      } else if (line) {
        line = c != '\n';
        out.append(c == '\n' ? '\n' : ' ');
      } else if (quote != 0) {
        quote = c == quote ? 0 : quote;
        out.append(c);
      } else if (c == '/' && next == '*') {
        block = true;
        out.append("  ");
        i++;
      } else if (c == '/' && next == '/') {
        line = true;
        out.append("  ");
        i++;
      } else {
        quote = c == '"' || c == '\'' ? c : 0;
        out.append(c);
      }
    }
    return out.toString();
  }

  private static List<Dependency> packageJson(String file, String content) {
    JsonObject json;
    try {
      json = JsonParser.parseString(content).getAsJsonObject();
    } catch (RuntimeException e) {
      return List.of();
    }
    var dependencies = new ArrayList<Dependency>();
    var lines = new Lines(content);
    for (var section : NPM_SECTIONS) {
      if (!json.has(section) || !json.get(section).isJsonObject()) {
        continue;
      }
      // entries keep their file order, so the search cursor only moves forward (linear overall)
      var cursor = Math.max(0, content.indexOf("\"" + section + "\""));
      for (var entry : json.getAsJsonObject(section).entrySet()) {
        var version = entry.getValue().isJsonPrimitive() ? entry.getValue().getAsString() : null;
        var at = content.indexOf("\"" + entry.getKey() + "\"", cursor);
        cursor = at < 0 ? cursor : at + 1;
        dependencies.add(new Dependency("npm", entry.getKey(), version, file, at < 0 ? 0 : lines.lineAt(at)));
      }
    }
    return dependencies;
  }

  private static List<Dependency> requirements(String file, String content) {
    var dependencies = new ArrayList<Dependency>();
    var lines = content.split("\\R", -1);
    for (int i = 0; i < lines.length; i++) {
      var line = stripHashComment(lines[i]).trim();
      if (line.isEmpty() || line.length() > MAX_LINE || line.startsWith("-") || line.contains("://")) {
        continue;
      }
      var matcher = REQUIREMENT.matcher(line);
      if (matcher.matches()) {
        var marker = matcher.group(2).indexOf(';');
        var version = (marker < 0 ? matcher.group(2) : matcher.group(2).substring(0, marker)).trim();
        dependencies.add(new Dependency("pypi", matcher.group(1), version.isEmpty() ? null : version, file, i + 1));
      }
    }
    return dependencies;
  }

  private static String tag(String xml, String name) {
    var open = "<" + name + ">";
    var start = xml.indexOf(open);
    if (start < 0) {
      return null;
    }
    var end = xml.indexOf("</" + name + ">", start);
    if (end < 0) {
      return null;
    }
    var value = xml.substring(start + open.length(), end).trim();
    return value.isEmpty() || value.indexOf('<') >= 0 ? null : value;
  }

  private static String stripHashComment(String line) {
    var hash = line.indexOf('#');
    return hash < 0 ? line : line.substring(0, hash);
  }

  /** Blanks out comments while keeping offsets (and therefore line numbers) intact. */
  private static String withoutXmlComments(String xml) {
    var builder = new StringBuilder(xml);
    for (int start = xml.indexOf("<!--"); start >= 0; ) {
      var end = xml.indexOf("-->", start + 4);
      var stop = end < 0 ? xml.length() : end + 3;
      for (int i = start; i < stop; i++) {
        if (builder.charAt(i) != '\n') {
          builder.setCharAt(i, ' ');
        }
      }
      start = end < 0 ? -1 : xml.indexOf("<!--", stop);
    }
    return builder.toString();
  }

  /** Maps offsets to 1-based line numbers in O(log n) after one linear pass. */
  private static final class Lines {
    private final int[] starts;

    Lines(String content) {
      var offsets = new ArrayList<Integer>();
      offsets.add(0);
      for (int i = 0; i < content.length(); i++) {
        if (content.charAt(i) == '\n') {
          offsets.add(i + 1);
        }
      }
      starts = offsets.stream().mapToInt(Integer::intValue).toArray();
    }

    int lineAt(int offset) {
      var index = java.util.Arrays.binarySearch(starts, offset);
      return index >= 0 ? index + 1 : -index - 1;
    }
  }

  private static String fileName(String relativePath) {
    var slash = relativePath.replace('\\', '/').lastIndexOf('/');
    return slash < 0 ? relativePath : relativePath.substring(slash + 1);
  }
}
