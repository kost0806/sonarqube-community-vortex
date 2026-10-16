package org.communityvortex.mcp.context;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.communityvortex.mcp.branch.BranchResolver;
import org.communityvortex.mcp.index.CodeIndex;
import org.communityvortex.mcp.sonar.Params;
import org.communityvortex.mcp.sonar.SonarServer;
import org.communityvortex.mcp.sonar.SonarServerException;

/** Server-side project context (from the plugin) merged with a local map of the workspace. */
public final class ContextService {

  static final int MODULE_LIMIT = 30;
  static final String WORKFLOW = "Before editing, follow 'policy' (architecture rules, dependency lists, guidelines) and the active "
    + "rules (vortex_get_rules). After editing, call vortex_verify_files on every changed file and fix what it reports. "
    + "Use vortex_search_symbols / vortex_find_references / vortex_get_dependencies instead of reading whole files.";

  private final SonarServer server;
  private final String projectKey;
  private final BranchResolver branches;
  private final CodeIndex index;
  private final Gson gson = new Gson();

  public ContextService(SonarServer server, String projectKey, BranchResolver branches, CodeIndex index) {
    this.server = server;
    this.projectKey = projectKey;
    this.branches = branches;
    this.index = index;
  }

  public JsonObject context(String branch, String pullRequest) {
    var scope = branches.resolve(branch, pullRequest);
    var context = serverContext(scope);
    context.add("resolvedScope", gson.toJsonTree(scope));
    context.add("workspace", workspaceMap());
    context.addProperty("workflow", WORKFLOW);
    return context;
  }

  private JsonObject serverContext(BranchResolver.Scope scope) {
    var params = Params.of("project", projectKey, "branch", scope.branch(), "pullRequest", scope.pullRequest());
    try {
      return server.get("api/vortex/context", params);
    } catch (SonarServerException e) {
      if (e.status() != 404 || e.getMessage() == null || !e.getMessage().contains("Unknown url")) {
        throw e;
      }
    }
    // Plugin missing: fall back to the built-in web services
    var context = new JsonObject();
    var warnings = new JsonArray();
    warnings.add("The Community Vortex plugin is not installed on the SonarQube server: policies, quality gate and size are not available");
    context.add("warnings", warnings);
    context.add("qualityProfiles", server.get("api/qualityprofiles/search", Params.of("project", projectKey)).get("profiles"));
    return context;
  }

  private JsonObject workspaceMap() {
    index.refresh();
    var json = new JsonObject();
    json.add("indexedFilesByLanguage", gson.toJsonTree(index.languageCounts()));
    var modules = new JsonArray();
    for (var module : index.moduleMap(moduleDepth(), MODULE_LIMIT)) {
      modules.add(gson.toJsonTree(module));
    }
    json.add("modules", modules);
    return json;
  }

  /** Java packages need more segments ({@code com.acme.shop.order}) than paths ({@code src/order}). */
  private int moduleDepth() {
    var counts = index.languageCounts();
    return counts.getOrDefault("java", 0) >= counts.values().stream().mapToInt(Integer::intValue).sum() / 2 ? 4 : 2;
  }
}
