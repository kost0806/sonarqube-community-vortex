package org.communityvortex.plugin;

import java.util.List;
import org.sonar.api.PropertyType;
import org.sonar.api.config.PropertyDefinition;
import org.sonar.api.config.PropertyDefinition.ConfigScope;

/** Settings shown under Administration (global) and Project Settings (per project) in the "Vortex" category. */
public final class VortexProperties {

  public static final String ARCHITECTURE_RULES = "sonar.vortex.architecture.rules";
  public static final String DENIED_DEPENDENCIES = "sonar.vortex.dependencies.denied";
  public static final String ALLOWED_DEPENDENCIES = "sonar.vortex.dependencies.allowed";
  public static final String GUIDELINES = "sonar.vortex.guidelines";

  public static final List<String> ALL_KEYS = List.of(ARCHITECTURE_RULES, DENIED_DEPENDENCIES, ALLOWED_DEPENDENCIES, GUIDELINES);

  /** Server-level: base URL of the Community Vortex analysis service that answers SonarQube CLI analysis requests. */
  public static final String ANALYSIS_URL = "sonar.vortex.analysis.url";

  static final String CATEGORY = "Vortex (Community)";
  private static final String SUB_CATEGORY_POLICIES = "Agent policies";

  private VortexProperties() {
  }

  public static List<PropertyDefinition> definitions() {
    return List.of(
      PropertyDefinition.builder(ARCHITECTURE_RULES)
        .name("Architecture rules")
        .description("One rule per line: <code>deny &lt;from&gt; -&gt; &lt;to&gt;[, &lt;to&gt;...]</code>. "
          + "Patterns are dotted package globs (<code>com.acme.domain.**</code>) or path globs (<code>src/ui/**</code>). "
          + "Lines starting with <code>#</code> are comments.")
        .type(PropertyType.TEXT)
        .category(CATEGORY)
        .subCategory(SUB_CATEGORY_POLICIES)
        .onConfigScopes(ConfigScope.PROJECT)
        .index(1)
        .build(),
      PropertyDefinition.builder(DENIED_DEPENDENCIES)
        .name("Denied dependencies")
        .description("Dependencies coding agents must not add, as <code>[ecosystem:]name-glob</code>, "
          + "e.g. <code>maven:log4j:log4j</code>, <code>npm:moment</code>, <code>pypi:pycrypto</code>.")
        .type(PropertyType.STRING)
        .multiValues(true)
        .category(CATEGORY)
        .subCategory(SUB_CATEGORY_POLICIES)
        .onConfigScopes(ConfigScope.PROJECT)
        .index(2)
        .build(),
      PropertyDefinition.builder(ALLOWED_DEPENDENCIES)
        .name("Allowed dependencies")
        .description("Optional allow list using the same syntax. When set, any other dependency is reported.")
        .type(PropertyType.STRING)
        .multiValues(true)
        .category(CATEGORY)
        .subCategory(SUB_CATEGORY_POLICIES)
        .onConfigScopes(ConfigScope.PROJECT)
        .index(3)
        .build(),
      PropertyDefinition.builder(GUIDELINES)
        .name("Coding guidelines for agents")
        .description("Free-form conventions handed to coding agents together with the project context.")
        .type(PropertyType.TEXT)
        .category(CATEGORY)
        .subCategory(SUB_CATEGORY_POLICIES)
        .onConfigScopes(ConfigScope.PROJECT)
        .index(4)
        .build(),
      PropertyDefinition.builder(ANALYSIS_URL)
        .name("Analysis service URL")
        .description("Base URL of the Community Vortex analysis service (vortex-server), e.g. <code>http://vortex-server:9100</code>. "
          + "When set, SonarQube CLI analysis requests (<code>sonar analyze</code>) are answered by that service. "
          + "Read at startup: set it in <code>sonar.properties</code> or as <code>SONAR_VORTEX_ANALYSIS_URL</code>.")
        .type(PropertyType.STRING)
        .category(CATEGORY)
        .subCategory("Analysis service")
        .index(5)
        .build());
  }
}
