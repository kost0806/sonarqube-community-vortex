package org.communityvortex.mcp.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import org.communityvortex.mcp.FakeSonar;
import org.junit.jupiter.api.Test;

class RuleRepositoryTest {

  @Test
  void mapsActivationSeverityAndParams() {
    var repository = new RuleRepository(PythonProjectServer.create(), "acme");

    var rules = repository.forLanguage("py");

    assertThat(rules).hasSize(2);
    var trailing = rules.stream().filter(r -> r.ruleKey().rule().equals("S139")).findFirst().orElseThrow();
    assertThat(trailing.severity()).isEqualTo("INFO");
    assertThat(trailing.param("legalTrailingCommentPattern")).isEqualTo("^#\\s*+[^\\s]++$");
    assertThat(trailing.qpKey()).isEqualTo("py-profile");
    assertThat(trailing.language()).isEqualTo("py");
    assertThat(repository.forLanguage("java")).isEmpty();
  }

  @Test
  void pagesThroughAllRulesUsingPagingTotal() {
    var server = new FakeSonar()
      .reply("api/qualityprofiles/search", "{\"profiles\":[{\"key\":\"j\",\"language\":\"java\"}]}")
      .reply("api/rules/search", params -> {
        var page = Integer.parseInt(params.get("p"));
        var rules = new StringBuilder();
        var count = page == 1 ? RuleRepository.PAGE_SIZE : 3;
        for (int i = 0; i < count; i++) {
          rules.append(i == 0 ? "" : ",").append("{\"key\":\"java:S").append(page * 1000 + i).append("\",\"lang\":\"java\",\"severity\":\"MAJOR\"}");
        }
        return "{\"paging\":{\"total\":" + (RuleRepository.PAGE_SIZE + 3) + "},\"rules\":[" + rules + "]}";
      });
    var repository = new RuleRepository(server, "acme");

    assertThat(repository.forLanguage("java")).hasSize(RuleRepository.PAGE_SIZE + 3);
    assertThat(server.count("api/rules/search")).isEqualTo(2);
    // template-free rule without activation details keeps the rule's severity
    assertThat(repository.forLanguage("java").get(0).severity()).isEqualTo("MAJOR");
  }

  /** Fields accepted by api/rules/search 'f' on SonarQube Community Build 26.10 (from its validation error). */
  private static final java.util.Set<String> ALLOWED_FIELDS_26_10 = java.util.Set.of("actives", "cleanCodeAttribute",
    "createdAt", "debtRemFn", "defaultDebtRemFn", "defaultRemFn", "deprecatedKeys", "descriptionSections",
    "educationPrinciples", "gapDescription", "htmlDesc", "htmlNote", "internalKey", "isExternal", "isTemplate", "lang",
    "langName", "mdDesc", "mdNote", "name", "noteLogin", "params", "remFn", "remFnOverloaded", "repo", "scope",
    "severity", "status", "sysTags", "tags", "templateKey", "updatedAt");

  @Test
  void requestsOnlyFieldsSupportedBySonarQube26() {
    var server = PythonProjectServer.create();

    new RuleRepository(server, "acme").forLanguage("py");

    var requested = server.calls().stream().filter(c -> c.path().equals("api/rules/search")).findFirst().orElseThrow()
      .params().get("f").split(",");
    assertThat(requested).isSubsetOf(ALLOWED_FIELDS_26_10);
  }

  @Test
  void refreshesAfterTheCacheExpires() {
    var server = PythonProjectServer.create();
    var now = new java.util.concurrent.atomic.AtomicLong();
    var repository = new RuleRepository(server, "acme", now::get);

    repository.forLanguage("py");
    now.addAndGet(RuleRepository.TTL_NANOS);
    repository.forLanguage("py");
    assertThat(server.count("api/qualityprofiles/search")).isEqualTo(1);
    now.addAndGet(1);
    repository.forLanguage("py");
    assertThat(server.count("api/qualityprofiles/search")).isEqualTo(2);
  }

  @Test
  void cachesUntilInvalidated() {
    var server = PythonProjectServer.create();
    var repository = new RuleRepository(server, "acme");

    repository.forLanguage("py");
    repository.all();
    repository.invalidate();
    repository.forLanguage("py");

    assertThat(server.count("api/qualityprofiles/search")).isEqualTo(2);
  }
}
