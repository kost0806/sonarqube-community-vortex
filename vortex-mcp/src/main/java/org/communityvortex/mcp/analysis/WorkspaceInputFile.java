package org.communityvortex.mcp.analysis;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Pattern;
import org.sonarsource.sonarlint.core.analysis.api.ClientInputFile;
import org.sonarsource.sonarlint.core.commons.api.SonarLanguage;

/** A workspace file handed to the analysis engine. */
final class WorkspaceInputFile implements ClientInputFile {

  private static final Pattern TEST_PATH = Pattern.compile(
    "(^|/)(src/test|tests?|__tests__|spec)/|(Test|Tests|IT)\\.(java|kt)$|\\.(test|spec)\\.[jt]sx?$|(^|/)test_[^/]+\\.py$|_test\\.(py|go)$");

  private final Path path;
  private final String relativePath;
  private final SonarLanguage language;

  WorkspaceInputFile(Path path, String relativePath, SonarLanguage language) {
    this.path = path;
    this.relativePath = relativePath;
    this.language = language;
  }

  static boolean looksLikeTest(String relativePath) {
    return TEST_PATH.matcher(relativePath.replace('\\', '/')).find();
  }

  @Override
  public String getPath() {
    return path.toString();
  }

  @Override
  public boolean isTest() {
    return looksLikeTest(relativePath);
  }

  @Override
  public Charset getCharset() {
    return StandardCharsets.UTF_8;
  }

  @Override
  public SonarLanguage language() {
    return language;
  }

  @SuppressWarnings("unchecked")
  @Override
  public <G> G getClientObject() {
    return (G) path;
  }

  @Override
  public InputStream inputStream() throws IOException {
    return Files.newInputStream(path);
  }

  @Override
  public String contents() throws IOException {
    return Files.readString(path, StandardCharsets.UTF_8);
  }

  @Override
  public String relativePath() {
    return relativePath;
  }

  @Override
  public URI uri() {
    return path.toUri();
  }

  @Override
  public String toString() {
    return relativePath;
  }

  /** Language from the default file suffixes of the analyzers (server-side custom suffixes are not applied). */
  static SonarLanguage detectLanguage(String fileName) {
    var lower = fileName.toLowerCase(Locale.ROOT);
    SonarLanguage best = null;
    var bestLength = 0;
    for (var language : SonarLanguage.values()) {
      for (var suffix : language.getDefaultFileSuffixes()) {
        var normalized = suffix.toLowerCase(Locale.ROOT);
        if (!normalized.isEmpty() && lower.endsWith(normalized) && normalized.length() > bestLength) {
          best = language;
          bestLength = normalized.length();
        }
      }
    }
    return best;
  }
}
