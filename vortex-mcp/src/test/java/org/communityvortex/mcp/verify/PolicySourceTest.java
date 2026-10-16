package org.communityvortex.mcp.verify;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicLong;
import org.communityvortex.mcp.FakeSonar;
import org.junit.jupiter.api.Test;

class PolicySourceTest {

  static final String POLICY = """
    {"project":"acme","policy":{
      "architectureRules":[{"line":1,"from":"com.acme.domain.**","to":"com.acme.infra.**","text":"deny com.acme.domain.** -> com.acme.infra.**"},
                           {"line":2,"from":"web/src/ui/**","to":"web/src/db/**","text":"deny web/src/ui/** -> web/src/db/**"}],
      "architectureRuleErrors":["line 3: unknown keyword 'allow' (expected 'deny')"],
      "deniedDependencies":["maven:log4j:log4j","npm:moment"],
      "allowedDependencies":[],
      "guidelines":"Prefer records."}}""";

  @Test
  void parsesPolicyAndReportsIgnoredRules() {
    var policy = new PolicySource(new FakeSonar().reply("api/vortex/policy", POLICY), "acme").get();

    assertThat(policy.architectureRules()).hasSize(2);
    assertThat(policy.dependencies().deniedPatterns()).containsExactly("maven:log4j:log4j", "npm:moment");
    assertThat(policy.guidelines()).isEqualTo("Prefer records.");
    assertThat(policy.warnings()).containsExactly("Architecture rule ignored: line 3: unknown keyword 'allow' (expected 'deny')");
  }

  @Test
  void cachesForOneMinute() {
    var server = new FakeSonar().reply("api/vortex/policy", POLICY);
    var now = new AtomicLong();
    var source = new PolicySource(server, "acme", now::get);

    source.get();
    now.addAndGet(PolicySource.TTL_NANOS);
    source.get();
    assertThat(server.count("api/vortex/policy")).isEqualTo(1);
    now.addAndGet(1);
    source.get();
    assertThat(server.count("api/vortex/policy")).isEqualTo(2);
  }

  @Test
  void missingPluginDisablesPolicyChecksWithAWarning() {
    var policy = new PolicySource(new FakeSonar(), "acme").get();

    assertThat(policy.architectureRules()).isEmpty();
    assertThat(policy.dependencies().isEmpty()).isTrue();
    assertThat(policy.warnings()).singleElement().asString().contains("plugin is not installed");
  }

  @Test
  void otherFailuresAreReportedButDoNotBreakVerification() {
    var policy = new PolicySource(new FakeSonar().fail("api/vortex/policy", 403, "Insufficient privileges"), "acme").get();

    assertThat(policy.warnings()).singleElement().asString().contains("Insufficient privileges");
  }
}
