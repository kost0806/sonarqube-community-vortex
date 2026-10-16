package org.communityvortex.plugin.ws;

import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;
import org.sonar.api.server.ws.Request;
import org.sonar.api.server.ws.WebService.NewAction;

/**
 * Optional {@code branch} / {@code pullRequest} selection. Community Build only has the main branch;
 * with mc1arke/sonarqube-community-branch-plugin installed these select analysed branches and PRs,
 * using the same parameter names as the built-in web services.
 */
public record BranchScope(String branch, String pullRequest) {

  static final String PARAM_BRANCH = "branch";
  static final String PARAM_PULL_REQUEST = "pullRequest";

  public BranchScope {
    if (branch != null && pullRequest != null) {
      throw new IllegalArgumentException("Only one of '" + PARAM_BRANCH + "' and '" + PARAM_PULL_REQUEST + "' can be set");
    }
  }

  public static BranchScope mainBranch() {
    return new BranchScope(null, null);
  }

  static void defineParams(NewAction action) {
    action.createParam(PARAM_BRANCH)
      .setDescription("Branch key. Requires branch support (e.g. the community branch plugin); defaults to the main branch.")
      .setExampleValue("feature/my-branch");
    action.createParam(PARAM_PULL_REQUEST)
      .setDescription("Pull request id. Requires pull request support (e.g. the community branch plugin).")
      .setExampleValue("42");
  }

  static BranchScope from(Request request) {
    return new BranchScope(emptyToNull(request.param(PARAM_BRANCH)), emptyToNull(request.param(PARAM_PULL_REQUEST)));
  }

  /** Returns a copy of {@code params} with the branch or pull request parameter added. */
  public Map<String, String> apply(Map<String, String> params) {
    var scoped = new LinkedHashMap<>(params);
    if (branch != null) {
      scoped.put(PARAM_BRANCH, branch);
    }
    if (pullRequest != null) {
      scoped.put(PARAM_PULL_REQUEST, pullRequest);
    }
    return scoped;
  }

  JsonObject toJson() {
    var json = new JsonObject();
    json.addProperty(PARAM_BRANCH, branch);
    json.addProperty(PARAM_PULL_REQUEST, pullRequest);
    json.addProperty("mainBranch", branch == null && pullRequest == null);
    return json;
  }

  private static String emptyToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
