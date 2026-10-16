package org.communityvortex.mcp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.communityvortex.mcp.sonar.SonarServer;
import org.communityvortex.mcp.sonar.SonarServerException;

/** In-memory {@link SonarServer} for tests: replies per path, optionally depending on the parameters. */
public final class FakeSonar implements SonarServer {

  public record Call(String path, Map<String, String> params) {
  }

  private final Map<String, Function<Map<String, String>, String>> replies = new HashMap<>();
  private final Map<String, SonarServerException> failures = new HashMap<>();
  private final Map<String, Path> downloads = new HashMap<>();
  private final List<Call> calls = new ArrayList<>();

  public FakeSonar reply(String path, String json) {
    return reply(path, params -> json);
  }

  public FakeSonar reply(String path, Function<Map<String, String>, String> json) {
    replies.put(path, json);
    failures.remove(path);
    return this;
  }

  public FakeSonar fail(String path, int status, String message) {
    failures.put(path, new SonarServerException(status, path + ": " + message));
    return this;
  }

  public FakeSonar serveFile(String plugin, Path file) {
    downloads.put(plugin, file);
    return this;
  }

  public List<Call> calls() {
    return calls;
  }

  public long count(String path) {
    return calls.stream().filter(c -> c.path().equals(path)).count();
  }

  @Override
  public JsonObject get(String path, Map<String, String> params) {
    calls.add(new Call(path, Map.copyOf(params)));
    if (failures.containsKey(path)) {
      throw failures.get(path);
    }
    var reply = replies.get(path);
    if (reply == null) {
      throw new SonarServerException(404, path + ": Unknown url : " + path);
    }
    return JsonParser.parseString(reply.apply(params)).getAsJsonObject();
  }

  @Override
  public void download(String path, Map<String, String> params, Path target) {
    calls.add(new Call(path, Map.copyOf(params)));
    var source = downloads.get(params.get("plugin"));
    if (source == null) {
      throw new SonarServerException(404, path + ": not found");
    }
    try {
      Files.createDirectories(target.getParent());
      // like HttpSonarServer: replaces an existing file
      Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
