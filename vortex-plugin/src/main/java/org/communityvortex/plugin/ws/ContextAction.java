package org.communityvortex.plugin.ws;

import static org.communityvortex.plugin.ws.SonarApi.params;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.function.Supplier;
import org.sonar.api.server.ws.Request;
import org.sonar.api.server.ws.RequestHandler;
import org.sonar.api.server.ws.Response;
import org.sonar.api.server.ws.WebService.NewController;

/**
 * {@code GET api/vortex/context}: everything a coding agent should know before writing code in a project,
 * in one call. Optional sections that cannot be computed (e.g. a never-analysed branch) are reported
 * under {@code warnings} instead of failing the request.
 */
public class ContextAction implements RequestHandler {

  static final String ACTION = "context";
  static final String PARAM_PROJECT = "project";
  private static final int TOP_DIRECTORIES = 20;

  void define(NewController controller) {
    var action = controller.createAction(ACTION)
      .setDescription("Agent context for a project: quality profiles, quality gate and its status, size, "
        + "largest directories, branches and pull requests, and the architecture, dependency and coding policies. "
        + "Requires 'Browse' permission on the project.")
      .setSince("0.1")
      .setResponseExample(getClass().getResource("context-example.json"))
      .setHandler(this);
    action.createParam(PARAM_PROJECT)
      .setDescription("Project key")
      .setRequired(true)
      .setExampleValue("my_project");
    BranchScope.defineParams(action);
  }

  @Override
  public void handle(Request request, Response response) {
    var projectKey = request.mandatoryParam(PARAM_PROJECT);
    var scope = BranchScope.from(request);
    var api = new SonarApi(request.localConnector());
    JsonResponses.write(response, () -> build(api, projectKey, scope));
  }

  JsonObject build(SonarApi api, String projectKey, BranchScope scope) {
    var warnings = new JsonArray();
    var component = api.get("api/components/show", scope.apply(params("component", projectKey))).getAsJsonObject("component");

    var json = new JsonObject();
    var project = new JsonObject();
    project.addProperty("key", projectKey);
    project.addProperty("name", component.get("name").getAsString());
    json.add("project", project);
    json.add("scope", scope.toJson());
    json.add("branches", optional(warnings, "branches", () -> branches(api, projectKey)));
    json.add("pullRequests", pullRequests(api, projectKey));
    json.add("qualityProfiles", optional(warnings, "quality profiles", () -> qualityProfiles(api, projectKey)));
    json.add("qualityGate", optional(warnings, "quality gate", () -> qualityGate(api, projectKey, scope)));
    json.add("size", optional(warnings, "size measures", () -> size(api, projectKey, scope)));
    json.add("topDirectories", optional(warnings, "directory measures", () -> topDirectories(api, projectKey, scope)));
    json.add("policy", PolicyReader.read(api, projectKey));
    json.add("warnings", warnings);
    return json;
  }

  private static JsonElement branches(SonarApi api, String projectKey) {
    var result = new JsonArray();
    for (var element : api.get("api/project_branches/list", params("project", projectKey)).getAsJsonArray("branches")) {
      var branch = element.getAsJsonObject();
      var json = new JsonObject();
      json.addProperty("name", branch.get("name").getAsString());
      json.addProperty("isMain", branch.has("isMain") && branch.get("isMain").getAsBoolean());
      json.addProperty("type", stringOrNull(branch, "type"));
      result.add(json);
    }
    return result;
  }

  private static JsonElement pullRequests(SonarApi api, String projectKey) {
    var result = new JsonArray();
    try {
      for (var element : api.get("api/project_pull_requests/list", params("project", projectKey)).getAsJsonArray("pullRequests")) {
        var pr = element.getAsJsonObject();
        var json = new JsonObject();
        json.addProperty("key", pr.get("key").getAsString());
        json.addProperty("title", stringOrNull(pr, "title"));
        json.addProperty("branch", stringOrNull(pr, "branch"));
        json.addProperty("base", stringOrNull(pr, "base"));
        result.add(json);
      }
    } catch (SonarApiException e) {
      // Without pull request support this service is missing or empty: nothing to report.
    }
    return result;
  }

  private static JsonElement qualityProfiles(SonarApi api, String projectKey) {
    var result = new JsonArray();
    for (var element : api.get("api/qualityprofiles/search", params("project", projectKey)).getAsJsonArray("profiles")) {
      var profile = element.getAsJsonObject();
      var json = new JsonObject();
      json.addProperty("key", profile.get("key").getAsString());
      json.addProperty("name", profile.get("name").getAsString());
      json.addProperty("language", profile.get("language").getAsString());
      json.addProperty("languageName", stringOrNull(profile, "languageName"));
      json.addProperty("activeRuleCount", profile.has("activeRuleCount") ? profile.get("activeRuleCount").getAsInt() : 0);
      result.add(json);
    }
    return result;
  }

  private static JsonElement qualityGate(SonarApi api, String projectKey, BranchScope scope) {
    var gateName = api.get("api/qualitygates/get_by_project", params("project", projectKey))
      .getAsJsonObject("qualityGate").get("name").getAsString();
    var gate = api.get("api/qualitygates/show", params("name", gateName));

    var json = new JsonObject();
    json.addProperty("name", gateName);
    var conditions = new JsonArray();
    var definedConditions = gate.getAsJsonArray("conditions");
    if (definedConditions != null) {
      for (var element : definedConditions) {
        var condition = element.getAsJsonObject();
        var c = new JsonObject();
        c.addProperty("metric", condition.get("metric").getAsString());
        c.addProperty("op", stringOrNull(condition, "op"));
        c.addProperty("error", stringOrNull(condition, "error"));
        conditions.add(c);
      }
    }
    json.add("conditions", conditions);

    var status = api.get("api/qualitygates/project_status", scope.apply(params("projectKey", projectKey)))
      .getAsJsonObject("projectStatus");
    json.addProperty("status", stringOrNull(status, "status"));
    return json;
  }

  private static JsonElement size(SonarApi api, String projectKey, BranchScope scope) {
    var measures = api.get("api/measures/component",
      scope.apply(params("component", projectKey, "metricKeys", "ncloc,files,ncloc_language_distribution")))
      .getAsJsonObject("component").getAsJsonArray("measures");
    var json = new JsonObject();
    var languages = new JsonObject();
    for (var element : measures) {
      var measure = element.getAsJsonObject();
      var metric = measure.get("metric").getAsString();
      var value = stringOrNull(measure, "value");
      if (value == null) {
        continue;
      }
      if ("ncloc_language_distribution".equals(metric)) {
        for (var pair : value.split(";")) {
          var parts = pair.split("=", 2);
          if (parts.length == 2) {
            languages.addProperty(parts[0], Long.parseLong(parts[1]));
          }
        }
      } else {
        json.addProperty(metric, Long.parseLong(value));
      }
    }
    json.add("nclocByLanguage", languages);
    return json;
  }

  private static JsonElement topDirectories(SonarApi api, String projectKey, BranchScope scope) {
    var tree = api.get("api/measures/component_tree", scope.apply(params(
      "component", projectKey, "metricKeys", "ncloc", "qualifiers", "DIR", "strategy", "all",
      "s", "metric", "metricSort", "ncloc", "asc", "false", "ps", String.valueOf(TOP_DIRECTORIES))));
    var result = new JsonArray();
    for (var element : tree.getAsJsonArray("components")) {
      var dir = element.getAsJsonObject();
      var json = new JsonObject();
      json.addProperty("path", stringOrNull(dir, "path"));
      var measures = dir.getAsJsonArray("measures");
      if (measures != null && !measures.isEmpty()) {
        json.addProperty("ncloc", Long.parseLong(measures.get(0).getAsJsonObject().get("value").getAsString()));
      }
      result.add(json);
    }
    return result;
  }

  private static JsonElement optional(JsonArray warnings, String label, Supplier<JsonElement> section) {
    try {
      return section.get();
    } catch (SonarApiException e) {
      warnings.add(label + " unavailable: " + e.getMessage());
      return null;
    }
  }

  private static String stringOrNull(JsonObject object, String member) {
    var value = object.get(member);
    return value == null || value.isJsonNull() ? null : value.getAsString();
  }
}
