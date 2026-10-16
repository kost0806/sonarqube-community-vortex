package org.communityvortex.mcp.sonar;

import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.Map;

/** The subset of the SonarQube Web API used by the MCP server. */
public interface SonarServer {

  JsonObject get(String path, Map<String, String> params);

  void download(String path, Map<String, String> params, Path target);
}
