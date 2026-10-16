package org.communityvortex.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.LongSupplier;
import org.communityvortex.mcp.analysis.Analyzer;
import org.communityvortex.mcp.analysis.LocalAnalyzer;
import org.communityvortex.mcp.analysis.PluginCache;
import org.communityvortex.mcp.analysis.RuleRepository;
import org.communityvortex.mcp.analysis.RuleSource;
import org.communityvortex.mcp.analysis.ServerActiveRule;
import org.communityvortex.mcp.index.CodeIndex;
import org.communityvortex.mcp.sonar.Params;
import org.communityvortex.mcp.sonar.SonarServer;
import org.communityvortex.mcp.sonar.SonarServerException;
import org.communityvortex.mcp.verify.PolicySource;
import org.communityvortex.mcp.verify.Verifier;
import org.communityvortex.mcp.workspace.Workspace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Answers SonarQube CLI analysis requests: checks the caller's own access to the project on SonarQube,
 * writes the posted files to an isolated temporary workspace, verifies them with the project's analyzers,
 * rules and Community Vortex policies, and returns issues in the CLI's wire format.
 */
public final class A3sService implements VortexHttpServer.Handler {

  public static final String ARCHITECTURE_RULE = "vortex:architecture";
  public static final String DEPENDENCY_RULE = "vortex:dependency";
  static final long RULES_TTL_NANOS = 5L * 60 * 1_000_000_000L;
  private static final Logger LOG = LoggerFactory.getLogger(A3sService.class);

  private record RulesSnapshot(long loadedAt, Map<String, List<ServerActiveRule>> byLanguage) {
  }

  private final Function<String, SonarServer> servers;
  private final LocalAnalyzer analyzer;
  private final Path cacheDir;
  private final Path requestsDir;
  private final int maxFiles;
  private final LongSupplier clock;
  private final Map<String, RulesSnapshot> rules = new ConcurrentHashMap<>();

  public A3sService(Function<String, SonarServer> servers, LocalAnalyzer analyzer, Path cacheDir, int maxFiles) {
    this(servers, analyzer, cacheDir, maxFiles, System::nanoTime);
  }

  A3sService(Function<String, SonarServer> servers, LocalAnalyzer analyzer, Path cacheDir, int maxFiles, LongSupplier clock) {
    this.servers = servers;
    this.analyzer = analyzer;
    this.cacheDir = cacheDir;
    this.requestsDir = cacheDir.resolve("requests");
    this.maxFiles = maxFiles;
    this.clock = clock;
  }

  /** {@code POST /analyses}. */
  @Override
  public ApiResponse analyses(String authorization, String body) {
    try {
      var token = bearerToken(authorization);
      var request = AnalysisRequest.parse(body, maxFiles);
      var server = servers.apply(token);
      checkAccess(server, request.projectKey());
      return ApiResponse.ok(analyse(server, request));
    } catch (RequestException e) {
      return ApiResponse.of(e);
    } catch (SonarServerException e) {
      return ApiResponse.of(upstream(e));
    } catch (IllegalArgumentException e) {
      return ApiResponse.error(400, e.getMessage());
    } catch (RuntimeException e) {
      LOG.error("Analysis request failed", e);
      return ApiResponse.error(500, "Analysis failed");
    }
  }

  /** {@code GET /entitlement}: this service provides the analysis to any user SonarQube authenticates. */
  @Override
  public ApiResponse entitlement(String authorization) {
    try {
      var token = bearerToken(authorization);
      var valid = servers.apply(token).get("api/authentication/validate", Params.of()).get("valid").getAsBoolean();
      if (!valid) {
        return ApiResponse.error(401, "Authentication required");
      }
      var json = new JsonObject();
      json.addProperty("allowed", true);
      json.addProperty("hasEntitlement", true);
      return ApiResponse.ok(json);
    } catch (RequestException e) {
      return ApiResponse.of(e);
    } catch (SonarServerException e) {
      return ApiResponse.of(upstream(e));
    }
  }

  private JsonObject analyse(SonarServer server, AnalysisRequest request) {
    var root = requestsDir.resolve(UUID.randomUUID().toString());
    try {
      var paths = writeFiles(root, request.files());
      var workspace = new Workspace(root);
      var ruleSource = rulesFor(server, request.projectKey());
      var plugins = new PluginCache(server, cacheDir);
      Analyzer bound = files -> analyzer.analyze(workspace, files, ruleSource, plugins);
      var verifier = new Verifier(workspace, bound, new CodeIndex(workspace), new PolicySource(server, request.projectKey()), maxFiles);
      return WireFormat.toResponse(verifier.verify(paths));
    } finally {
      deleteRecursively(root);
    }
  }

  private RuleSource rulesFor(SonarServer server, String projectKey) {
    var now = clock.getAsLong();
    var snapshot = rules.compute(projectKey, (key, current) ->
      current != null && now - current.loadedAt() <= RULES_TTL_NANOS
        ? current
        : new RulesSnapshot(now, new RuleRepository(server, key).all()));
    return language -> snapshot.byLanguage().getOrDefault(language, List.of());
  }

  private static void checkAccess(SonarServer server, String projectKey) {
    try {
      server.get("api/components/show", Params.of("component", projectKey));
    } catch (SonarServerException e) {
      throw upstream(e);
    }
  }

  private static RequestException upstream(SonarServerException e) {
    return switch (e.status()) {
      case 401 -> new RequestException(401, "Authentication required");
      case 403 -> new RequestException(403, "Insufficient privileges for this project");
      case 404 -> new RequestException(404, "Project not found");
      default -> {
        LOG.warn("SonarQube call failed: {}", e.getMessage());
        yield new RequestException(502, "SonarQube is unavailable");
      }
    };
  }

  private static String bearerToken(String authorization) {
    if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7) || authorization.length() <= 7) {
      throw new RequestException(401, "Authentication required");
    }
    return authorization.substring(7).trim();
  }

  private static List<String> writeFiles(Path root, List<AnalysisRequest.File> files) {
    try {
      Files.createDirectories(root);
      var paths = new java.util.ArrayList<String>();
      for (var file : files) {
        var target = root.resolve(file.path()).normalize();
        if (!target.startsWith(root)) {
          throw new RequestException(400, "File path escapes the workspace: '" + file.path() + "'");
        }
        Files.createDirectories(target.getParent());
        Files.writeString(target, file.content(), StandardCharsets.UTF_8);
        paths.add(file.path());
      }
      return paths;
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot write request files", e);
    }
  }

  private static void deleteRecursively(Path root) {
    if (!Files.exists(root)) {
      return;
    }
    try (var walk = Files.walk(root)) {
      walk.sorted(Comparator.reverseOrder()).forEach(path -> {
        try {
          Files.deleteIfExists(path);
        } catch (IOException e) {
          LOG.debug("Cannot delete {}", path);
        }
      });
    } catch (IOException e) {
      LOG.debug("Cannot clean up {}", root);
    }
  }

  /** Maps a verification report onto the response the SonarQube CLI expects. */
  static final class WireFormat {

    private WireFormat() {
    }

    static JsonObject toResponse(Verifier.Report report) {
      var issues = new JsonArray();
      for (var finding : report.issues()) {
        issues.add(issue(finding.file(), finding.ruleKey(), finding.message(),
          range(finding.line(), finding.endLine(), finding.startOffset(), finding.endOffset())));
      }
      for (var violation : report.architectureViolations()) {
        issues.add(issue(violation.file(), ARCHITECTURE_RULE, violation.message(), lineRange(violation.line())));
      }
      for (var violation : report.dependencyViolations()) {
        issues.add(issue(violation.file(), DEPENDENCY_RULE, violation.message(), lineRange(violation.line())));
      }
      var errors = new JsonArray();
      for (var failed : report.failedFiles()) {
        var error = new JsonObject();
        error.addProperty("code", "ANALYSIS_FAILED");
        error.addProperty("message", "Analysis failed for '" + failed + "'");
        errors.add(error);
      }
      var json = new JsonObject();
      json.addProperty("id", UUID.randomUUID().toString());
      json.add("issues", issues);
      json.add("errors", errors);
      return json;
    }

    private static JsonObject issue(String file, String rule, String message, JsonObject range) {
      var json = new JsonObject();
      json.addProperty("id", UUID.randomUUID().toString());
      json.addProperty("filePath", file);
      json.addProperty("message", message);
      json.addProperty("rule", rule);
      json.add("textRange", range);
      json.add("flows", new JsonArray());
      return json;
    }

    private static JsonObject lineRange(int line) {
      return line > 0 ? range(line, line, 0, 0) : null;
    }

    private static JsonObject range(int startLine, int endLine, int startOffset, int endOffset) {
      if (startLine <= 0) {
        return null;
      }
      var json = new JsonObject();
      json.addProperty("startLine", startLine);
      json.addProperty("endLine", endLine);
      json.addProperty("startOffset", startOffset);
      json.addProperty("endOffset", endOffset);
      return json;
    }
  }
}
