package org.communityvortex.plugin.ws;

import static org.communityvortex.plugin.ws.SonarApi.params;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.communityvortex.plugin.VortexProperties;
import org.communityvortex.policy.ArchitectureRules;
import org.communityvortex.policy.DependencyPolicy;

/** Reads the project's agent policies from its effective settings (project value, else global, else default). */
final class PolicyReader {

  private PolicyReader() {
  }

  static JsonObject read(SonarApi api, String projectKey) {
    var settings = api.get("api/settings/values",
      params("component", projectKey, "keys", String.join(",", VortexProperties.ALL_KEYS)));
    var byKey = indexByKey(settings.getAsJsonArray("settings"));

    var parsedRules = ArchitectureRules.parse(single(byKey.get(VortexProperties.ARCHITECTURE_RULES)));
    var dependencyPolicy = DependencyPolicy.of(
      multi(byKey.get(VortexProperties.DENIED_DEPENDENCIES)),
      multi(byKey.get(VortexProperties.ALLOWED_DEPENDENCIES)));

    var policy = new JsonObject();
    var rules = new JsonArray();
    for (var rule : parsedRules.rules()) {
      var json = new JsonObject();
      json.addProperty("line", rule.line());
      json.addProperty("from", rule.from().text());
      json.addProperty("to", rule.to().text());
      json.addProperty("text", rule.text());
      rules.add(json);
    }
    policy.add("architectureRules", rules);
    policy.add("architectureRuleErrors", toArray(parsedRules.errors()));
    policy.add("deniedDependencies", toArray(dependencyPolicy.deniedPatterns()));
    policy.add("allowedDependencies", toArray(dependencyPolicy.allowedPatterns()));
    policy.addProperty("guidelines", single(byKey.get(VortexProperties.GUIDELINES)));
    return policy;
  }

  private static Map<String, JsonObject> indexByKey(JsonArray settings) {
    var byKey = new HashMap<String, JsonObject>();
    if (settings != null) {
      for (JsonElement element : settings) {
        var setting = element.getAsJsonObject();
        byKey.put(setting.get("key").getAsString(), setting);
      }
    }
    return byKey;
  }

  private static String single(JsonObject setting) {
    return setting == null || !setting.has("value") ? null : setting.get("value").getAsString();
  }

  private static List<String> multi(JsonObject setting) {
    var values = new ArrayList<String>();
    if (setting == null) {
      return values;
    }
    if (setting.has("values")) {
      setting.getAsJsonArray("values").forEach(v -> values.add(v.getAsString()));
    } else if (setting.has("value")) {
      // a multi-value setting stored as one comma separated value
      for (var value : setting.get("value").getAsString().split(",")) {
        values.add(value.trim());
      }
    }
    return values;
  }

  private static JsonArray toArray(List<String> values) {
    var array = new JsonArray();
    values.forEach(array::add);
    return array;
  }
}
