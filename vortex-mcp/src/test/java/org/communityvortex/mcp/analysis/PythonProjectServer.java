package org.communityvortex.mcp.analysis;

import java.nio.file.Files;
import java.nio.file.Path;
import org.communityvortex.mcp.FakeSonar;

/** A fake server with one Python quality profile and the real Python analyzer from Maven Central. */
public final class PythonProjectServer {

  public static final Path ANALYZER = Path.of("target/test-analyzers/sonar-python-plugin.jar");

  private PythonProjectServer() {
  }

  public static FakeSonar create() {
    if (!Files.isRegularFile(ANALYZER)) {
      throw new IllegalStateException("Run through Maven: " + ANALYZER + " is copied in generate-test-resources");
    }
    return new FakeSonar()
      .reply("api/qualityprofiles/search", """
        {"profiles":[{"key":"py-profile","name":"Sonar way","language":"py","languageName":"Python","activeRuleCount":2}]}""")
      .reply("api/rules/search", """
        {"total":2,"p":1,"ps":500,
         "rules":[
           {"key":"python:S139","repo":"python","name":"Comments should not be located at the end of lines of code","severity":"MINOR","lang":"py","type":"CODE_SMELL"},
           {"key":"python:S1481","repo":"python","name":"Unused local variables should be removed","severity":"MINOR","lang":"py","type":"CODE_SMELL"}],
         "actives":{
           "python:S139":[{"qProfile":"py-profile","inherit":"NONE","severity":"INFO","params":[{"key":"legalTrailingCommentPattern","value":"^#\\\\s*+[^\\\\s]++$"}]}],
           "python:S1481":[{"qProfile":"py-profile","inherit":"NONE","severity":"MAJOR","params":[]}]}}""")
      .reply("api/plugins/installed", """
        {"plugins":[
          {"key":"python","name":"Python Code Quality and Security","filename":"sonar-python-plugin-5.32.0.37250.jar","hash":"abc123","sonarLintSupported":true},
          {"key":"communityvortex","name":"Community Vortex","filename":"vortex-plugin.jar","hash":"def456","sonarLintSupported":false}]}""")
      .serveFile("python", ANALYZER);
  }
}
