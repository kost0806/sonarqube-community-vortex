package org.communityvortex.mcp.deps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import org.communityvortex.policy.Dependency;
import org.junit.jupiter.api.Test;

class ManifestScannerTest {

  @Test
  void recognisesManifests() {
    assertThat(ManifestScanner.isManifest("pom.xml")).isTrue();
    assertThat(ManifestScanner.isManifest("api/build.gradle.kts")).isTrue();
    assertThat(ManifestScanner.isManifest("web\\package.json")).isTrue();
    assertThat(ManifestScanner.isManifest("requirements-dev.txt")).isTrue();
    assertThat(ManifestScanner.isManifest("src/Main.java")).isFalse();
    assertThat(ManifestScanner.scan("src/Main.java", "x")).isEmpty();
  }

  @Test
  void mavenDependenciesWithLinesIgnoringComments() {
    var deps = ManifestScanner.scan("pom.xml", """
      <project>
        <!-- <dependency><groupId>old</groupId><artifactId>gone</artifactId></dependency> -->
        <dependencies>
          <dependency>
            <groupId>log4j</groupId>
            <artifactId>log4j</artifactId>
            <version>1.2.17</version>
          </dependency>
          <dependency>
            <groupId>com.acme</groupId>
            <artifactId>core</artifactId>
          </dependency>
          <dependency><artifactId>no-group</artifactId></dependency>
        </dependencies>
      </project>
      """);

    assertThat(deps).extracting(Dependency::ecosystem, Dependency::name, Dependency::version, Dependency::line)
      .containsExactly(tuple("maven", "log4j:log4j", "1.2.17", 4), tuple("maven", "com.acme:core", null, 9));
  }

  @Test
  void gradleDependencies() {
    var deps = ManifestScanner.scan("build.gradle.kts", """
      dependencies {
          implementation("com.google.guava:guava:33.0.0-jre")
          testImplementation 'org.junit.jupiter:junit-jupiter'
          api(project(":core"))
      }
      """);

    assertThat(deps).extracting(Dependency::name, Dependency::version, Dependency::line)
      .containsExactly(tuple("com.google.guava:guava", "33.0.0-jre", 2), tuple("org.junit.jupiter:junit-jupiter", null, 3));
  }

  @Test
  void gradleCommentsAreIgnored() {
    var deps = ManifestScanner.scan("build.gradle", """
      dependencies {
          // implementation 'log4j:log4j:1.2.17'
          /* implementation 'commons-collections:commons-collections:3.2.1'
             testImplementation 'x:y' */
          implementation 'com.acme:core:1.0'
      }
      """);

    assertThat(deps).extracting(Dependency::name, Dependency::line).containsExactly(tuple("com.acme:core", 5));
  }

  @Test
  void npmLinesPointIntoTheRightSection() {
    var deps = ManifestScanner.scan("package.json", """
      {
        "name": "react",
        "scripts": {"react": "echo"},
        "dependencies": {
          "react": "^18.0.0"
        }
      }
      """);

    assertThat(deps).extracting(Dependency::name, Dependency::line).containsExactly(tuple("react", 5));
  }

  @Test
  void npmDependenciesFromAllSections() {
    var deps = ManifestScanner.scan("package.json", """
      {
        "name": "web",
        "dependencies": {"react": "^18.0.0"},
        "devDependencies": {
          "moment": "2.29.4"
        }
      }
      """);

    assertThat(deps).extracting(Dependency::ecosystem, Dependency::name, Dependency::line)
      .containsExactly(tuple("npm", "react", 3), tuple("npm", "moment", 5));
    assertThat(ManifestScanner.scan("package.json", "{broken")).isEmpty();
  }

  @Test
  void hostileManifestsAreScannedInLinearTime() {
    var spaces = " ".repeat(500_000);
    org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> {
      ManifestScanner.scan("pom.xml", "<dependency>".repeat(60_000));
      ManifestScanner.scan("pom.xml", "<!--".repeat(100_000));
      ManifestScanner.scan("pom.xml", "<dependency><groupId>" + spaces + "x</groupId></dependency>");
      ManifestScanner.scan("build.gradle", "implementation" + spaces + "\n" + "implementation(" + " ".repeat(3000) + "'a:b'");
      ManifestScanner.scan("requirements.txt", "a" + spaces + "b");
      ManifestScanner.scan("package.json", "{\"dependencies\":{" + "\"a\":\"1\",".repeat(50_000) + "\"z\":\"1\"}}");
    });
    assertThat(ManifestScanner.scan("pom.xml", "x".repeat(ManifestScanner.MAX_BYTES + 1))).isEmpty();
  }

  @Test
  void pythonRequirements() {
    var deps = ManifestScanner.scan("requirements.txt", """
      # pinned
      requests[security]==2.32.0  # http
      -r base.txt
      git+https://github.com/x/y.git
      pycrypto
      urllib3>=2; python_version > "3.8"
      """);

    assertThat(deps).extracting(Dependency::name, Dependency::version, Dependency::line)
      .containsExactly(tuple("requests", "==2.32.0", 2), tuple("pycrypto", null, 5), tuple("urllib3", ">=2", 6));
  }
}
