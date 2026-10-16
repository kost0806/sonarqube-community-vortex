package org.communityvortex.mcp.analysis;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.communityvortex.mcp.sonar.Params;
import org.communityvortex.mcp.sonar.SonarServer;
import org.sonarsource.sonarlint.core.commons.api.SonarLanguage;
import org.sonarsource.sonarlint.core.commons.plugins.SonarArtifact;

/**
 * Downloads the analyzers installed on the server (the same jars SonarQube for IDE uses in connected mode)
 * into {@code <cache>/plugins/<hash>/}, so analysis runs with exactly the server's analyzer versions.
 */
public final class PluginCache {

  private static final Pattern SAFE_FILENAME = Pattern.compile("[A-Za-z0-9._-]+\\.jar");
  private static final Pattern SAFE_HASH = Pattern.compile("[A-Za-z0-9]+");
  /** {@code api/plugins/installed} reports each jar's MD5 as {@code hash}. */
  private static final Pattern MD5 = Pattern.compile("[0-9a-fA-F]{32}");

  private final Set<Path> downloadedThisSession = new HashSet<>();

  public record InstalledPlugin(String key, String filename, String hash, boolean sonarLintSupported) {
  }

  private final SonarServer server;
  private final Path pluginsDir;
  private Map<String, InstalledPlugin> installed;

  public PluginCache(SonarServer server, Path cacheDir) {
    this.server = server;
    this.pluginsDir = cacheDir.resolve("plugins");
  }

  public synchronized Map<String, InstalledPlugin> installed() {
    if (installed == null) {
      var plugins = new HashMap<String, InstalledPlugin>();
      for (var element : server.get("api/plugins/installed", Params.of()).getAsJsonArray("plugins")) {
        var json = element.getAsJsonObject();
        var key = json.get("key").getAsString();
        plugins.put(key, new InstalledPlugin(key,
          json.has("filename") ? json.get("filename").getAsString() : key + ".jar",
          json.has("hash") ? json.get("hash").getAsString() : "nohash",
          json.has("sonarLintSupported") && json.get("sonarLintSupported").getAsBoolean()));
      }
      installed = Map.copyOf(plugins);
    }
    return installed;
  }

  /** Plugin keys needed to analyse {@code languages} that the server provides with IDE (SonarLint) support. */
  public Set<String> pluginKeysFor(Set<SonarLanguage> languages) {
    var available = installed();
    var keys = new LinkedHashSet<String>();
    for (var language : languages) {
      var plugin = language.getPlugin();
      if (!isUsable(available, plugin.getKey())) {
        continue;
      }
      keys.add(plugin.getKey());
      for (var dependency : plugin.getDependencies()) {
        dependency.anyRequiredArtifacts().stream()
          .map(SonarArtifact::getKey)
          .filter(k -> isUsable(available, k))
          .findFirst()
          .ifPresent(keys::add);
      }
    }
    return keys;
  }

  public synchronized Set<Path> jarsFor(Set<String> pluginKeys) {
    var jars = new LinkedHashSet<Path>();
    for (var key : pluginKeys) {
      jars.add(ensureDownloaded(installed().get(key)));
    }
    return jars;
  }

  private Path ensureDownloaded(InstalledPlugin plugin) {
    var filename = SAFE_FILENAME.matcher(plugin.filename()).matches() ? plugin.filename() : plugin.key() + ".jar";
    var hash = SAFE_HASH.matcher(plugin.hash()).matches() ? plugin.hash() : "nohash";
    var target = pluginsDir.resolve(hash).resolve(filename);
    // jars are loaded into this JVM: verify them against the server's MD5 (also when cached),
    // and never trust an unverifiable cached jar from a previous session
    var verifiable = MD5.matcher(plugin.hash()).matches();
    var trusted = Files.isRegularFile(target) && (verifiable ? md5Matches(target, plugin.hash()) : downloadedThisSession.contains(target));
    if (!trusted) {
      server.download("api/plugins/download", Params.of("plugin", plugin.key()), target);
      if (verifiable && !md5Matches(target, plugin.hash())) {
        deleteQuietly(target);
        throw new IllegalStateException("Analyzer '" + plugin.key() + "' failed the integrity check (MD5 differs from the server's); not loaded");
      }
      downloadedThisSession.add(target);
    }
    return target;
  }

  /** Verified (path, size, mtime, md5) combinations, so large jars are hashed once per process. */
  private static final Set<String> VERIFIED = java.util.concurrent.ConcurrentHashMap.newKeySet();

  private static boolean md5Matches(Path file, String expected) {
    String identity;
    try {
      identity = file.toAbsolutePath() + "|" + Files.size(file) + "|" + Files.getLastModifiedTime(file).toMillis() + "|" + expected.toLowerCase(java.util.Locale.ROOT);
    } catch (IOException e) {
      return false;
    }
    if (VERIFIED.contains(identity)) {
      return true;
    }
    var matches = computeMd5Matches(file, expected);
    if (matches) {
      VERIFIED.add(identity);
    }
    return matches;
  }

  private static boolean computeMd5Matches(Path file, String expected) {
    try (var in = Files.newInputStream(file)) {
      var digest = MessageDigest.getInstance("MD5");
      var buffer = new byte[64 * 1024];
      for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
        digest.update(buffer, 0, read);
      }
      return HexFormat.of().formatHex(digest.digest()).equalsIgnoreCase(expected);
    } catch (IOException e) {
      return false;
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("MD5 is not available", e);
    }
  }

  private static void deleteQuietly(Path file) {
    try {
      Files.deleteIfExists(file);
    } catch (IOException e) {
      // a corrupt file left behind is re-verified before any use
    }
  }

  private static boolean isUsable(Map<String, InstalledPlugin> available, String key) {
    var plugin = available.get(key);
    return plugin != null && plugin.sonarLintSupported();
  }
}
