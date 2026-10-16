package org.communityvortex.plugin.ws;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonParser;
import org.communityvortex.plugin.ws.FakeSonarServer.FakeResponse;
import org.junit.jupiter.api.Test;

class PolicyActionTest {

  private final PolicyAction action = new PolicyAction();

  @Test
  void returnsPolicyOfVisibleProject() {
    var server = new FakeSonarServer()
      .reply("api/components/show", "{\"component\":{\"key\":\"acme\",\"name\":\"Acme\"}}")
      .reply("api/settings/values", "{\"settings\":[{\"key\":\"sonar.vortex.dependencies.denied\",\"values\":[\"npm:moment\"]}]}");
    var response = new FakeResponse();

    action.handle(server.request("project", "acme"), response);

    var json = JsonParser.parseString(response.body()).getAsJsonObject();
    assertThat(response.status()).isEqualTo(200);
    assertThat(json.get("project").getAsString()).isEqualTo("acme");
    var policy = json.getAsJsonObject("policy");
    assertThat(policy.getAsJsonArray("deniedDependencies").get(0).getAsString()).isEqualTo("npm:moment");
    assertThat(policy.getAsJsonArray("architectureRules")).isEmpty();
    assertThat(policy.get("guidelines").isJsonNull()).isTrue();
  }

  @Test
  void hiddenProjectIsNotFound() {
    var server = new FakeSonarServer().fail("api/components/show", 404, "Component key 'secret' not found");
    var response = new FakeResponse();

    action.handle(server.request("project", "secret"), response);

    assertThat(response.status()).isEqualTo(404);
    assertThat(server.calls()).extracting(FakeSonarServer.Call::path).doesNotContain("api/settings/values");
  }
}
