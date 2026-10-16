package org.communityvortex.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class DependencyPolicyTest {

  private static Dependency maven(String name) {
    return new Dependency("maven", name, "1.0", "pom.xml", 10);
  }

  private static Dependency npm(String name) {
    return new Dependency("npm", name, "^2.0.0", "package.json", 5);
  }

  @Test
  void deniedPatternWithEcosystemOnlyMatchesThatEcosystem() {
    var policy = DependencyPolicy.of(List.of("maven:log4j:log4j"), List.of());

    assertThat(policy.evaluate(maven("log4j:log4j"))).hasValueSatisfying(v ->
      assertThat(v.reason()).isEqualTo("denied by policy 'maven:log4j:log4j'"));
    assertThat(policy.evaluate(npm("log4j:log4j"))).isEmpty();
  }

  @Test
  void patternWithoutEcosystemMatchesAnyEcosystem() {
    var policy = DependencyPolicy.of(List.of("moment"), List.of());

    assertThat(policy.evaluate(npm("moment"))).isPresent();
    assertThat(policy.evaluate(new Dependency("pypi", "moment", null, "requirements.txt", 1))).isPresent();
  }

  @Test
  void wildcardsAndCaseInsensitivity() {
    var policy = DependencyPolicy.of(List.of("maven:org.apache.logging.log4j:*", "npm:@legacy/*"), List.of());

    assertThat(policy.evaluate(maven("ORG.apache.logging.log4j:log4j-core"))).isPresent();
    assertThat(policy.evaluate(npm("@legacy/widgets"))).isPresent();
    assertThat(policy.evaluate(npm("@modern/widgets"))).isEmpty();
  }

  @Test
  void gradleEcosystemIsAnAliasForMaven() {
    var policy = DependencyPolicy.of(List.of("gradle:com.google.guava:guava"), List.of());

    assertThat(policy.evaluate(maven("com.google.guava:guava"))).isPresent();
  }

  @Test
  void pypiNamesAreNormalized() {
    var policy = DependencyPolicy.of(List.of("pypi:PyCrypto_Dome"), List.of());

    assertThat(policy.evaluate(new Dependency("pypi", "pycrypto-dome", "1", "requirements.txt", 3))).isPresent();
  }

  @Test
  void allowlistRejectsEverythingElse() {
    var policy = DependencyPolicy.of(List.of(), List.of("maven:com.acme:*", "npm:react"));

    assertThat(policy.evaluate(maven("com.acme:core"))).isEmpty();
    assertThat(policy.evaluate(npm("react"))).isEmpty();
    assertThat(policy.evaluate(npm("left-pad"))).hasValueSatisfying(v ->
      assertThat(v.reason()).isEqualTo("not in the allowed dependency list"));
  }

  @Test
  void allowlistOnlyGovernsTheEcosystemsItNames() {
    var policy = DependencyPolicy.of(List.of(), List.of("maven:org.springframework.*"));

    assertThat(policy.evaluate(maven("com.acme:core"))).isPresent();
    assertThat(policy.evaluate(npm("left-pad"))).isEmpty();
  }

  @Test
  void unprefixedAllowlistEntriesGovernEveryEcosystem() {
    var policy = DependencyPolicy.of(List.of(), List.of("react"));

    assertThat(policy.evaluate(maven("com.acme:core"))).isPresent();
    assertThat(policy.evaluate(npm("react"))).isEmpty();
  }

  @Test
  void denyWinsOverAllow() {
    var policy = DependencyPolicy.of(List.of("npm:react"), List.of("npm:*"));

    assertThat(policy.evaluate(npm("react"))).isPresent();
  }

  @Test
  void emptyPolicyAllowsEverything() {
    assertThat(DependencyPolicy.of(List.of(), List.of()).evaluate(npm("anything"))).isEmpty();
    assertThat(DependencyPolicy.of(List.of(), List.of()).isEmpty()).isTrue();
  }

  @Test
  void blankEntriesAreIgnored() {
    var policy = DependencyPolicy.of(List.of(" ", ""), List.of());

    assertThat(policy.isEmpty()).isTrue();
  }

  @Test
  void violationMessageNamesDependencyAndLocation() {
    var violation = DependencyPolicy.of(List.of("npm:moment"), List.of()).evaluate(npm("moment")).orElseThrow();

    assertThat(violation.message()).isEqualTo("npm dependency 'moment' (package.json:5) is denied by policy 'npm:moment'");
  }

  @Test
  void exposesPatternsAsText() {
    var policy = DependencyPolicy.of(List.of("npm:moment"), List.of("maven:com.acme:*"));

    assertThat(policy.deniedPatterns()).containsExactly("npm:moment");
    assertThat(policy.allowedPatterns()).containsExactly("maven:com.acme:*");
  }

  @Test
  void dependencyRequiresEcosystemAndName() {
    assertThatThrownBy(() -> new Dependency(" ", "x", null, "f", 1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Dependency("npm", null, null, "f", 1)).isInstanceOf(IllegalArgumentException.class);
  }
}
