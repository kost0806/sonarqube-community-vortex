package org.communityvortex.mcp.tools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import org.communityvortex.mcp.analysis.RuleRepository;
import org.communityvortex.mcp.analysis.ServerActiveRule;
import org.communityvortex.mcp.context.ContextService;
import org.communityvortex.mcp.index.CodeIndex;
import org.communityvortex.mcp.index.ReferenceFinder;
import org.communityvortex.mcp.sonar.SonarServerException;
import org.communityvortex.mcp.verify.Verifier;
import org.communityvortex.mcp.workspace.Workspace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The MCP tools exposed to coding agents. Every tool is read-only. */
public final class VortexTools {

  private static final Logger LOG = LoggerFactory.getLogger(VortexTools.class);

  private record Tool(String name, String description, String inputSchema, Function<ToolArguments, Object> handler) {
  }

  private final Gson gson = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
  private final Map<String, Tool> tools = new LinkedHashMap<>();
  private final Workspace workspace;
  private final ContextService context;
  private final RuleRepository rules;
  private final Verifier verifier;
  private final CodeIndex index;
  private final ReferenceFinder references;

  public VortexTools(Workspace workspace, ContextService context, RuleRepository rules, Verifier verifier, CodeIndex index,
    ReferenceFinder references) {
    this.workspace = workspace;
    this.context = context;
    this.rules = rules;
    this.verifier = verifier;
    this.index = index;
    this.references = references;
    register();
  }

  public List<SyncToolSpecification> specifications(McpJsonMapper jsonMapper) {
    var specifications = new ArrayList<SyncToolSpecification>();
    for (var tool : tools.values()) {
      var definition = McpSchema.Tool.builder()
        .name(tool.name())
        .description(tool.description())
        .inputSchema(jsonMapper, tool.inputSchema())
        .build();
      specifications.add(SyncToolSpecification.builder()
        .tool(definition)
        .callHandler((exchange, request) -> call(tool.name(), request.arguments()))
        .build());
    }
    return specifications;
  }

  public List<String> names() {
    return List.copyOf(tools.keySet());
  }

  public CallToolResult call(String name, Map<String, Object> arguments) {
    var tool = tools.get(name);
    if (tool == null) {
      return error("Unknown tool: " + name);
    }
    try {
      var result = tool.handler().apply(new ToolArguments(arguments));
      return CallToolResult.builder().addTextContent(gson.toJson(result)).isError(false).build();
    } catch (IllegalArgumentException | IllegalStateException | SonarServerException e) {
      return error(e.getMessage());
    } catch (RuntimeException e) {
      LOG.error("Tool {} failed", name, e);
      return error("Internal error in " + name + " (" + e.getClass().getSimpleName() + ")");
    }
  }

  private static CallToolResult error(String message) {
    return CallToolResult.builder().addTextContent(message).isError(true).build();
  }

  private void register() {
    add(new Tool("vortex_get_context",
      "Project context from SonarQube before writing code: quality profiles, quality gate, architecture rules, dependency "
        + "allow/deny lists, coding guidelines, branches, and a module map of the local workspace. Branch defaults to the "
        + "checked-out git branch when SonarQube has analysed it (community branch plugin), else the main branch.",
      """
        {"type":"object","properties":{
          "branch":{"type":"string","description":"SonarQube branch name"},
          "pullRequest":{"type":"string","description":"SonarQube pull request id"}},
         "additionalProperties":false}""",
      args -> context.context(args.optionalString("branch"), args.optionalString("pullRequest"))));

    add(new Tool("vortex_get_rules",
      "Active coding rules of the project's quality profiles (what vortex_verify_files enforces).",
      """
        {"type":"object","properties":{
          "language":{"type":"string","description":"Language key, e.g. java, py, js, ts"},
          "query":{"type":"string","description":"Filter on rule key or name"},
          "limit":{"type":"integer","minimum":1,"maximum":200,"default":50},
          "offset":{"type":"integer","minimum":0,"default":0}},
         "additionalProperties":false}""",
      this::getRules));

    add(new Tool("vortex_verify_files",
      "Verify changed files: analyses them locally with the project's SonarQube analyzers and active rules and checks "
        + "architecture rules (imports) and dependency policies (manifests). 'passed' is true only when nothing is reported. "
        + "Taint/security-engine analysis is not available in Community Build.",
      """
        {"type":"object","properties":{
          "paths":{"type":"array","items":{"type":"string"},"minItems":1,"maxItems":50,
                   "description":"Workspace-relative paths of the files to verify"}},
         "required":["paths"],"additionalProperties":false}""",
      args -> verifier.verify(args.stringList("paths"))));

    add(new Tool("vortex_search_symbols",
      "Find declarations (classes, methods, functions, fields...) by name across the workspace.",
      """
        {"type":"object","properties":{
          "query":{"type":"string","description":"Substring of the symbol name, or an exact qualified name"},
          "kind":{"type":"string","description":"Optional kind: class, interface, enum, record, method, constructor, field, function, variable, type"},
          "limit":{"type":"integer","minimum":1,"maximum":200,"default":20}},
         "required":["query"],"additionalProperties":false}""",
      args -> {
        index.refresh();
        return index.searchSymbols(args.requiredString("query"), args.optionalString("kind"), args.integer("limit", 20, 1, 200));
      }));

    add(new Tool("vortex_file_outline",
      "Declarations and imports of one file, without its body.",
      """
        {"type":"object","properties":{"path":{"type":"string"}},"required":["path"],"additionalProperties":false}""",
      args -> index.facts(workspace.resolveFile(args.requiredString("path")))
        .orElseThrow(() -> new IllegalArgumentException("no outline available for this file type"))));

    add(new Tool("vortex_find_references",
      "Where a symbol is used. Java usages are resolved semantically (confidence RESOLVED); others are name matches.",
      """
        {"type":"object","properties":{
          "symbol":{"type":"string","description":"Qualified name (com.acme.Order.total) or simple name"},
          "limit":{"type":"integer","minimum":1,"maximum":500,"default":50}},
         "required":["symbol"],"additionalProperties":false}""",
      args -> references.find(args.requiredString("symbol"), args.integer("limit", 50, 1, 500))));

    add(new Tool("vortex_get_dependencies",
      "What a file imports and which workspace files import it.",
      """
        {"type":"object","properties":{"path":{"type":"string"}},"required":["path"],"additionalProperties":false}""",
      args -> {
        index.refresh();
        var facts = index.facts(workspace.resolveFile(args.requiredString("path")))
          .orElseThrow(() -> new IllegalArgumentException("no dependency information for this file type"));
        var json = new JsonObject();
        json.addProperty("file", facts.file());
        json.addProperty("unit", facts.unit());
        json.add("imports", gson.toJsonTree(facts.imports()));
        json.add("importedBy", gson.toJsonTree(index.importers(facts)));
        return json;
      }));
  }

  private Object getRules(ToolArguments args) {
    var language = args.optionalString("language");
    var query = args.optionalString("query");
    var limit = args.integer("limit", 50, 1, 200);
    var offset = args.integer("offset", 0, 0, Integer.MAX_VALUE);
    var matching = new ArrayList<Map<String, Object>>();
    for (var entry : rules.all().entrySet()) {
      if (language != null && !language.equals(entry.getKey())) {
        continue;
      }
      for (var rule : entry.getValue()) {
        if (query == null || matches(rule, query)) {
          matching.add(describe(rule));
        }
      }
    }
    matching.sort((a, b) -> ((String) a.get("key")).compareTo((String) b.get("key")));
    var page = matching.subList(Math.min(offset, matching.size()), Math.min(offset + limit, matching.size()));
    var json = new JsonObject();
    json.addProperty("total", matching.size());
    json.addProperty("offset", offset);
    json.add("rules", gson.toJsonTree(page));
    return json;
  }

  private static boolean matches(ServerActiveRule rule, String query) {
    var needle = query.toLowerCase(Locale.ROOT);
    return rule.ruleKey().toString().toLowerCase(Locale.ROOT).contains(needle)
      || (rule.name() != null && rule.name().toLowerCase(Locale.ROOT).contains(needle));
  }

  private static Map<String, Object> describe(ServerActiveRule rule) {
    var json = new LinkedHashMap<String, Object>();
    json.put("key", rule.ruleKey().toString());
    json.put("name", rule.name());
    json.put("language", rule.language());
    json.put("severity", rule.severity());
    json.put("type", rule.type());
    if (!rule.params().isEmpty()) {
      json.put("params", rule.params());
    }
    return json;
  }

  private void add(Tool tool) {
    tools.put(tool.name(), tool);
  }
}
