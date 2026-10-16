package org.communityvortex.mcp.index;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.communityvortex.mcp.workspace.Workspace;

/**
 * Incremental index of declarations and imports across the workspace. Files are re-parsed only when their
 * size or modification time changed since the last {@link #refresh()}.
 */
public final class CodeIndex {

  static final long MAX_FILE_BYTES = 2L * 1024 * 1024;

  public record ModuleSummary(String module, int files, Map<String, Integer> dependsOn) {
  }

  private record Entry(long modified, long size, SourceFacts facts) {
  }

  private final Workspace workspace;
  private final List<SourceIndexer> indexers;
  private final Map<String, Entry> entries = new HashMap<>();
  private long generation;

  public CodeIndex(Workspace workspace) {
    this(workspace, List.of(new JavaSourceIndexer(), new ScriptSourceIndexer(), new PythonSourceIndexer()));
  }

  CodeIndex(Workspace workspace, List<SourceIndexer> indexers) {
    this.workspace = workspace;
    this.indexers = List.copyOf(indexers);
  }

  /** Re-scans the workspace; returns {@code true} when anything changed. */
  public synchronized boolean refresh() {
    var seen = new HashSet<String>();
    var changed = false;
    for (var path : workspace.files()) {
      var relative = workspace.relative(path);
      var indexer = indexerFor(relative);
      if (indexer.isEmpty()) {
        continue;
      }
      seen.add(relative);
      changed |= update(path, relative, indexer.get());
    }
    changed |= entries.keySet().retainAll(seen);
    if (changed) {
      generation++;
    }
    return changed;
  }

  /** Increases whenever the indexed content changes; lets dependants invalidate caches. */
  public synchronized long generation() {
    return generation;
  }

  /** Facts for one file, re-indexing it first if it changed. */
  public synchronized Optional<SourceFacts> facts(Path file) {
    var relative = workspace.relative(file);
    var indexer = indexerFor(relative);
    if (indexer.isEmpty()) {
      return Optional.empty();
    }
    if (update(file, relative, indexer.get())) {
      generation++;
    }
    return Optional.ofNullable(entries.get(relative)).map(Entry::facts);
  }

  public synchronized List<SourceFacts> allFacts() {
    return entries.values().stream().map(Entry::facts).sorted(Comparator.comparing(SourceFacts::file)).toList();
  }

  public synchronized List<Symbol> searchSymbols(String query, String kind, int limit) {
    var needle = query.toLowerCase(Locale.ROOT);
    var matches = new ArrayList<Symbol>();
    for (var entry : entries.values()) {
      for (var symbol : entry.facts().symbols()) {
        var name = symbol.name().toLowerCase(Locale.ROOT);
        var qualified = symbol.qualifiedName().toLowerCase(Locale.ROOT);
        if ((kind == null || kind.equals(symbol.kind())) && (name.contains(needle) || qualified.equals(needle))) {
          matches.add(symbol);
        }
      }
    }
    matches.sort(Comparator.comparingInt((Symbol s) -> rank(s, needle))
      .thenComparing(Symbol::qualifiedName));
    return List.copyOf(matches.subList(0, Math.min(limit, matches.size())));
  }

  /** Declarations whose qualified name equals {@code symbol}, or whose simple name does when none does. */
  public synchronized List<Symbol> declarations(String symbol) {
    var exact = new ArrayList<Symbol>();
    var simple = new ArrayList<Symbol>();
    for (var entry : entries.values()) {
      for (var s : entry.facts().symbols()) {
        if (s.qualifiedName().equals(symbol)) {
          exact.add(s);
        } else if (s.name().equals(symbol)) {
          simple.add(s);
        }
      }
    }
    var result = exact.isEmpty() ? simple : exact;
    result.sort(Comparator.comparing(Symbol::file).thenComparingInt(Symbol::line));
    return List.copyOf(result);
  }

  /** Files whose imports refer to {@code file} (its declared types, module or path). */
  public synchronized List<String> importers(SourceFacts file) {
    var targets = new HashSet<String>();
    targets.add(file.unit());
    if ("java".equals(file.language())) {
      // Java imports name types, not packages
      targets.clear();
      file.symbols().stream().filter(s -> isType(s.kind())).forEach(s -> targets.add(s.qualifiedName()));
    }
    if (file.unit().endsWith("/index")) {
      targets.add(file.unit().substring(0, file.unit().length() - "/index".length()));
    }
    var importers = new ArrayList<String>();
    for (var entry : entries.values()) {
      var facts = entry.facts();
      if (!facts.file().equals(file.file()) && facts.imports().stream().anyMatch(targets::contains)) {
        importers.add(facts.file());
      }
    }
    importers.sort(null);
    return importers;
  }

  /** Units grouped by their first {@code depth} segments, with dependency counts between the groups. */
  public synchronized List<ModuleSummary> moduleMap(int depth, int limit) {
    var files = new TreeMap<String, Integer>();
    var edges = new HashMap<String, Map<String, Integer>>();
    var unitToModule = new HashMap<String, String>();
    for (var entry : entries.values()) {
      unitToModule.put(entry.facts().unit(), moduleOf(entry.facts().unit(), depth));
    }
    for (var entry : entries.values()) {
      var facts = entry.facts();
      var module = moduleOf(facts.unit(), depth);
      files.merge(module, 1, Integer::sum);
      for (var target : facts.imports()) {
        var targetModule = internalModule(target, unitToModule, depth);
        if (targetModule != null && !targetModule.equals(module)) {
          edges.computeIfAbsent(module, k -> new TreeMap<>()).merge(targetModule, 1, Integer::sum);
        }
      }
    }
    var summaries = new ArrayList<ModuleSummary>();
    files.forEach((module, count) -> summaries.add(new ModuleSummary(module, count, Map.copyOf(edges.getOrDefault(module, Map.of())))));
    summaries.sort(Comparator.comparingInt(ModuleSummary::files).reversed().thenComparing(ModuleSummary::module));
    return List.copyOf(summaries.subList(0, Math.min(limit, summaries.size())));
  }

  public synchronized Map<String, Integer> languageCounts() {
    var counts = new LinkedHashMap<String, Integer>();
    entries.values().forEach(e -> counts.merge(e.facts().language(), 1, Integer::sum));
    return counts;
  }

  Path root() {
    return workspace.root();
  }

  private static String internalModule(String target, Map<String, String> unitToModule, int depth) {
    if (unitToModule.containsKey(target)) {
      return unitToModule.get(target);
    }
    // Java type imports: strip the type name to reach the package
    var dot = target.lastIndexOf('.');
    if (dot > 0 && unitToModule.containsKey(target.substring(0, dot))) {
      return unitToModule.get(target.substring(0, dot));
    }
    return null;
  }

  private static String moduleOf(String unit, int depth) {
    var separator = unit.contains("/") ? "/" : ".";
    var parts = unit.split(java.util.regex.Pattern.quote(separator));
    var keep = Math.min(depth, unit.contains("/") ? Math.max(1, parts.length - 1) : parts.length);
    return String.join(separator, java.util.Arrays.copyOfRange(parts, 0, Math.max(1, keep)));
  }

  private static boolean isType(String kind) {
    return switch (kind) {
      case "class", "interface", "enum", "record", "annotation", "type" -> true;
      default -> false;
    };
  }

  private static int rank(Symbol symbol, String needle) {
    var name = symbol.name().toLowerCase(Locale.ROOT);
    if (name.equals(needle) || symbol.qualifiedName().toLowerCase(Locale.ROOT).equals(needle)) {
      return 0;
    }
    return name.startsWith(needle) ? 1 : 2;
  }

  private Optional<SourceIndexer> indexerFor(String relative) {
    return indexers.stream().filter(i -> i.supports(relative)).findFirst();
  }

  private boolean update(Path path, String relative, SourceIndexer indexer) {
    try {
      var attributes = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class);
      var modified = attributes.lastModifiedTime().toMillis();
      var size = attributes.size();
      var current = entries.get(relative);
      if (current != null && current.modified() == modified && current.size() == size) {
        return false;
      }
      if (size > MAX_FILE_BYTES) {
        return entries.remove(relative) != null;
      }
      var facts = indexer.index(relative, Files.readString(path, StandardCharsets.UTF_8));
      if (facts.isEmpty()) {
        return entries.remove(relative) != null;
      }
      entries.put(relative, new Entry(modified, size, facts.get()));
      return true;
    } catch (IOException e) {
      // unreadable or not UTF-8 (MalformedInputException): drop it from the index
      return entries.remove(relative) != null;
    }
  }
}
