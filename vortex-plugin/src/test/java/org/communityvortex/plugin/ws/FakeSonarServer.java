package org.communityvortex.plugin.ws;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.sonar.api.server.ws.LocalConnector;
import org.sonar.api.server.ws.Request;
import org.sonar.api.server.ws.Response;
import org.sonar.api.utils.text.JsonWriter;
import org.sonar.api.utils.text.XmlWriter;

/** Test doubles for the in-process web service API. */
final class FakeSonarServer implements LocalConnector {

  record Call(String path, Map<String, String> params) {
  }

  private record Reply(int status, String body) {
  }

  private final Map<String, Reply> replies = new HashMap<>();
  private final List<Call> calls = new ArrayList<>();

  FakeSonarServer reply(String path, String json) {
    replies.put(path, new Reply(200, json));
    return this;
  }

  FakeSonarServer fail(String path, int status, String message) {
    replies.put(path, new Reply(status, "{\"errors\":[{\"msg\":\"" + message + "\"}]}"));
    return this;
  }

  List<Call> calls() {
    return calls;
  }

  Call lastCall(String path) {
    return calls.stream().filter(c -> c.path().equals(path)).reduce((a, b) -> b).orElseThrow();
  }

  @Override
  public LocalResponse call(LocalRequest request) {
    var params = new LinkedHashMap<String, String>();
    request.getParameterMap().forEach((k, v) -> params.put(k, v[0]));
    calls.add(new Call(request.getPath(), params));
    var reply = replies.getOrDefault(request.getPath(), new Reply(404, "{\"errors\":[{\"msg\":\"Unknown url : " + request.getPath() + "\"}]}"));
    return new LocalResponse() {
      @Override
      public int getStatus() {
        return reply.status();
      }

      @Override
      public String getMediaType() {
        return "application/json";
      }

      @Override
      public byte[] getBytes() {
        return reply.body().getBytes(StandardCharsets.UTF_8);
      }

      @Override
      public Collection<String> getHeaderNames() {
        return List.of();
      }

      @Override
      public String getHeader(String name) {
        return null;
      }
    };
  }

  FakeRequest request(String... params) {
    var map = new LinkedHashMap<String, String>();
    for (int i = 0; i < params.length; i += 2) {
      map.put(params[i], params[i + 1]);
    }
    return new FakeRequest(map, this);
  }

  static final class FakeRequest extends Request {
    private final Map<String, String> params;
    private final LocalConnector connector;

    FakeRequest(Map<String, String> params, LocalConnector connector) {
      this.params = params;
      this.connector = connector;
    }

    @Override
    public String method() {
      return "GET";
    }

    @Override
    public String getMediaType() {
      return "application/json";
    }

    @Override
    public boolean hasParam(String key) {
      return params.containsKey(key);
    }

    @Override
    public List<String> paramAsStrings(String key) {
      return params.containsKey(key) ? List.of(params.get(key)) : null;
    }

    @Override
    public Map<String, String[]> getParams() {
      var map = new LinkedHashMap<String, String[]>();
      params.forEach((k, v) -> map.put(k, new String[] {v}));
      return map;
    }

    @Override
    public String param(String key) {
      return params.get(key);
    }

    @Override
    public List<String> multiParam(String key) {
      return params.containsKey(key) ? List.of(params.get(key)) : List.of();
    }

    @Override
    public InputStream paramAsInputStream(String key) {
      return null;
    }

    @Override
    public Part paramAsPart(String key) {
      return null;
    }

    @Override
    public Optional<String> header(String name) {
      return Optional.empty();
    }

    @Override
    public LocalConnector localConnector() {
      return connector;
    }

    @Override
    public String getPath() {
      return "api/vortex/test";
    }
  }

  static final class FakeResponse implements Response {
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private int status = 200;
    private String mediaType;

    int status() {
      return status;
    }

    String mediaType() {
      return mediaType;
    }

    String body() {
      return output.toString(StandardCharsets.UTF_8);
    }

    @Override
    public JsonWriter newJsonWriter() {
      throw new UnsupportedOperationException();
    }

    @Override
    public XmlWriter newXmlWriter() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Response noContent() {
      status = 204;
      return this;
    }

    @Override
    public Response setHeader(String name, String value) {
      return this;
    }

    @Override
    public Collection<String> getHeaderNames() {
      return List.of();
    }

    @Override
    public String getHeader(String name) {
      return null;
    }

    @Override
    public Stream stream() {
      return new Stream() {
        @Override
        public Stream setMediaType(String type) {
          mediaType = type;
          return this;
        }

        @Override
        public Stream setStatus(int httpStatus) {
          status = httpStatus;
          return this;
        }

        @Override
        public OutputStream output() {
          return output;
        }
      };
    }
  }
}
