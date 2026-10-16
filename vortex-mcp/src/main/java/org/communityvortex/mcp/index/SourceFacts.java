package org.communityvortex.mcp.index;

import java.util.List;

/**
 * What one source file declares and depends on.
 *
 * @param unit     the file's architectural unit: Java package, Python module, or the path for JS/TS
 * @param imports  dependency targets in the same notation as {@code unit} (FQNs, modules, workspace paths or package names)
 * @param symbols  declarations, outermost first
 */
public record SourceFacts(String file, String language, String unit, List<String> imports, List<Symbol> symbols) {

  public SourceFacts {
    imports = List.copyOf(imports);
    symbols = List.copyOf(symbols);
  }
}
