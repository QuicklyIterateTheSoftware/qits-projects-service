package eu.wohlben.qits.projects.releasehost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link HttpReleaseGitHost}'s three writes against a local server standing in for qits-githost —
 * {@link HttpBackingBranchMergerTest}'s shape, and about one thing: that each write carries the
 * repository's address pair (qits-886). qits-githost announces a door write like a push and stores
 * no names, so the commit and the tag carry them in the body and the delete, which has none, as
 * query parameters — and a repository with no name sends neither.
 */
class HttpReleaseGitHostTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private record Received(String method, String path, String query, String body) {}

  private HttpServer server;
  private final List<Received> received = new CopyOnWriteArrayList<>();
  private final AtomicInteger status = new AtomicInteger(200);
  private final AtomicReference<String> responseBody =
      new AtomicReference<>("{\"sha\":\"new-sha\"}");

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  private String startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          received.add(
              new Received(
                  exchange.getRequestMethod(),
                  exchange.getRequestURI().getPath(),
                  exchange.getRequestURI().getRawQuery(),
                  body));
          byte[] bytes = responseBody.get().getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(status.get(), bytes.length == 0 ? -1 : bytes.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
          }
        });
    server.start();
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  private HttpReleaseGitHost against(String base) {
    HttpReleaseGitHost host = new HttpReleaseGitHost();
    host.githostUrl = Optional.ofNullable(base);
    host.bearer = () -> Optional.of("machine-token");
    return host;
  }

  @Test
  void theCommitCarriesTheAddressPairInItsBody() throws Exception {
    String base = startServer();

    against(base)
        .commit(
            "repo-1",
            "project-1",
            "the-repo",
            "refs/heads/release/r1",
            "release(1)",
            Map.of("pom.xml", "<project/>"),
            Map.of());

    JsonNode body = MAPPER.readTree(received.get(0).body());
    assertEquals("/githost/api/repositories/repo-1/commits", received.get(0).path());
    assertEquals("project-1", body.get("projectId").asText());
    assertEquals("the-repo", body.get("repoName").asText());
  }

  @Test
  void theTagCarriesTheAddressPairInItsBody() throws Exception {
    String base = startServer();
    status.set(201);
    responseBody.set("{\"sha\":\"tag-object\"}");

    against(base).tag("repo-1", "project-1", "the-repo", "2026.1004.1", "sha-1", "release(1)");

    JsonNode body = MAPPER.readTree(received.get(0).body());
    assertEquals("/githost/api/repositories/repo-1/tags", received.get(0).path());
    assertEquals("project-1", body.get("projectId").asText());
    assertEquals("the-repo", body.get("repoName").asText());
  }

  @Test
  void theDeleteCarriesTheAddressPairAsQueryParametersAndKeepsTheBranchsSlashes()
      throws Exception {
    String base = startServer();
    status.set(204);
    responseBody.set("");

    against(base).deleteBranch("repo-1", "project-1", "the repo", "release/r1");

    assertEquals("DELETE", received.get(0).method());
    assertEquals("/githost/api/repositories/repo-1/branches/release/r1", received.get(0).path());
    assertEquals("projectId=project-1&repoName=the+repo", received.get(0).query());
  }

  @Test
  void aRepositoryWithNoNameSendsNeitherHalf() throws Exception {
    String base = startServer();

    against(base).commit("repo-1", null, null, "refs/heads/release/r1", "m", Map.of(), Map.of());
    status.set(204);
    responseBody.set("");
    against(base).deleteBranch("repo-1", null, null, "work");

    JsonNode body = MAPPER.readTree(received.get(0).body());
    assertFalse(body.has("projectId"), body.toString());
    assertFalse(body.has("repoName"), body.toString());
    assertNull(received.get(1).query(), "no query string at all on a nameless delete");
  }
}
