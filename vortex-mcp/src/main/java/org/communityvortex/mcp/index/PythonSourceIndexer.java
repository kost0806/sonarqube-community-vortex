package org.communityvortex.mcp.index;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/** Python declarations and imports from syntax patterns; units are dotted module names. */
public final class PythonSourceIndexer implements SourceIndexer {

  // linear-time patterns (no overlapping repetitions), applied per line of at most ScriptSourceIndexer.MAX_LINE chars
  private static final Pattern IMPORT = Pattern.compile("^[ \\t]*import[ \\t]+(\\S.*)$");
  private static final Pattern FROM_IMPORT = Pattern.compile("^[ \\t]*from[ \\t]+(\\.*)(\\w[\\w.]*)?[ \\t]+import[ \\t]+(\\S.*)$");
  private static final Pattern DEF = Pattern.compile("^([ \\t]*)(?:async[ \\t]+)?def[ \\t]+([A-Za-z_]\\w*)[ \\t]*\\(");
  private static final Pattern CLASS = Pattern.compile("^([ \\t]*)class[ \\t]+([A-Za-z_]\\w*)");

  @Override
  public boolean supports(String relativePath) {
    return relativePath.endsWith(".py");
  }

  @Override
  public Optional<SourceFacts> index(String relativePath, String content) {
    var module = moduleOf(relativePath);
    var imports = new LinkedHashSet<String>();
    var symbols = new ArrayList<Symbol>();
    String currentClass = null;
    var lines = content.split("\\R", -1);
    for (int i = 0; i < lines.length; i++) {
      var line = lines[i];
      var lineNumber = i + 1;
      if (line.length() > ScriptSourceIndexer.MAX_LINE) {
        continue;
      }
      var importMatch = IMPORT.matcher(line);
      var fromMatch = FROM_IMPORT.matcher(line);
      var classMatch = CLASS.matcher(line);
      var defMatch = DEF.matcher(line);
      if (fromMatch.matches()) {
        var base = absolute(module, relativePath.endsWith("__init__.py"), fromMatch.group(1),
          fromMatch.group(2) == null ? "" : fromMatch.group(2));
        if (!base.isEmpty()) {
          imports.add(base);
        }
        for (var name : fromMatch.group(3).replaceAll("[()]", "").split(",")) {
          var imported = name.trim().split("\\s+")[0];
          if (!imported.isEmpty() && !imported.equals("*")) {
            imports.add(base.isEmpty() ? imported : base + "." + imported);
          }
        }
      } else if (importMatch.matches()) {
        for (var name : importMatch.group(1).split(",")) {
          imports.add(name.trim().split("\\s+")[0]);
        }
      } else if (classMatch.find()) {
        var topLevel = classMatch.group(1).isEmpty();
        var name = classMatch.group(2);
        if (topLevel) {
          currentClass = name;
        }
        symbols.add(new Symbol(name, module + "." + name, "class", relativePath, lineNumber, lineNumber, null));
      } else if (defMatch.find()) {
        var indented = !defMatch.group(1).isEmpty();
        var name = defMatch.group(2);
        if (!indented) {
          currentClass = null;
        }
        var owner = indented && currentClass != null ? module + "." + currentClass : module;
        symbols.add(new Symbol(name, owner + "." + name, indented && currentClass != null ? "method" : "function",
          relativePath, lineNumber, lineNumber, line.trim()));
      } else if (!line.isBlank() && !Character.isWhitespace(line.charAt(0)) && !line.startsWith("#") && !line.startsWith("@")) {
        currentClass = null;
      }
    }
    return Optional.of(new SourceFacts(relativePath, "py", module, List.copyOf(imports), symbols));
  }

  static String moduleOf(String relativePath) {
    var withoutExtension = relativePath.substring(0, relativePath.length() - ".py".length());
    if (withoutExtension.endsWith("/__init__") || withoutExtension.equals("__init__")) {
      withoutExtension = withoutExtension.substring(0, Math.max(0, withoutExtension.length() - "/__init__".length()));
    }
    var module = withoutExtension.replace('/', '.');
    return module.startsWith("src.") ? module.substring("src.".length()) : module;
  }

  private static String absolute(String module, boolean isPackageInit, String dots, String name) {
    if (dots.isEmpty()) {
      return name;
    }
    var parts = new ArrayList<>(List.of(module.split("\\.")));
    // one dot = current package; for a module that is the parent of the module itself
    var levels = dots.length() - (isPackageInit ? 1 : 0);
    for (int i = 0; i < levels && !parts.isEmpty(); i++) {
      parts.remove(parts.size() - 1);
    }
    var base = String.join(".", parts);
    if (name.isEmpty()) {
      return base;
    }
    return base.isEmpty() ? name : base + "." + name;
  }
}
