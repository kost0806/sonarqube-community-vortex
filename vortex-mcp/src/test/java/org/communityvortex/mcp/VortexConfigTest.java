package org.communityvortex.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VortexConfigTest {

  @TempDir
  Path cwd;

  private Map<String, String> env() {
    var env = new HashMap<String, String>();
    env.put("SONAR_HOST_URL", "http://localhost:9000");
    env.put("SONAR_TOKEN", "squ_secret");
    env.put("SONAR_PROJECT_KEY", "acme");
    return env;
  }

  @Test
  void defaultsFromWorkingDirectoryAndHome() {
    var config = VortexConfig.fromEnvironment(env(), cwd, Path.of("/home/me"));

    assertThat(config.serverUrl()).hasToString("http://localhost:9000/");
    assertThat(config.workspace()).isEqualTo(cwd.toAbsolutePath().normalize());
    assertThat(config.cacheDir()).isEqualTo(Path.of("/home/me/.vortex"));
    assertThat(config.branch()).isNull();
    assertThat(config.nodePath()).isNull();
    assertThat(config.toString()).doesNotContain("squ_secret");
  }

  @Test
  void optionalSettings(@TempDir Path workspace) {
    var env = env();
    env.put("VORTEX_WORKSPACE", workspace.toString());
    env.put("SONAR_BRANCH", " feature/x ");
    env.put("VORTEX_CACHE_DIR", "/tmp/cache");
    env.put("VORTEX_NODE", "/usr/bin/node");

    var config = VortexConfig.fromEnvironment(env, cwd, cwd);

    assertThat(config.workspace()).isEqualTo(workspace);
    assertThat(config.branch()).isEqualTo("feature/x");
    assertThat(config.cacheDir()).isEqualTo(Path.of("/tmp/cache"));
    assertThat(config.nodePath()).isEqualTo(Path.of("/usr/bin/node"));
  }

  @Test
  void plainHttpOnlyForLocalServersUnlessExplicitlyAllowed() {
    var remote = env();
    remote.put("SONAR_HOST_URL", "http://sonar.example.com");
    assertThatThrownBy(() -> VortexConfig.fromEnvironment(remote, cwd, cwd)).hasMessageContaining("https");

    remote.put("VORTEX_ALLOW_INSECURE_HTTP", "true");
    assertThat(VortexConfig.fromEnvironment(remote, cwd, cwd).serverUrl()).hasToString("http://sonar.example.com/");

    var loopback = env();
    loopback.put("SONAR_HOST_URL", "http://127.0.0.1:9001");
    assertThat(VortexConfig.fromEnvironment(loopback, cwd, cwd).serverUrl().getPort()).isEqualTo(9001);
    loopback.put("SONAR_HOST_URL", "https://sonar.example.com");
    assertThat(VortexConfig.fromEnvironment(loopback, cwd, cwd).serverUrl().getScheme()).isEqualTo("https");
  }

  @Test
  void rejectsInvalidConfiguration() {
    var missingToken = env();
    missingToken.remove("SONAR_TOKEN");
    assertThatThrownBy(() -> VortexConfig.fromEnvironment(missingToken, cwd, cwd)).hasMessage("SONAR_TOKEN must be set");

    var badUrl = env();
    badUrl.put("SONAR_HOST_URL", "file:///etc/passwd");
    assertThatThrownBy(() -> VortexConfig.fromEnvironment(badUrl, cwd, cwd)).hasMessageContaining("http(s)");

    var both = env();
    both.put("SONAR_BRANCH", "a");
    both.put("SONAR_PULL_REQUEST", "1");
    assertThatThrownBy(() -> VortexConfig.fromEnvironment(both, cwd, cwd)).hasMessageContaining("only one");

    var credentials = env();
    credentials.put("SONAR_HOST_URL", "https://user:pw@sonar.example.com");
    assertThatThrownBy(() -> VortexConfig.fromEnvironment(credentials, cwd, cwd)).hasMessageContaining("credentials");

    var noWorkspace = env();
    noWorkspace.put("VORTEX_WORKSPACE", cwd.resolve("missing").toString());
    assertThatThrownBy(() -> VortexConfig.fromEnvironment(noWorkspace, cwd, cwd)).hasMessageContaining("not a directory");
  }
}
