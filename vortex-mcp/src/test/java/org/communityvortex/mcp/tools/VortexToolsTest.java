package org.communityvortex.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.communityvortex.mcp.FakeSonar;
import org.communityvortex.mcp.analysis.Analyzer;
import org.communityvortex.mcp.analysis.RuleRepository;
import org.communityvortex.mcp.branch.BranchResolver;
import org.communityvortex.mcp.context.ContextService;
import org.communityvortex.mcp.index.CodeIndex;
import org.communityvortex.mcp.index.ReferenceFinder;
import org.communityvortex.mcp.verify.PolicySource;
import org.communityvortex.mcp.verify.Verifier;
import org.communityvortex.mcp.workspace.Workspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class VortexToolsTest {

  @TempDir
  Path root;
  private FakeSonar server;
  private VortexTools tools;

  @BeforeEach
  void setUp() throws Exception {
    org.communityvortex.mcp.index.SampleWorkspaceAccess.create(root);
    server = new FakeSonar()
      .reply("api/vortex/context", params -> "{\"project\":{\"key\":\"acme\"},\"scope\":{\"branch\":" + quote(params.get("branch")) + "}}")
      .reply("api/vortex/policy", "{\"policy\":{\"architectureRules\":[],\"architectureRuleErrors\":[],\"deniedDependencies\":[],\"allowedDependencies\":[]}}")
      .reply("api/project_branches/list", "{\"branches\":[{\"name\":\"main\",\"isMain\":true}]}")
      .reply("api/qualityprofiles/search", "{\"profiles\":[{\"key\":\"p\",\"language\":\"java\"}]}")
      .reply("api/rules/search", """
        {"total":3,"rules":[
          {"key":"java:S2","name":"Beta rule","lang":"java","severity":"MAJOR","type":"BUG"},
          {"key":"java:S1","name":"Alpha rule","lang":"java","severity":"MINOR","type":"CODE_SMELL"},
          {"key":"java:S3","name":"Gamma","lang":"java","severity":"INFO","type":"CODE_SMELL"}],
         "actives":{"java:S1":[{"qProfile":"p","severity":"MINOR","params":[{"key":"max","value":"3"}]}]}}""");
    var workspace = new Workspace(root);
    var index = new CodeIndex(workspace);
    Analyzer analyzer = files -> new Analyzer.Report(List.of(), List.of(), List.of(), List.of());
    var branches = new BranchResolver(server, "acme", null, null, root);
    tools = new VortexTools(workspace, new ContextService(server, "acme", branches, index), new RuleRepository(server, "acme"),
      new Verifier(workspace, analyzer, index, new PolicySource(server, "acme")), index, new ReferenceFinder(index));
  }

  private static String quote(String value) {
    return value == null ? "null" : "\"" + value + "\"";
  }

  private static JsonObject json(CallToolResult result) {
    assertThat(result.isError()).as(text(result)).isFalse();
    return JsonParser.parseString(text(result)).getAsJsonObject();
  }

  private static String text(CallToolResult result) {
    return ((McpSchema.TextContent) result.content().get(0)).text();
  }

  @Test
  void exposesSevenReadOnlyTools() {
    var specs = tools.specifications(new JacksonMcpJsonMapper(JsonMapper.builder().build()));

    assertThat(tools.names()).containsExactly("vortex_get_context", "vortex_get_rules", "vortex_verify_files",
      "vortex_search_symbols", "vortex_file_outline", "vortex_find_references", "vortex_get_dependencies");
    assertThat(specs).hasSize(7).allSatisfy(s -> assertThat(s.tool().inputSchema()).containsEntry("type", "object"));
    var verify = specs.get(2);
    assertThat(verify.tool().inputSchema()).containsEntry("required", List.of("paths"));
    var result = verify.callHandler().apply(null, new McpSchema.CallToolRequest("vortex_verify_files", Map.of("paths", List.of("pom.xml"))));
    assertThat(result.isError()).isTrue();
  }

  @Test
  void contextCombinesServerAndWorkspace() {
    var context = json(tools.call("vortex_get_context", Map.of("branch", "feature/x")));

    assertThat(context.getAsJsonObject("scope").get("branch").getAsString()).isEqualTo("feature/x");
    assertThat(context.getAsJsonObject("resolvedScope").get("source").getAsString()).isEqualTo("ARGUMENT");
    assertThat(context.getAsJsonObject("workspace").getAsJsonObject("indexedFilesByLanguage").get("java").getAsInt()).isEqualTo(3);
    assertThat(context.getAsJsonObject("workspace").getAsJsonArray("modules")).isNotEmpty();
    assertThat(context.get("workflow").getAsString()).contains("vortex_verify_files");
  }

  @Test
  void contextFallsBackWhenPluginIsMissing() {
    server.fail("api/vortex/context", 404, "Unknown url : api/vortex/context");

    var context = json(tools.call("vortex_get_context", Map.of()));

    assertThat(context.getAsJsonArray("warnings").get(0).getAsString()).contains("not installed");
    assertThat(context.getAsJsonArray("qualityProfiles")).hasSize(1);
  }

  @Test
  void contextPropagatesOtherServerErrors() {
    server.fail("api/vortex/context", 403, "Insufficient privileges");

    var result = tools.call("vortex_get_context", Map.of());

    assertThat(result.isError()).isTrue();
    assertThat(text(result)).contains("Insufficient privileges");
  }

  @Test
  void rulesAreFilteredSortedAndPaged() {
    var all = json(tools.call("vortex_get_rules", Map.of("language", "java", "limit", 2)));
    assertThat(all.get("total").getAsInt()).isEqualTo(3);
    assertThat(all.getAsJsonArray("rules")).hasSize(2);
    assertThat(all.getAsJsonArray("rules").get(0).getAsJsonObject().get("key").getAsString()).isEqualTo("java:S1");
    assertThat(all.getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonObject("params").get("max").getAsString()).isEqualTo("3");

    var filtered = json(tools.call("vortex_get_rules", Map.of("query", "gamma", "offset", 0)));
    assertThat(filtered.getAsJsonArray("rules")).singleElement()
      .satisfies(r -> assertThat(r.getAsJsonObject().get("key").getAsString()).isEqualTo("java:S3"));
    assertThat(json(tools.call("vortex_get_rules", Map.of("language", "py"))).get("total").getAsInt()).isZero();
  }

  @Test
  void navigationTools() {
    var symbols = JsonParser.parseString(text(tools.call("vortex_search_symbols", Map.of("query", "OrderService")))).getAsJsonArray();
    assertThat(symbols.get(0).getAsJsonObject().get("kind").getAsString()).isEqualTo("class");

    var outline = json(tools.call("vortex_file_outline", Map.of("path", "web/src/ui/App.tsx")));
    assertThat(outline.getAsJsonArray("imports")).hasSize(3);

    var references = json(tools.call("vortex_find_references", Map.of("symbol", "com.acme.domain.Order.total")));
    assertThat(references.getAsJsonArray("references")).hasSize(1);

    var dependencies = json(tools.call("vortex_get_dependencies", Map.of("path", "web/src/db/client.ts")));
    assertThat(dependencies.getAsJsonArray("importedBy").get(0).getAsString()).isEqualTo("web/src/ui/App.tsx");
  }

  @Test
  void verifyReportsPassForCleanFiles() {
    var report = json(tools.call("vortex_verify_files", Map.of("paths", List.of("web/src/db/client.ts"))));

    assertThat(report.get("passed").getAsBoolean()).isTrue();
  }

  @Test
  void invalidArgumentsBecomeToolErrors() {
    assertThat(text(tools.call("vortex_search_symbols", Map.of()))).isEqualTo("'query' is required");
    assertThat(text(tools.call("vortex_search_symbols", Map.of("query", 3)))).isEqualTo("'query' must be a string");
    assertThat(text(tools.call("vortex_search_symbols", Map.of("query", "a", "limit", 1000)))).contains("between 1 and 200");
    assertThat(text(tools.call("vortex_search_symbols", Map.of("query", "a", "limit", 1.5)))).contains("integer");
    assertThat(text(tools.call("vortex_verify_files", Map.of("paths", "pom.xml")))).contains("non-empty array");
    assertThat(text(tools.call("vortex_verify_files", Map.of("paths", List.of(" "))))).contains("non-empty strings");
    assertThat(text(tools.call("vortex_file_outline", Map.of("path", "README.md")))).contains("no outline");
    assertThat(text(tools.call("vortex_get_dependencies", Map.of("path", "../x")))).contains("outside the workspace");
    assertThat(tools.call("nope", null).isError()).isTrue();
  }

  @Test
  void unexpectedFailuresDoNotLeakDetails() {
    var broken = new VortexTools(new Workspace(root), null, null, null, null, null);

    var result = broken.call("vortex_get_rules", Map.of());

    assertThat(text(result)).isEqualTo("Internal error in vortex_get_rules (NullPointerException)");
    assertThat(Optional.ofNullable(result.isError())).contains(true);
  }
}
