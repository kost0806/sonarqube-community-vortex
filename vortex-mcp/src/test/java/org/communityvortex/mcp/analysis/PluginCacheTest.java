package org.communityvortex.mcp.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.communityvortex.mcp.FakeSonar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sonarsource.sonarlint.core.commons.api.SonarLanguage;

class PluginCacheTest {

  @TempDir
  Path cache;

  @Test
  void selectsOnlyIdeCompatiblePluginsForRequestedLanguages() {
    var server = new FakeSonar().reply("api/plugins/installed", """
      {"plugins":[
        {"key":"python","filename":"python.jar","hash":"h1","sonarLintSupported":true},
        {"key":"java","filename":"java.jar","hash":"h2","sonarLintSupported":false},
        {"key":"javascript","filename":"js.jar","hash":"h3","sonarLintSupported":true}]}""");
    var cache = new PluginCache(server, this.cache);

    assertThat(cache.pluginKeysFor(Set.of(SonarLanguage.PYTHON, SonarLanguage.JAVA))).containsExactly("python");
    assertThat(cache.pluginKeysFor(Set.of(SonarLanguage.TS))).containsExactly("javascript");
    assertThat(cache.installed()).hasSize(3);
    assertThat(server.count("api/plugins/installed")).isEqualTo(1);
  }

  @Test
  void neverWritesOutsideTheCacheForHostileFileNames(@TempDir Path source) throws Exception {
    var jar = Files.writeString(source.resolve("x.jar"), "jar");
    var server = new FakeSonar()
      .reply("api/plugins/installed", """
        {"plugins":[{"key":"python","filename":"../../evil.jar","hash":"../..","sonarLintSupported":true}]}""")
      .serveFile("python", jar);

    var jars = new PluginCache(server, cache).jarsFor(Set.of("python"));

    assertThat(jars).containsExactly(cache.resolve("plugins/nohash/python.jar"));
    assertThat(jars.iterator().next()).isRegularFile();
  }

  private static String md5(byte[] bytes) throws Exception {
    return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("MD5").digest(bytes));
  }

  @Test
  void verifiesDownloadedJarsAgainstTheServerHash(@TempDir Path source) throws Exception {
    var jar = Files.writeString(source.resolve("python.jar"), "real analyzer");
    var hash = md5(Files.readAllBytes(jar));
    var server = new FakeSonar()
      .reply("api/plugins/installed", "{\"plugins\":[{\"key\":\"python\",\"filename\":\"python.jar\",\"hash\":\"" + hash + "\",\"sonarLintSupported\":true}]}")
      .serveFile("python", jar);
    var pluginCache = new PluginCache(server, cache);

    var cached = pluginCache.jarsFor(Set.of("python")).iterator().next();
    assertThat(cached).hasContent("real analyzer");

    // a tampered cache entry is replaced by a fresh, verified download
    Files.writeString(cached, "tampered");
    assertThat(new PluginCache(server, cache).jarsFor(Set.of("python")).iterator().next()).hasContent("real analyzer");
    assertThat(server.count("api/plugins/download")).isEqualTo(2);
  }

  @Test
  void refusesJarsThatDoNotMatchTheServerHash(@TempDir Path source) throws Exception {
    var jar = Files.writeString(source.resolve("python.jar"), "swapped in transit");
    var server = new FakeSonar()
      .reply("api/plugins/installed", "{\"plugins\":[{\"key\":\"python\",\"filename\":\"python.jar\",\"hash\":\""
        + md5("expected".getBytes()) + "\",\"sonarLintSupported\":true}]}")
      .serveFile("python", jar);

    assertThatThrownBy(() -> new PluginCache(server, cache).jarsFor(Set.of("python")))
      .isInstanceOf(IllegalStateException.class)
      .hasMessageContaining("integrity");
    try (var files = Files.walk(cache)) {
      assertThat(files.filter(Files::isRegularFile)).isEmpty();
    }
  }

  @Test
  void toleratesMissingOptionalFields() {
    var server = new FakeSonar().reply("api/plugins/installed", "{\"plugins\":[{\"key\":\"python\"}]}");

    var plugin = new PluginCache(server, cache).installed().get("python");

    assertThat(plugin.filename()).isEqualTo("python.jar");
    assertThat(plugin.sonarLintSupported()).isFalse();
  }
}
