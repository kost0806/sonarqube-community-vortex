package org.communityvortex.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AnalysisRequestTest {

  private static String request(String path) {
    return "{\"projectKey\":\"acme\",\"files\":[{\"path\":\"" + path + "\",\"content\":\"x = 1\\n\"}]}";
  }

  @Test
  void parsesTheCliRequestFormat() {
    var parsed = AnalysisRequest.parse("""
      {"projectKey":"acme","branchName":"feature/x","analysisDepth":"DEEP",
       "files":[{"path":"src/a.py","content":"x = 1\\n","scope":"MAIN"},{"path":"tests/test_a.py","content":"","scope":"TEST"}]}""", 10);

    assertThat(parsed.projectKey()).isEqualTo("acme");
    assertThat(parsed.branchName()).isEqualTo("feature/x");
    assertThat(parsed.files()).extracting(AnalysisRequest.File::path).containsExactly("src/a.py", "tests/test_a.py");
    assertThat(parsed.files().get(0).content()).isEqualTo("x = 1\n");
  }

  @ParameterizedTest
  @ValueSource(strings = {"/etc/passwd", "C:/Windows/x.py", "../x.py", "a/../../x.py", "a\\\\b.py", "./a.py", "a//b.py", ""})
  void rejectsUnsafePaths(String path) {
    assertThatThrownBy(() -> AnalysisRequest.parse(request(path), 10))
      .isInstanceOfSatisfying(RequestException.class, e -> assertThat(e.status()).isEqualTo(400));
  }

  @Test
  void rejectsMalformedRequests() {
    assertThatThrownBy(() -> AnalysisRequest.parse("not json", 10)).hasMessageContaining("JSON");
    assertThatThrownBy(() -> AnalysisRequest.parse("{\"files\":[]}", 10)).hasMessageContaining("projectKey");
    assertThatThrownBy(() -> AnalysisRequest.parse("{\"projectKey\":\"a\",\"files\":[]}", 10)).hasMessageContaining("files");
    assertThatThrownBy(() -> AnalysisRequest.parse("{\"projectKey\":\"a\",\"files\":[{\"path\":\"a.py\"}]}", 10))
      .hasMessageContaining("content");
    assertThatThrownBy(() -> AnalysisRequest.parse(
      "{\"projectKey\":\"a\",\"files\":[{\"path\":\"a.py\",\"content\":\"\"},{\"path\":\"a.py\",\"content\":\"\"}]}", 10))
      .hasMessageContaining("Duplicate");
  }

  @Test
  void tooManyFilesTellsTheCliHowToSplit() {
    var body = "{\"projectKey\":\"a\",\"files\":[{\"path\":\"a.py\",\"content\":\"\"},{\"path\":\"b.py\",\"content\":\"\"}]}";

    assertThatThrownBy(() -> AnalysisRequest.parse(body, 1))
      .isInstanceOfSatisfying(RequestException.class, e -> {
        assertThat(e.status()).isEqualTo(413);
        assertThat(e.toJson().getAsJsonObject("meta").get("maxFiles").getAsInt()).isEqualTo(1);
      });
  }
}
