package org.communityvortex.mcp.analysis;

import java.util.Map;
import org.sonar.api.batch.rule.ActiveRule;
import org.sonar.api.rule.RuleKey;

/** A rule activated in one of the project's quality profiles on the server. */
public record ServerActiveRule(RuleKey ruleKey, String name, String language, String severity, String type,
  Map<String, String> params, String internalKey, String templateRuleKey, String qpKey) implements ActiveRule {

  public ServerActiveRule {
    params = Map.copyOf(params);
  }

  @Override
  public String param(String key) {
    return params.get(key);
  }
}
