package org.communityvortex.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.communityvortex.plugin.ws.VortexWebService;
import org.junit.jupiter.api.Test;
import org.sonar.api.Plugin;
import org.sonar.api.SonarQubeSide;
import org.sonar.api.SonarRuntime;
import org.sonar.api.config.PropertyDefinition;
import org.sonar.api.server.ws.WebService;

class VortexPluginTest {

  private static Plugin.Context contextFor(SonarQubeSide side) {
    var runtime = mock(SonarRuntime.class);
    when(runtime.getSonarQubeSide()).thenReturn(side);
    return new Plugin.Context(runtime);
  }

  @Test
  void serverSideRegistersSettingsAndWebService() {
    var context = contextFor(SonarQubeSide.SERVER);

    new VortexPlugin().define(context);

    assertThat(context.getExtensions()).contains(VortexWebService.class);
    assertThat(context.getExtensions()).filteredOn(PropertyDefinition.class::isInstance).hasSize(4);
  }

  @Test
  void scannerSideOnlyRegistersSettings() {
    var context = contextFor(SonarQubeSide.SCANNER);

    new VortexPlugin().define(context);

    assertThat(context.getExtensions()).doesNotContain(VortexWebService.class).hasSize(4);
  }

  @Test
  void settingsAreAvailableOnProjects() {
    assertThat(VortexProperties.definitions())
      .allSatisfy(d -> {
        assertThat(d.category()).isEqualTo("Vortex (Community)");
        assertThat(d.configScopes()).contains(PropertyDefinition.ConfigScope.PROJECT);
      })
      .extracting(PropertyDefinition::key)
      .containsExactlyElementsOf(VortexProperties.ALL_KEYS);
  }

  @Test
  void webServiceDefinesContextAndPolicyActions() {
    var context = new WebService.Context();

    new VortexWebService().define(context);

    var controller = context.controller("api/vortex");
    assertThat(controller.actions()).extracting(WebService.Action::key).containsExactlyInAnyOrder("context", "policy");
    var contextAction = controller.action("context");
    assertThat(contextAction.isPost()).isFalse();
    assertThat(contextAction.responseExampleAsString()).contains("\"qualityGate\"");
    assertThat(contextAction.param("project").isRequired()).isTrue();
    assertThat(contextAction.param("branch")).isNotNull();
    assertThat(contextAction.param("pullRequest")).isNotNull();
    assertThat(controller.action("policy").responseExampleAsString()).contains("\"policy\"");
  }
}
