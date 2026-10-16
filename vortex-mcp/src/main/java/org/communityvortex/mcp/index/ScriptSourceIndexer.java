package org.communityvortex.mcp.index;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * JavaScript/TypeScript declarations and module imports from syntax patterns.
 * Relative imports are resolved to workspace paths without extension ({@code src/db/client});
 * bare specifiers stay package names ({@code react}).
 */
public final class ScriptSourceIndexer implements SourceIndexer {

  private static final Pattern EXTENSIONS = Pattern.compile("\\.(m|c)?(j|t)sx?$");
  // Patterns run over untrusted workspace files: no two adjacent parts may consume the same characters,
  // keeping matching linear (no catastrophic backtracking), and lines longer than MAX_LINE are skipped.
  static final int MAX_LINE = 4096;
  private static final List<Pattern> IMPORTS = List.of(
    // import x from 'm' / export * from 'm' (the 'from' clause ends multi-line imports too)
    Pattern.compile("\\bfrom[ \\t]*['\"]([^'\"\\r\\n]+)['\"]"),
    // import 'm' (side effects)
    Pattern.compile("\\bimport[ \\t]*['\"]([^'\"\\r\\n]+)['\"]"),
    // require('m') / import('m')
    Pattern.compile("\\b(?:require|import)[ \\t]*\\([ \\t]*['\"]([^'\"\\r\\n]+)['\"]"));
  private static final String NAME = "([A-Za-z_$][\\w$]*)";
  private static final List<Declaration> DECLARATIONS = List.of(
    new Declaration("class", Pattern.compile("^[ \\t]*(?:export[ \\t]+)?(?:default[ \\t]+)?(?:abstract[ \\t]+)?class[ \\t]+" + NAME)),
    new Declaration("function", Pattern.compile("^[ \\t]*(?:export[ \\t]+)?(?:default[ \\t]+)?(?:async[ \\t]+)?function[ \\t]*\\*?[ \\t]*" + NAME)),
    new Declaration("interface", Pattern.compile("^[ \\t]*(?:export[ \\t]+)?interface[ \\t]+" + NAME)),
    new Declaration("type", Pattern.compile("^[ \\t]*(?:export[ \\t]+)?type[ \\t]+" + NAME + "[ \\t]*(?:<[^=\\r\\n]*>)?[ \\t]*=")),
    new Declaration("enum", Pattern.compile("^[ \\t]*(?:export[ \\t]+)?(?:const[ \\t]+)?enum[ \\t]+" + NAME)),
    new Declaration("variable", Pattern.compile("^(?:export[ \\t]+)?(?:const|let|var)[ \\t]+" + NAME)));

  private record Declaration(String kind, Pattern pattern) {
  }

  @Override
  public boolean supports(String relativePath) {
    return EXTENSIONS.matcher(relativePath).find() && !relativePath.endsWith(".d.ts");
  }

  @Override
  public Optional<SourceFacts> index(String relativePath, String content) {
    var language = relativePath.matches(".*\\.(m|c)?tsx?$") ? "ts" : "js";
    var imports = new LinkedHashSet<String>();
    var symbols = new ArrayList<Symbol>();
    var lines = content.split("\\R", -1);
    for (int i = 0; i < lines.length; i++) {
      var line = lines[i];
      if (line.length() > MAX_LINE) {
        continue;
      }
      for (var pattern : IMPORTS) {
        var matcher = pattern.matcher(line);
        while (matcher.find()) {
          imports.add(resolve(relativePath, matcher.group(1)));
        }
      }
      for (var declaration : DECLARATIONS) {
        var matcher = declaration.pattern().matcher(line);
        if (matcher.find()) {
          var name = matcher.group(1);
          symbols.add(new Symbol(name, unitOf(relativePath) + "#" + name, declaration.kind(), relativePath, i + 1, i + 1, null));
        }
      }
    }
    return Optional.of(new SourceFacts(relativePath, language, unitOf(relativePath), List.copyOf(imports), symbols));
  }

  static String unitOf(String relativePath) {
    return EXTENSIONS.matcher(relativePath).replaceFirst("");
  }

  static String resolve(String importingFile, String specifier) {
    if (!specifier.startsWith(".")) {
      return specifier;
    }
    var parent = Path.of(importingFile).getParent();
    var resolved = (parent == null ? Path.of(specifier) : parent.resolve(specifier)).normalize().toString().replace('\\', '/');
    return EXTENSIONS.matcher(resolved).replaceFirst("");
  }

}
