package org.communityvortex.plugin.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.communityvortex.plugin.ws.FakeSonarServer.FakeResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ContextActionTest {

  private FakeSonarServer server;
  private final ContextAction action = new ContextAction();

  @BeforeEach
  void setUp() {
    server = new FakeSonarServer()
      .reply("api/components/show", "{\"component\":{\"key\":\"acme\",\"name\":\"Acme Shop\",\"qualifier\":\"TRK\"}}")
      .reply("api/project_branches/list", """
        {"branches":[{"name":"main","isMain":true,"type":"BRANCH"},{"name":"feature/cart","isMain":false,"type":"BRANCH"}]}""")
      .reply("api/project_pull_requests/list", """
        {"pullRequests":[{"key":"7","title":"Add cart","branch":"feature/cart","base":"main"}]}""")
      .reply("api/qualityprofiles/search", """
        {"profiles":[{"key":"p1","name":"Sonar way","language":"java","languageName":"Java","activeRuleCount":512}]}""")
      .reply("api/qualitygates/get_by_project", "{\"qualityGate\":{\"name\":\"Sonar way\",\"default\":true}}")
      .reply("api/qualitygates/show", """
        {"name":"Sonar way","conditions":[{"id":1,"metric":"new_coverage","op":"LT","error":"80"}]}""")
      .reply("api/qualitygates/project_status", "{\"projectStatus\":{\"status\":\"ERROR\"}}")
      .reply("api/measures/component", """
        {"component":{"measures":[{"metric":"ncloc","value":"1200"},{"metric":"files","value":"30"},
        {"metric":"ncloc_language_distribution","value":"java=1100;xml=100"}]}}""")
      .reply("api/measures/component_tree", """
        {"components":[{"path":"src/main/java/com/acme/order","measures":[{"metric":"ncloc","value":"700"}]},{"path":"src/empty","measures":[]}]}""")
      .reply("api/settings/values", """
        {"settings":[
          {"key":"sonar.vortex.architecture.rules","value":"deny com.acme.domain.** -> com.acme.infra.**\\nallow x -> y"},
          {"key":"sonar.vortex.dependencies.denied","values":["maven:log4j:log4j","npm:moment"]},
          {"key":"sonar.vortex.dependencies.allowed","value":"maven:com.acme:*, npm:react"},
          {"key":"sonar.vortex.guidelines","value":"Use records for DTOs."}]}""");
  }

  private JsonObject handle(String... params) {
    var response = new FakeResponse();
    action.handle(server.request(params), response);
    assertThat(response.mediaType()).isEqualTo("application/json");
    var json = JsonParser.parseString(response.body()).getAsJsonObject();
    json.addProperty("_status", response.status());
    return json;
  }

  @Test
  void aggregatesProjectContext() {
    var json = handle("project", "acme");

    assertThat(json.get("_status").getAsInt()).isEqualTo(200);
    assertThat(json.getAsJsonObject("project").get("name").getAsString()).isEqualTo("Acme Shop");
    assertThat(json.getAsJsonObject("scope").get("mainBranch").getAsBoolean()).isTrue();
    assertThat(json.getAsJsonArray("branches")).hasSize(2);
    assertThat(json.getAsJsonArray("pullRequests").get(0).getAsJsonObject().get("branch").getAsString()).isEqualTo("feature/cart");
    assertThat(json.getAsJsonArray("qualityProfiles").get(0).getAsJsonObject().get("activeRuleCount").getAsInt()).isEqualTo(512);

    var gate = json.getAsJsonObject("qualityGate");
    assertThat(gate.get("name").getAsString()).isEqualTo("Sonar way");
    assertThat(gate.get("status").getAsString()).isEqualTo("ERROR");
    assertThat(gate.getAsJsonArray("conditions").get(0).getAsJsonObject().get("metric").getAsString()).isEqualTo("new_coverage");

    var size = json.getAsJsonObject("size");
    assertThat(size.get("ncloc").getAsLong()).isEqualTo(1200);
    assertThat(size.get("files").getAsLong()).isEqualTo(30);
    assertThat(size.getAsJsonObject("nclocByLanguage").get("xml").getAsLong()).isEqualTo(100);

    var dirs = json.getAsJsonArray("topDirectories");
    assertThat(dirs.get(0).getAsJsonObject().get("ncloc").getAsLong()).isEqualTo(700);
    assertThat(dirs.get(1).getAsJsonObject().has("ncloc")).isFalse();
    assertThat(json.getAsJsonArray("warnings")).isEmpty();
  }

  @Test
  void parsesPoliciesFromSettings() {
    var policy = handle("project", "acme").getAsJsonObject("policy");

    assertThat(policy.getAsJsonArray("architectureRules")).hasSize(1);
    assertThat(policy.getAsJsonArray("architectureRules").get(0).getAsJsonObject().get("text").getAsString())
      .isEqualTo("deny com.acme.domain.** -> com.acme.infra.**");
    assertThat(policy.getAsJsonArray("architectureRuleErrors").get(0).getAsString())
      .isEqualTo("line 2: unknown keyword 'allow' (expected 'deny')");
    assertThat(policy.getAsJsonArray("deniedDependencies")).hasSize(2);
    assertThat(policy.getAsJsonArray("allowedDependencies").get(1).getAsString()).isEqualTo("npm:react");
    assertThat(policy.get("guidelines").getAsString()).isEqualTo("Use records for DTOs.");
  }

  @Test
  void forwardsBranchToBranchAwareServicesOnly() {
    handle("project", "acme", "branch", "feature/cart");

    assertThat(server.lastCall("api/components/show").params()).containsEntry("branch", "feature/cart");
    assertThat(server.lastCall("api/measures/component").params()).containsEntry("branch", "feature/cart");
    assertThat(server.lastCall("api/measures/component_tree").params()).containsEntry("branch", "feature/cart");
    assertThat(server.lastCall("api/qualitygates/project_status").params()).containsEntry("branch", "feature/cart");
    // project level settings and profiles are not branch specific
    assertThat(server.lastCall("api/qualityprofiles/search").params()).doesNotContainKey("branch");
    assertThat(server.lastCall("api/settings/values").params()).doesNotContainKey("branch");
  }

  @Test
  void forwardsPullRequest() {
    var json = handle("project", "acme", "pullRequest", "7");

    assertThat(server.lastCall("api/qualitygates/project_status").params()).containsEntry("pullRequest", "7");
    assertThat(json.getAsJsonObject("scope").get("pullRequest").getAsString()).isEqualTo("7");
  }

  @Test
  void rejectsBranchAndPullRequestTogether() {
    var request = server.request("project", "acme", "branch", "b", "pullRequest", "7");

    assertThatThrownBy(() -> action.handle(request, new FakeResponse()))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("Only one of");
  }

  @Test
  void relaysPermissionErrorFromTheProjectLookup() {
    server.fail("api/components/show", 403, "Insufficient privileges");

    var json = handle("project", "acme");

    assertThat(json.get("_status").getAsInt()).isEqualTo(403);
    assertThat(json.getAsJsonArray("errors").get(0).getAsJsonObject().get("msg").getAsString())
      .isEqualTo("api/components/show: Insufficient privileges");
  }

  @Test
  void relaysUnknownBranchAsNotFound() {
    server.fail("api/components/show", 404, "Component 'acme' on branch 'nope' not found");

    assertThat(handle("project", "acme", "branch", "nope").get("_status").getAsInt()).isEqualTo(404);
  }

  @Test
  void hidesUnexpectedUpstreamFailures() {
    server.fail("api/components/show", 500, "An error has occurred");

    var json = handle("project", "acme");

    assertThat(json.get("_status").getAsInt()).isEqualTo(502);
    assertThat(json.getAsJsonArray("errors").get(0).getAsJsonObject().get("msg").getAsString())
      .isEqualTo("Upstream web service failed");
  }

  @Test
  void degradesOptionalSectionsToWarnings() {
    server.fail("api/measures/component", 404, "never analysed")
      .fail("api/qualitygates/project_status", 404, "no analysis");

    var json = handle("project", "acme");

    assertThat(json.get("_status").getAsInt()).isEqualTo(200);
    assertThat(json.get("size").isJsonNull()).isTrue();
    assertThat(json.get("qualityGate").isJsonNull()).isTrue();
    assertThat(json.getAsJsonArray("warnings")).extracting(e -> e.getAsString())
      .containsExactly("quality gate unavailable: api/qualitygates/project_status: no analysis",
        "size measures unavailable: api/measures/component: never analysed");
  }

  @Test
  void communityBuildWithoutPullRequestSupportYieldsNoPullRequests() {
    server.fail("api/project_pull_requests/list", 404, "Unknown url");

    var json = handle("project", "acme");

    assertThat(json.getAsJsonArray("pullRequests")).isEmpty();
    assertThat(json.getAsJsonArray("warnings")).isEmpty();
  }

  @Test
  void rejectsMalformedUpstreamJson() {
    server.reply("api/components/show", "not json");

    assertThat(handle("project", "acme").get("_status").getAsInt()).isEqualTo(502);
  }
}
