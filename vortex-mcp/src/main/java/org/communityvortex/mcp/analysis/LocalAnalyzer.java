package org.communityvortex.mcp.analysis;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import org.communityvortex.mcp.workspace.Workspace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.rule.ActiveRule;
import org.sonarsource.sonarlint.core.analysis.AnalysisScheduler;
import org.sonarsource.sonarlint.core.analysis.api.AnalysisConfiguration;
import org.sonarsource.sonarlint.core.analysis.api.AnalysisSchedulerConfiguration;
import org.sonarsource.sonarlint.core.analysis.api.ClientInputFile;
import org.sonarsource.sonarlint.core.analysis.api.ClientModuleFileSystem;
import org.sonarsource.sonarlint.core.analysis.api.Issue;
import org.sonarsource.sonarlint.core.analysis.api.TriggerType;
import org.sonarsource.sonarlint.core.analysis.command.AnalyzeCommand;
import org.sonarsource.sonarlint.core.commons.api.SonarLanguage;
import org.sonarsource.sonarlint.core.commons.log.LogOutput;
import org.sonarsource.sonarlint.core.commons.progress.SonarLintCancelMonitor;
import org.sonarsource.sonarlint.core.commons.progress.TaskManager;
import org.sonarsource.sonarlint.core.plugin.commons.LoadedPlugins;
import org.sonarsource.sonarlint.core.plugin.commons.PluginsLoader;

/**
 * Runs the SonarLint analysis engine (LGPL, sonarlint-core) in-process with the server's analyzers and
 * active rules. The engine is (re)loaded lazily for the set of languages seen so far.
 */
public final class LocalAnalyzer implements Analyzer, AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(LocalAnalyzer.class);
  private static final String MODULE_KEY = "vortex-workspace";
  private static final long TIMEOUT_MINUTES = 5;

  private final Workspace defaultWorkspace;
  private final PluginCache defaultPlugins;
  private final RuleSource defaultRules;
  private final Path workDir;
  private final Optional<NodeLocator.Node> node;
  private final TaskManager taskManager = new TaskManager();

  /** Workspace of the analysis in progress; read by the module file system of the (shared) engine. */
  private Workspace current;
  private AnalysisScheduler scheduler;
  private LoadedPlugins loadedPlugins;
  private Set<SonarLanguage> loadedLanguages = EnumSet.noneOf(SonarLanguage.class);
  /** Languages the server has no IDE-compatible analyzer for; remembered so they never trigger a reload. */
  private final Set<SonarLanguage> unavailableLanguages = EnumSet.noneOf(SonarLanguage.class);
  private int engineLoads;

  /** An analyzer bound to one workspace and project (the MCP server). */
  public LocalAnalyzer(Workspace workspace, PluginCache plugins, RuleSource rules, Path cacheDir, Path configuredNode) {
    this.defaultWorkspace = workspace;
    this.defaultPlugins = plugins;
    this.defaultRules = rules;
    this.workDir = cacheDir.resolve("work");
    this.node = NodeLocator.locate(configuredNode, System.getenv("PATH"));
  }

  /** A shared engine for many workspaces and projects (the analysis service); use {@link #analyze(Workspace, List, RuleSource, PluginCache)}. */
  public LocalAnalyzer(Path cacheDir, Path configuredNode) {
    this(null, null, null, cacheDir, configuredNode);
  }

  @Override
  public Report analyze(List<Path> files) {
    if (defaultWorkspace == null) {
      throw new IllegalStateException("This analyzer has no default workspace");
    }
    return analyze(defaultWorkspace, files, defaultRules, defaultPlugins);
  }

  /** Analyses {@code files} of {@code workspace} with the given project rules and analyzers. Calls are serialised. */
  public synchronized Report analyze(Workspace workspace, List<Path> files, RuleSource rules, PluginCache plugins) {
    current = workspace;
    var warnings = new ArrayList<String>();
    var unsupported = new ArrayList<String>();
    var inputs = new ArrayList<ClientInputFile>();
    var languages = EnumSet.noneOf(SonarLanguage.class);
    for (var file : files) {
      var relative = workspace.relative(file);
      var language = WorkspaceInputFile.detectLanguage(file.getFileName().toString());
      if (language == null) {
        unsupported.add(relative);
      } else {
        languages.add(language);
        inputs.add(new WorkspaceInputFile(file, relative, language));
      }
    }
    var usableLanguages = ensureEngine(languages, plugins, warnings);
    var analysable = new ArrayList<ClientInputFile>();
    for (var input : inputs) {
      if (usableLanguages.contains(input.language())) {
        analysable.add(input);
      } else {
        unsupported.add(input.relativePath());
      }
    }
    if (analysable.isEmpty()) {
      return new Report(List.of(), unsupported, List.of(), warnings);
    }
    var activeRules = activeRulesFor(usableLanguages, rules, warnings);
    return run(workspace, analysable, activeRules, unsupported, warnings);
  }

  private Report run(Workspace workspace, List<ClientInputFile> inputs, Map<String, ServerActiveRule> activeRules,
    List<String> unsupported, List<String> warnings) {
    var config = AnalysisConfiguration.builder()
      .addInputFiles(inputs)
      .addActiveRules(new ArrayList<ActiveRule>(activeRules.values()))
      .setBaseDir(workspace.root())
      .build();
    var issues = Collections.synchronizedList(new ArrayList<Issue>());
    var command = new AnalyzeCommand(MODULE_KEY, UUID.randomUUID(), TriggerType.FORCED, () -> config, issues::add,
      null, new SonarLintCancelMonitor(), taskManager, started -> { }, () -> true, Set.of(), Map.of());
    scheduler.post(command);
    List<String> failed = new ArrayList<>();
    try {
      var results = command.getFutureResult().get(TIMEOUT_MINUTES, TimeUnit.MINUTES);
      results.failedAnalysisFiles().forEach(f -> failed.add(f.relativePath()));
    } catch (TimeoutException e) {
      command.cancel();
      throw new IllegalStateException("Analysis did not finish within " + TIMEOUT_MINUTES + " minutes", e);
    } catch (ExecutionException e) {
      throw new IllegalStateException("Analysis failed: " + e.getCause().getMessage(), e.getCause());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Analysis interrupted", e);
    }
    var findings = new ArrayList<Finding>();
    synchronized (issues) {
      for (var issue : issues) {
        findings.add(toFinding(issue, activeRules));
      }
    }
    findings.sort((a, b) -> a.file().equals(b.file()) ? Integer.compare(a.line(), b.line()) : a.file().compareTo(b.file()));
    return new Report(findings, unsupported, failed, warnings);
  }

  private static Finding toFinding(Issue issue, Map<String, ServerActiveRule> activeRules) {
    var ruleKey = issue.getRuleKey().toString();
    var rule = activeRules.get(ruleKey);
    var range = issue.getTextRange();
    var file = issue.getInputFile() == null ? null : issue.getInputFile().relativePath();
    return new Finding(file,
      range == null ? 0 : range.getStartLine(),
      range == null ? 0 : range.getEndLine(),
      range == null ? 0 : range.getStartLineOffset(),
      range == null ? 0 : range.getEndLineOffset(),
      ruleKey,
      rule == null ? null : rule.name(),
      rule == null ? null : rule.severity(),
      rule == null ? null : rule.type(),
      issue.getMessage(),
      !issue.quickFixes().isEmpty());
  }

  private static Map<String, ServerActiveRule> activeRulesFor(Set<SonarLanguage> languages, RuleSource rules, List<String> warnings) {
    var byKey = new LinkedHashMap<String, ServerActiveRule>();
    for (var language : languages) {
      var languageRules = rules.forLanguage(language.getSonarLanguageKey());
      if (languageRules.isEmpty()) {
        warnings.add("No active rules for language '" + language.getSonarLanguageKey() + "' in the project's quality profiles");
      }
      languageRules.forEach(rule -> byKey.put(rule.ruleKey().toString(), rule));
    }
    return byKey;
  }

  /** Loads analyzers for {@code requested}; returns the languages that can actually be analysed. */
  private Set<SonarLanguage> ensureEngine(Set<SonarLanguage> requested, PluginCache plugins, List<String> warnings) {
    for (var language : requested) {
      if (unavailableLanguages.contains(language)) {
        warnings.add(noAnalyzer(language));
      }
    }
    var missing = EnumSet.noneOf(SonarLanguage.class);
    missing.addAll(requested);
    missing.removeAll(unavailableLanguages);
    missing.removeAll(loadedLanguages);
    if (missing.isEmpty()) {
      return intersect(requested, loadedLanguages);
    }
    var wanted = EnumSet.noneOf(SonarLanguage.class);
    wanted.addAll(loadedLanguages);
    wanted.addAll(missing);
    var pluginKeys = plugins.pluginKeysFor(wanted);
    var supported = EnumSet.noneOf(SonarLanguage.class);
    for (var language : wanted) {
      if (pluginKeys.contains(language.getPlugin().getKey())) {
        supported.add(language);
      } else {
        unavailableLanguages.add(language);
        warnings.add(noAnalyzer(language));
      }
    }
    if (supported.equals(loadedLanguages) && scheduler != null) {
      return intersect(requested, loadedLanguages);
    }
    if (supported.isEmpty()) {
      return supported;
    }
    if (requested.stream().anyMatch(l -> l == SonarLanguage.JS || l == SonarLanguage.TS) && node.isEmpty()) {
      warnings.add("Node.js was not found (set VORTEX_NODE); JavaScript/TypeScript analysis may be skipped");
    }
    var skipped = loadEngine(plugins.jarsFor(pluginKeys), supported);
    for (var language : supported) {
      var reason = skipped.get(language.getPlugin().getKey());
      if (reason != null) {
        loadedLanguages.remove(language);
        unavailableLanguages.add(language);
        warnings.add("The " + language.getName() + " analyzer from the server could not be loaded: " + reason);
      }
    }
    return intersect(requested, loadedLanguages);
  }

  /** @return analyzers the engine refused to load (plugin key to reason) */
  private Map<String, String> loadEngine(Set<Path> jars, Set<SonarLanguage> languages) {
    stopEngine();
    try {
      Files.createDirectories(workDir);
    } catch (IOException e) {
      throw new IllegalStateException("Cannot create analysis work directory " + workDir, e);
    }
    var result = new PluginsLoader().load(
      new PluginsLoader.Configuration(jars, languages, false, node.map(NodeLocator.Node::version)), Set.of());
    loadedPlugins = result.getLoadedPlugins();
    var schedulerConfig = AnalysisSchedulerConfiguration.builder()
      .setWorkDir(workDir)
      .setClientPid(ProcessHandle.current().pid())
      .setExtraProperties(Map.of())
      .setFileSystemProvider(moduleKey -> new WorkspaceFileSystem(() -> current))
      .setNodeJs(node.map(NodeLocator.Node::path).orElse(null))
      .build();
    scheduler = new AnalysisScheduler(schedulerConfig, loadedPlugins, new Slf4jLogOutput());
    loadedLanguages = EnumSet.copyOf(languages);
    engineLoads++;
    var skipped = new LinkedHashMap<String, String>();
    result.getPluginCheckResultByKeys().forEach((key, check) -> {
      if (check.isSkipped()) {
        var reason = check.getSkipReason().map(Object::toString).orElse("unknown reason");
        LOG.warn("Analyzer '{}' skipped: {}", key, reason);
        skipped.put(key, reason);
      }
    });
    LOG.info("Analysis engine loaded for {}", languages);
    return skipped;
  }

  private static String noAnalyzer(SonarLanguage language) {
    return "The server has no IDE-compatible analyzer for " + language.getName();
  }

  /** Number of times analyzers were (re)loaded; visible for tests. */
  synchronized int engineLoads() {
    return engineLoads;
  }

  private static Set<SonarLanguage> intersect(Set<SonarLanguage> a, Set<SonarLanguage> b) {
    var result = EnumSet.noneOf(SonarLanguage.class);
    for (var language : a) {
      if (b.contains(language)) {
        result.add(language);
      }
    }
    return result;
  }

  private void stopEngine() {
    if (scheduler != null) {
      scheduler.stop();
      scheduler = null;
    }
    if (loadedPlugins != null) {
      try {
        loadedPlugins.close();
      } catch (IOException e) {
        LOG.debug("Cannot close analyzers", e);
      }
      loadedPlugins = null;
    }
    loadedLanguages = EnumSet.noneOf(SonarLanguage.class);
  }

  @Override
  public synchronized void close() {
    stopEngine();
  }

  /** Lets cross-file sensors (e.g. tsconfig lookup) see the workspace. */
  private static final class WorkspaceFileSystem implements ClientModuleFileSystem {
    private final java.util.function.Supplier<Workspace> workspaces;
    private final Map<Path, ClientInputFile> cache = new HashMap<>();
    private Workspace cachedFor;

    WorkspaceFileSystem(java.util.function.Supplier<Workspace> workspaces) {
      this.workspaces = workspaces;
    }

    @Override
    public Stream<ClientInputFile> files(String suffix, InputFile.Type type) {
      return files().filter(f -> f.relativePath().endsWith(suffix) && f.isTest() == (type == InputFile.Type.TEST));
    }

    @Override
    public synchronized Stream<ClientInputFile> files() {
      var workspace = workspaces.get();
      if (workspace != cachedFor) {
        cache.clear();
        cachedFor = workspace;
      }
      var result = new ArrayList<ClientInputFile>();
      for (var path : workspace.files()) {
        result.add(cache.computeIfAbsent(path, p -> new WorkspaceInputFile(p, workspace.relative(p),
          WorkspaceInputFile.detectLanguage(p.getFileName().toString()))));
      }
      return result.stream();
    }
  }

  private static final class Slf4jLogOutput implements LogOutput {
    private static final Logger ENGINE = LoggerFactory.getLogger("sonarlint");

    @Override
    public void log(String message, Level level) {
      log(message, level, null);
    }

    @Override
    public void log(String message, Level level, String stacktrace) {
      switch (level) {
        case ERROR -> ENGINE.error("{}{}", message, stacktrace == null ? "" : "\n" + stacktrace);
        case WARN -> ENGINE.warn(message);
        case INFO -> ENGINE.info(message);
        default -> ENGINE.debug(message);
      }
    }
  }
}
