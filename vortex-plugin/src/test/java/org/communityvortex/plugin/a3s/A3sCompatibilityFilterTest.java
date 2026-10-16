package org.communityvortex.plugin.a3s;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.communityvortex.plugin.VortexProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sonar.api.config.Configuration;
import org.sonar.api.server.http.Cookie;
import org.sonar.api.server.http.HttpRequest;
import org.sonar.api.server.http.HttpResponse;

class A3sCompatibilityFilterTest {

  private HttpServer service;
  private final AtomicReference<String> forwardedAuthorization = new AtomicReference<>();
  private final AtomicReference<String> forwardedBody = new AtomicReference<>();
  private final Map<String, String> settings = new HashMap<>();
  private boolean chained;

  @BeforeEach
  void startService() throws Exception {
    service = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    service.createContext("/analyses", exchange -> {
      forwardedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
      forwardedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      var bytes = "{\"id\":\"x\",\"issues\":[]}".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, bytes.length);
      exchange.getResponseBody().write(bytes);
      exchange.close();
    });
    service.createContext("/entitlement", exchange -> {
      forwardedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
      var bytes = "{\"allowed\":true}".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, bytes.length);
      exchange.getResponseBody().write(bytes);
      exchange.close();
    });
    service.start();
    settings.put(VortexProperties.ANALYSIS_URL, "http://127.0.0.1:" + service.getAddress().getPort());
  }

  @AfterEach
  void stopService() {
    service.stop(0);
  }

  private FakeResponse call(String method, String path, String body) throws Exception {
    var response = new FakeResponse();
    new A3sCompatibilityFilter(new MapConfiguration(settings))
      .doFilter(new FakeRequest(method, path, body), response, (req, res) -> chained = true);
    return response;
  }

  @Test
  void handlesOnlyTheAnalysisEndpoints() {
    var pattern = new A3sCompatibilityFilter(new MapConfiguration(settings)).doGetPattern();

    assertThat(pattern.matches("/api/v2/a3s/analyses")).isTrue();
    assertThat(pattern.matches("/api/v2/a3s/org-entitlement/00000000-0000-0000-0000-000000000000")).isTrue();
    assertThat(pattern.matches("/api/v2/cag/cag-entitlement/x")).isFalse();
    assertThat(pattern.matches("/api/vortex/context")).isFalse();
  }

  @Test
  void forwardsAnalysesWithTheCallersToken() throws Exception {
    var response = call("POST", "/api/v2/a3s/analyses", "{\"projectKey\":\"acme\"}");

    assertThat(response.status).isEqualTo(200);
    assertThat(response.body()).isEqualTo("{\"id\":\"x\",\"issues\":[]}");
    assertThat(forwardedAuthorization.get()).isEqualTo("Bearer user-token");
    assertThat(forwardedBody.get()).isEqualTo("{\"projectKey\":\"acme\"}");
    assertThat(chained).isFalse();
  }

  @Test
  void forwardsEntitlementChecks() throws Exception {
    var response = call("GET", "/api/v2/a3s/org-entitlement/00000000-0000-0000-0000-000000000000", null);

    assertThat(response.status).isEqualTo(200);
    assertThat(response.body()).contains("\"allowed\":true");
    assertThat(forwardedAuthorization.get()).isEqualTo("Bearer user-token");
  }

  @Test
  void notConfiguredMeansNotProvided() throws Exception {
    settings.remove(VortexProperties.ANALYSIS_URL);

    var response = call("POST", "/api/v2/a3s/analyses", "{}");

    assertThat(response.status).isEqualTo(404);
    assertThat(response.body()).contains("not configured");
  }

  @Test
  void unreachableServiceIsAServiceUnavailable() throws Exception {
    settings.put(VortexProperties.ANALYSIS_URL, "http://127.0.0.1:1/");

    assertThat(call("POST", "/api/v2/a3s/analyses", "{}").status).isEqualTo(503);
  }

  @Test
  void unknownRoutesAndMethodsAreNotFound() throws Exception {
    assertThat(call("GET", "/api/v2/a3s/analyses", null).status).isEqualTo(404);
    assertThat(call("DELETE", "/api/v2/a3s/org-entitlement/x", null).status).isEqualTo(404);
  }

  @Test
  void oversizedBodiesAreRejectedWithTheLimit() throws Exception {
    var response = call("POST", "/api/v2/a3s/analyses", "x".repeat(A3sCompatibilityFilter.MAX_BODY_CHARS + 1));

    assertThat(response.status).isEqualTo(413);
    assertThat(response.body()).contains("\"maxRequestSize\":" + A3sCompatibilityFilter.MAX_BODY_CHARS);
    assertThat(forwardedBody.get()).isNull();
  }

  private record MapConfiguration(Map<String, String> values) implements Configuration {
    @Override
    public Optional<String> get(String key) {
      return Optional.ofNullable(values.get(key));
    }

    @Override
    public boolean hasKey(String key) {
      return values.containsKey(key);
    }

    @Override
    public String[] getStringArray(String key) {
      return get(key).map(v -> v.split(",")).orElse(new String[0]);
    }
  }

  private static final class FakeRequest implements HttpRequest {
    private final String method;
    private final String path;
    private final String body;

    FakeRequest(String method, String path, String body) {
      this.method = method;
      this.path = path;
      this.body = body;
    }

    @Override public int getServerPort() { return 9000; }
    @Override public boolean isSecure() { return false; }
    @Override public String getScheme() { return "http"; }
    @Override public String getServerName() { return "localhost"; }
    @Override public String getRequestURL() { return "http://localhost:9000/sonar" + path; }
    @Override public String getRequestURI() { return "/sonar" + path; }
    @Override public String getQueryString() { return null; }
    @Override public String getContextPath() { return "/sonar"; }
    @Override public String getParameter(String name) { return null; }
    @Override public String[] getParameterValues(String name) { return new String[0]; }
    @Override public String getHeader(String name) { return "Authorization".equals(name) ? "Bearer user-token" : null; }
    @Override public Enumeration<String> getHeaderNames() { return java.util.Collections.enumeration(List.of("Authorization")); }
    @Override public Enumeration<String> getHeaders(String name) { return java.util.Collections.emptyEnumeration(); }
    @Override public String getMethod() { return method; }
    @Override public Cookie[] getCookies() { return new Cookie[0]; }
    @Override public String getRemoteAddr() { return "127.0.0.1"; }
    @Override public void setAttribute(String name, Object value) { }
    @Override public String getServletPath() { return path; }
    @Override public BufferedReader getReader() { return new BufferedReader(new StringReader(body == null ? "" : body)); }
  }

  private static final class FakeResponse implements HttpResponse {
    private int status = 200;
    private final StringWriter writer = new StringWriter();

    String body() {
      return writer.toString();
    }

    @Override public void addHeader(String name, String value) { }
    @Override public String getHeader(String name) { return null; }
    @Override public Collection<String> getHeaders(String name) { return List.of(); }
    @Override public void setStatus(int status) { this.status = status; }
    @Override public int getStatus() { return status; }
    @Override public void setContentType(String type) { }
    @Override public PrintWriter getWriter() { return new PrintWriter(writer, true); }
    @Override public void setHeader(String name, String value) { }
    @Override public void sendRedirect(String location) { }
    @Override public void addCookie(Cookie cookie) { }
    @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
    @Override public void setCharacterEncoding(String charset) { }
  }
}
