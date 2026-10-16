package org.communityvortex.plugin.ws;

import org.sonar.api.server.ws.WebService;

/** Registers {@code api/vortex/*}. */
public class VortexWebService implements WebService {

  public static final String CONTROLLER = "api/vortex";

  private final ContextAction contextAction = new ContextAction();
  private final PolicyAction policyAction = new PolicyAction();

  @Override
  public void define(Context context) {
    var controller = context.createController(CONTROLLER)
      .setDescription("Community Vortex (unofficial): guidance for coding agents working on a project.")
      .setSince("0.1");
    contextAction.define(controller);
    policyAction.define(controller);
    controller.done();
  }
}
