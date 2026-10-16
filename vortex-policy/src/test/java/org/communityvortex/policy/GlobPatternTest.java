package org.communityvortex.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class GlobPatternTest {

  @ParameterizedTest(name = "{0} matches {1} -> {2}")
  @CsvSource({
    "com.acme.domain.**, com.acme.domain, true",
    "com.acme.domain.**, com.acme.domain.Order, true",
    "com.acme.domain.**, com.acme.domain.model.Order, true",
    "com.acme.domain.**, com.acme.domainx.Order, false",
    "com.acme.domain.**, com.acme.infra.Repo, false",
    "com.acme.*, com.acme.Order, true",
    "com.acme.*, com.acme.model.Order, false",
    "com.**.infra.**, com.acme.infra.db.Repo, true",
    "com.**.infra.**, com.infra, true",
    "**.internal.**, org.lib.internal.Impl, true",
    "**, anything.at.all, true",
    "java.sql.*, java.sql.Connection, true",
    "com.acme.Ord?r, com.acme.Order, true",
    "com.acme.Ord?r, com.acme.Ordr, false",
  })
  void dottedPatterns(String pattern, String candidate, boolean expected) {
    assertThat(GlobPattern.parse(pattern).matches(candidate)).isEqualTo(expected);
  }

  @ParameterizedTest(name = "{0} matches {1} -> {2}")
  @CsvSource({
    "src/ui/**, src/ui/App.tsx, true",
    "src/ui/**, src/ui/components/Button.tsx, true",
    "src/ui/**, src/db/client.ts, false",
    "src/*/index.ts, src/ui/index.ts, true",
    "src/*/index.ts, src/ui/x/index.ts, false",
    "**/db/**, packages/api/db/pool.ts, true",
  })
  void pathPatterns(String pattern, String candidate, boolean expected) {
    assertThat(GlobPattern.parse(pattern).matches(candidate)).isEqualTo(expected);
  }

  @Test
  void consecutiveDoubleStarsBehaveLikeOne() {
    assertThat(GlobPattern.parse("a/**/**/b").matches("a/b")).isTrue();
    assertThat(GlobPattern.parse("a/**/**/b").matches("a/x/y/b")).isTrue();
    assertThat(GlobPattern.parse("**/**").matches("a/b")).isTrue();
  }

  @Test
  void pathPatternNormalizesWindowsSeparators() {
    assertThat(GlobPattern.parse("src/ui/**").matches("src\\ui\\App.tsx")).isTrue();
  }

  @Test
  void regexMetacharactersAreLiteral() {
    var pattern = GlobPattern.parse("a+b.(c)");
    assertThat(pattern.matches("a+b.(c)")).isTrue();
    assertThat(pattern.matches("aab.(c)")).isFalse();
  }

  @Test
  void keepsOriginalText() {
    assertThat(GlobPattern.parse(" com.acme.** ").text()).isEqualTo("com.acme.**");
    assertThat(GlobPattern.parse("com.acme.**")).hasToString("com.acme.**");
  }

  @Test
  void rejectsBlankPattern() {
    assertThatThrownBy(() -> GlobPattern.parse("  "))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("empty");
  }

  @Test
  void rejectsWhitespaceInsidePattern() {
    assertThatThrownBy(() -> GlobPattern.parse("com acme"))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("whitespace");
  }
}
