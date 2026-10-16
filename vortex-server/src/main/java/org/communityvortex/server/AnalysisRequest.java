package org.communityvortex.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The analysis request the SonarQube CLI posts ({@code projectKey}, optional {@code branchName}, and
 * {@code files[]} with project-relative POSIX {@code path} and {@code content}). Paths are untrusted:
 * they are validated before anything is written to disk.
 */
public record AnalysisRequest(String projectKey, String branchName, List<File> files) {

  public record File(String path, String content) {
  }

  private static final Pattern ABSOLUTE = Pattern.compile("^(/|[A-Za-z]:)");
  private static final int MAX_PATH_LENGTH = 1024;
  private static final int MAX_KEY_LENGTH = 400;

  public AnalysisRequest {
    files = List.copyOf(files);
  }

  public static AnalysisRequest parse(String body, int maxFiles) {
    JsonObject json;
    try {
      json = JsonParser.parseString(body).getAsJsonObject();
    } catch (JsonParseException | IllegalStateException e) {
      throw new RequestException(400, "Request body must be a JSON object");
    }
    var projectKey = string(json.get("projectKey"));
    if (projectKey == null || projectKey.isBlank() || projectKey.length() > MAX_KEY_LENGTH) {
      throw new RequestException(400, "projectKey is required");
    }
    var filesJson = json.get("files");
    if (filesJson == null || !filesJson.isJsonArray() || filesJson.getAsJsonArray().isEmpty()) {
      throw new RequestException(400, "At least one file is required in files[]");
    }
    var array = filesJson.getAsJsonArray();
    if (array.size() > maxFiles) {
      var meta = new JsonObject();
      meta.addProperty("maxFiles", maxFiles);
      throw new RequestException(413, "Too many files in one request (maximum " + maxFiles + ")", meta);
    }
    var files = new ArrayList<File>();
    var seen = new HashSet<String>();
    for (JsonElement element : array) {
      if (!element.isJsonObject()) {
        throw new RequestException(400, "files[] entries must be objects");
      }
      var path = string(element.getAsJsonObject().get("path"));
      var content = string(element.getAsJsonObject().get("content"));
      validatePath(path);
      if (content == null) {
        throw new RequestException(400, "File content is required for '" + path + "'");
      }
      if (!seen.add(path)) {
        throw new RequestException(400, "Duplicate file path in request: '" + path + "'");
      }
      files.add(new File(path, content));
    }
    var branch = string(json.get("branchName"));
    return new AnalysisRequest(projectKey.trim(), branch, files);
  }

  private static void validatePath(String path) {
    if (path == null || path.isEmpty() || path.length() > MAX_PATH_LENGTH) {
      throw new RequestException(400, "File path must not be empty");
    }
    if (ABSOLUTE.matcher(path).find() || path.indexOf('\\') >= 0 || path.indexOf('\0') >= 0) {
      throw new RequestException(400, "File path must be a project-relative POSIX path: '" + path + "'");
    }
    for (var segment : path.split("/", -1)) {
      if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
        throw new RequestException(400, "File path must not contain empty, '.' or '..' segments: '" + path + "'");
      }
    }
  }

  private static String string(JsonElement element) {
    return element == null || element.isJsonNull() || !element.isJsonPrimitive() ? null : element.getAsString();
  }
}
