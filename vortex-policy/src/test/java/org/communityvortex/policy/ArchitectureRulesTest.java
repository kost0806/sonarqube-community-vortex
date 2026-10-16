package org.communityvortex.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ArchitectureRulesTest {

  @Test
  void parsesDenyRulesIgnoringCommentsAndBlankLines() {
    var parsed = ArchitectureRules.parse("""
      # domain must stay pure

      deny com.acme.domain.** -> com.acme.infra.**
      deny src/ui/** -> src/db/**   # inline comment
      """);

    assertThat(parsed.errors()).isEmpty();
    assertThat(parsed.rules())
      .extracting(r -> r.from().text(), r -> r.to().text(), ArchitectureRule::line)
      .containsExactly(
        org.assertj.core.groups.Tuple.tuple("com.acme.domain.**", "com.acme.infra.**", 3),
        org.assertj.core.groups.Tuple.tuple("src/ui/**", "src/db/**", 4));
  }

  @Test
  void expandsCommaSeparatedTargets() {
    var parsed = ArchitectureRules.parse("deny com.acme.domain.** -> java.sql.**, com.acme.infra.**");

    assertThat(parsed.rules()).extracting(r -> r.to().text())
      .containsExactly("java.sql.**", "com.acme.infra.**");
  }

  @Test
  void keywordIsCaseInsensitive() {
    assertThat(ArchitectureRules.parse("DENY a.** -> b.**").rules()).hasSize(1);
  }

  @Test
  void reportsSyntaxErrorsWithLineNumbersAndKeepsValidRules() {
    var parsed = ArchitectureRules.parse("""
      deny a.** -> b.**
      allow a.** -> c.**
      deny a.**
      deny  -> b.**
      deny a.** -> b c
      """);

    assertThat(parsed.rules()).hasSize(1);
    assertThat(parsed.errors()).containsExactly(
      "line 2: unknown keyword 'allow' (expected 'deny')",
      "line 3: expected 'deny <from> -> <to>'",
      "line 4: expected 'deny <from> -> <to>'",
      "line 5: pattern 'b c' must not contain whitespace");
  }

  @Test
  void nullOrEmptyTextYieldsNoRules() {
    assertThat(ArchitectureRules.parse(null).rules()).isEmpty();
    assertThat(ArchitectureRules.parse("").rules()).isEmpty();
  }

  @Test
  void findsViolationsForMatchingImports() {
    var rules = ArchitectureRules.parse("""
      deny com.acme.domain.** -> com.acme.infra.**
      deny com.acme.domain.** -> java.sql.**
      """).rules();

    var violations = ArchitectureRules.check("com.acme.domain.order",
      List.of("com.acme.infra.db.OrderRepo", "java.util.List", "java.sql.Connection"), rules);

    assertThat(violations)
      .extracting(ArchitectureViolation::target, v -> v.rule().line())
      .containsExactly(
        org.assertj.core.groups.Tuple.tuple("com.acme.infra.db.OrderRepo", 1),
        org.assertj.core.groups.Tuple.tuple("java.sql.Connection", 2));
    assertThat(violations.get(0).message())
      .isEqualTo("'com.acme.domain.order' must not depend on 'com.acme.infra.db.OrderRepo' (rule line 1: deny com.acme.domain.** -> com.acme.infra.**)");
  }

  @Test
  void noViolationWhenSourceDoesNotMatch() {
    var rules = ArchitectureRules.parse("deny com.acme.domain.** -> com.acme.infra.**").rules();

    assertThat(ArchitectureRules.check("com.acme.app", List.of("com.acme.infra.Repo"), rules)).isEmpty();
  }

  @Test
  void parsedRulesAreImmutable() {
    var parsed = ArchitectureRules.parse("deny a.** -> b.**");

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> parsed.rules().clear())
      .isInstanceOf(UnsupportedOperationException.class);
  }
}
