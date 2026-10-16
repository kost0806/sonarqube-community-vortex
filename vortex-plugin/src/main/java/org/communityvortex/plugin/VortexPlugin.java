package org.communityvortex.plugin;

import org.communityvortex.plugin.a3s.A3sCompatibilityFilter;
import org.communityvortex.plugin.ws.VortexWebService;
import org.sonar.api.Plugin;
import org.sonar.api.SonarQubeSide;

public class VortexPlugin implements Plugin {

  @Override
  public void define(Context context) {
    context.addExtensions(VortexProperties.definitions());
    if (context.getRuntime().getSonarQubeSide() == SonarQubeSide.SERVER) {
      context.addExtensions(VortexWebService.class, A3sCompatibilityFilter.class);
    }
  }
}
