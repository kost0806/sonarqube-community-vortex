package org.communityvortex.plugin.ws;

import com.google.gson.JsonObject;
import org.sonar.api.server.ws.Request;
import org.sonar.api.server.ws.RequestHandler;
import org.sonar.api.server.ws.Response;
import org.sonar.api.server.ws.WebService.NewController;

/** {@code GET api/vortex/policy}: the project's architecture, dependency and coding policies only (cheap, for verification loops). */
public class PolicyAction implements RequestHandler {

  static final String ACTION = "policy";
  static final String PARAM_PROJECT = "project";

  void define(NewController controller) {
    var action = controller.createAction(ACTION)
      .setDescription("Architecture rules, dependency allow/deny lists and coding guidelines of a project. "
        + "Requires 'Browse' permission on the project.")
      .setSince("0.1")
      .setResponseExample(getClass().getResource("policy-example.json"))
      .setHandler(this);
    action.createParam(PARAM_PROJECT)
      .setDescription("Project key")
      .setRequired(true)
      .setExampleValue("my_project");
  }

  @Override
  public void handle(Request request, Response response) {
    var projectKey = request.mandatoryParam(PARAM_PROJECT);
    var api = new SonarApi(request.localConnector());
    JsonResponses.write(response, () -> {
      // fail with the platform's own 403/404 when the caller cannot browse the project
      api.get("api/components/show", SonarApi.params("component", projectKey));
      var json = new JsonObject();
      json.addProperty("project", projectKey);
      json.add("policy", PolicyReader.read(api, projectKey));
      return json;
    });
  }
}
