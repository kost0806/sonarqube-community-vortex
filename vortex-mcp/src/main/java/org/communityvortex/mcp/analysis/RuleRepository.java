package org.communityvortex.mcp.analysis;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import org.communityvortex.mcp.sonar.Params;
import org.communityvortex.mcp.sonar.SonarServer;
import org.sonar.api.rule.RuleKey;

/** Active rules of the project's quality profiles, fetched once and cached until {@link #invalidate()}. */
public final class RuleRepository implements RuleSource {

  static final int PAGE_SIZE = 500;
  // 'type' is no longer accepted by 26.x (validated against a real server in it/run_it.py); it is read when present
  private static final String FIELDS = "name,lang,severity,params,templateKey,internalKey,actives";

  /** Quality profile changes on the server are picked up after at most five minutes. */
  static final long TTL_NANOS = 5L * 60 * 1_000_000_000L;

  private final SonarServer server;
  private final String projectKey;
  private final LongSupplier clock;
  private Map<String, List<ServerActiveRule>> byLanguage;
  private long loadedAt;

  public RuleRepository(SonarServer server, String projectKey) {
    this(server, projectKey, System::nanoTime);
  }

  RuleRepository(SonarServer server, String projectKey, LongSupplier clock) {
    this.server = server;
    this.projectKey = projectKey;
    this.clock = clock;
  }

  @Override
  public synchronized List<ServerActiveRule> forLanguage(String languageKey) {
    return load().getOrDefault(languageKey, List.of());
  }

  public synchronized Map<String, List<ServerActiveRule>> all() {
    return load();
  }

  public synchronized void invalidate() {
    byLanguage = null;
  }

  private Map<String, List<ServerActiveRule>> load() {
    var now = clock.getAsLong();
    if (byLanguage == null || now - loadedAt > TTL_NANOS) {
      loadedAt = now;
      var loaded = new HashMap<String, List<ServerActiveRule>>();
      var profiles = server.get("api/qualityprofiles/search", Params.of("project", projectKey)).getAsJsonArray("profiles");
      for (JsonElement element : profiles) {
        var profile = element.getAsJsonObject();
        loaded.put(profile.get("language").getAsString(), List.copyOf(fetchProfile(profile.get("key").getAsString())));
      }
      byLanguage = Map.copyOf(loaded);
    }
    return byLanguage;
  }

  private List<ServerActiveRule> fetchProfile(String profileKey) {
    var rules = new ArrayList<ServerActiveRule>();
    for (int page = 1; ; page++) {
      var response = server.get("api/rules/search", Params.of(
        "qprofile", profileKey, "activation", "true", "f", FIELDS,
        "ps", String.valueOf(PAGE_SIZE), "p", String.valueOf(page)));
      var pageRules = response.getAsJsonArray("rules");
      var actives = response.getAsJsonObject("actives");
      for (JsonElement element : pageRules) {
        rules.add(toActiveRule(element.getAsJsonObject(), actives, profileKey));
      }
      if (pageRules.isEmpty() || (long) page * PAGE_SIZE >= total(response)) {
        return rules;
      }
    }
  }

  private static long total(JsonObject response) {
    if (response.has("paging")) {
      return response.getAsJsonObject("paging").get("total").getAsLong();
    }
    return response.has("total") ? response.get("total").getAsLong() : 0;
  }

  private static ServerActiveRule toActiveRule(JsonObject rule, JsonObject actives, String profileKey) {
    var key = rule.get("key").getAsString();
    var activation = activationFor(actives, key, profileKey);
    var params = new HashMap<String, String>();
    var severity = string(rule, "severity");
    if (activation != null) {
      severity = activation.has("severity") ? activation.get("severity").getAsString() : severity;
      var activeParams = activation.getAsJsonArray("params");
      if (activeParams != null) {
        for (JsonElement p : activeParams) {
          var param = p.getAsJsonObject();
          if (param.has("value")) {
            params.put(param.get("key").getAsString(), param.get("value").getAsString());
          }
        }
      }
    }
    return new ServerActiveRule(RuleKey.parse(key), string(rule, "name"), string(rule, "lang"), severity,
      string(rule, "type"), params, string(rule, "internalKey"), string(rule, "templateKey"), profileKey);
  }

  private static JsonObject activationFor(JsonObject actives, String ruleKey, String profileKey) {
    if (actives == null || !actives.has(ruleKey)) {
      return null;
    }
    JsonArray activations = actives.getAsJsonArray(ruleKey);
    for (JsonElement element : activations) {
      var activation = element.getAsJsonObject();
      if (profileKey.equals(string(activation, "qProfile"))) {
        return activation;
      }
    }
    return activations.isEmpty() ? null : activations.get(0).getAsJsonObject();
  }

  private static String string(JsonObject object, String member) {
    var value = object.get(member);
    return value == null || value.isJsonNull() ? null : value.getAsString();
  }
}
