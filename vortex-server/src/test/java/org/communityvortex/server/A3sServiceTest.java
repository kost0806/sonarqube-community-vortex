package org.communityvortex.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.communityvortex.mcp.FakeSonar;
import org.communityvortex.mcp.analysis.LocalAnalyzer;
import org.communityvortex.mcp.analysis.PythonProjectServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Runs the service with the real SonarLint engine and the real Python analyzer. */
class A3sServiceTest {

  @TempDir
  static Path cacheDir;
  private static LocalAnalyzer analyzer;

  private FakeSonar server;
  private final List<String> tokensSeen = new ArrayList<>();
  private A3sService service;

  @BeforeAll
  static void startEngine() {
    analyzer = new LocalAnalyzer(cacheDir, null);
  }

  @AfterAll
  static void stopEngine() {
    analyzer.close();
  }

  @BeforeEach
  void setUp() {
    server = PythonProjectServer.create()
      .reply("api/components/show", "{\"component\":{\"key\":\"acme\",\"name\":\"Acme\"}}")
      .reply("api/authentication/validate", "{\"valid\":true}")
      .reply("api/vortex/policy", """
        {"policy":{"architectureRules":[{"line":3,"from":"shop.domain.**","to":"shop.infra.**","text":"deny shop.domain.** -> shop.infra.**"}],
         "architectureRuleErrors":[],"deniedDependencies":["pypi:pycrypto"],"allowedDependencies":[],"guidelines":null}}""");
    service = new A3sService(token -> {
      tokensSeen.add(token);
      return server;
    }, analyzer, cacheDir, 10);
  }

  private static String request(String... pathsAndContents) {
    var files = new JsonArray();
    for (int i = 0; i < pathsAndContents.length; i += 2) {
      var file = new JsonObject();
      file.addProperty("path", pathsAndContents[i]);
      file.addProperty("content", pathsAndContents[i + 1]);
      files.add(file);
    }
    var json = new JsonObject();
    json.addProperty("projectKey", "acme");
    json.add("files", files);
    return json.toString();
  }

  @Test
  void analysesPostedFilesWithServerRulesAndPolicies() throws Exception {
    var response = service.analyses("Bearer squ_user", request(
      "src/shop/domain/order.py", "from shop.infra import db\n\ndef total():\n    leftover = 3\n    return db.count()\n",
      "requirements.txt", "pycrypto==2.6\nrequests==2.32.0\n"));

    assertThat(response.status()).isEqualTo(200);
    var body = response.body();
    assertThat(body.get("id").getAsString()).isNotBlank();
    var issues = body.getAsJsonArray("issues");
    var rules = new ArrayList<String>();
    issues.forEach(i -> rules.add(i.getAsJsonObject().get("rule").getAsString()));
    assertThat(rules).contains("python:S1481", "vortex:architecture", "vortex:dependency");

    var unused = find(issues, "python:S1481");
    assertThat(unused.get("filePath").getAsString()).isEqualTo("src/shop/domain/order.py");
    var range = unused.getAsJsonObject("textRange");
    assertThat(range.get("startLine").getAsInt()).isEqualTo(4);
    assertThat(range.get("startOffset").getAsInt()).isEqualTo(4);
    assertThat(range.get("endOffset").getAsInt()).isEqualTo(12);
    assertThat(unused.getAsJsonArray("flows")).isEmpty();

    var architecture = find(issues, "vortex:architecture");
    assertThat(architecture.get("message").getAsString()).contains("must not depend on 'shop.infra'").contains("rule line 3");
    assertThat(architecture.getAsJsonObject("textRange").get("startLine").getAsInt()).isEqualTo(1);
    assertThat(find(issues, "vortex:dependency").get("filePath").getAsString()).isEqualTo("requirements.txt");

    assertThat(tokensSeen).containsOnly("squ_user");
    try (var leftovers = Files.list(cacheDir.resolve("requests"))) {
      assertThat(leftovers).as("request files are deleted").isEmpty();
    }
  }

  @Test
  void cleanFilesHaveNoIssues() {
    var response = service.analyses("Bearer t", request("src/util.py", "def add(a, b):\n    return a + b\n"));

    assertThat(response.status()).isEqualTo(200);
    assertThat(response.body().getAsJsonArray("issues")).isEmpty();
  }

  @Test
  void requiresABearerToken() {
    assertThat(service.analyses(null, request("a.py", "")).status()).isEqualTo(401);
    assertThat(service.analyses("Basic YWRtaW4=", request("a.py", "")).status()).isEqualTo(401);
    assertThat(service.entitlement(null).status()).isEqualTo(401);
  }

  @Test
  void relaysSonarQubePermissionDecisions() {
    server.fail("api/components/show", 403, "Insufficient privileges");
    assertThat(service.analyses("Bearer t", request("a.py", "")).status()).isEqualTo(403);

    server.fail("api/components/show", 404, "Component key 'acme' not found");
    var notFound = service.analyses("Bearer t", request("a.py", ""));
    assertThat(notFound.status()).isEqualTo(404);
    assertThat(notFound.body().get("message").getAsString()).contains("not found");

    server.fail("api/components/show", 401, "Unauthorized");
    assertThat(service.analyses("Bearer t", request("a.py", "")).status()).isEqualTo(401);

    server.fail("api/components/show", 500, "boom");
    var upstream = service.analyses("Bearer t", request("a.py", ""));
    assertThat(upstream.status()).isEqualTo(502);
    assertThat(upstream.body().get("message").getAsString()).doesNotContain("boom");
  }

  @Test
  void invalidRequestsAreRejectedBeforeTouchingTheServer() {
    var response = service.analyses("Bearer t", request("../escape.py", ""));

    assertThat(response.status()).isEqualTo(400);
    assertThat(server.calls()).isEmpty();
    var tooMany = service.analyses("Bearer t", request("a.py", "", "b.py", "", "c.py", "", "d.py", "", "e.py", "", "f.py", "",
      "g.py", "", "h.py", "", "i.py", "", "j.py", "", "k.py", ""));
    assertThat(tooMany.status()).isEqualTo(413);
  }

  @Test
  void entitlementReflectsAValidToken() {
    var allowed = service.entitlement("Bearer t");
    assertThat(allowed.status()).isEqualTo(200);
    assertThat(allowed.body().get("allowed").getAsBoolean()).isTrue();

    server.reply("api/authentication/validate", "{\"valid\":false}");
    assertThat(service.entitlement("Bearer t").status()).isEqualTo(401);
  }

  @Test
  void activeRulesAreCachedPerProject() {
    service.analyses("Bearer t", request("a.py", "x = 1\n"));
    service.analyses("Bearer t", request("b.py", "y = 2\n"));

    assertThat(server.count("api/qualityprofiles/search")).isEqualTo(1);
    // permissions are still checked on every request
    assertThat(server.count("api/components/show")).isEqualTo(2);
  }

  private static JsonObject find(JsonArray issues, String rule) {
    for (var issue : issues) {
      if (issue.getAsJsonObject().get("rule").getAsString().equals(rule)) {
        return issue.getAsJsonObject();
      }
    }
    throw new AssertionError("no issue for " + rule + " in " + issues);
  }

  @Test
  void responseIsValidJsonForTheCli() {
    var response = service.analyses("Bearer t", request("a.py", "def f():\n    v = 1\n"));

    var reparsed = JsonParser.parseString(response.body().toString()).getAsJsonObject();
    assertThat(reparsed.has("issues")).isTrue();
    assertThat(reparsed.get("errors").isJsonArray()).isTrue();
  }
}
