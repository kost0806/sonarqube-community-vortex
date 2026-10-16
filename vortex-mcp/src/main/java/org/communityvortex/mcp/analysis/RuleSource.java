package org.communityvortex.mcp.analysis;

import java.util.List;

/** Active rules of a project, by SonarQube language key ({@code java}, {@code py}, ...). */
@FunctionalInterface
public interface RuleSource {

  List<ServerActiveRule> forLanguage(String languageKey);
}
