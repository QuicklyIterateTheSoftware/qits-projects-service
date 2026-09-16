package eu.wohlben.qits.projects.releasehost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import eu.wohlben.qits.projects.control.BackingBranchMerger;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link HttpBackingBranchMerger} against a local server standing in for qits-githost — plain JUnit
 * over a directly-constructed bean, {@code HttpEstatePinsTest}'s shape.
 *
 * <p>What only a wire test can see, and the flow cannot: that a fold with nothing to decide sends
 * <b>the request it always sent</b>, byte-comparable and with no {@code resolutions} key at all, so
 * a git host that has never heard of directives cannot tell the two features apart; that a
 * directive travels as {@code {path, gitlink}}; and that the 409's four new fields and the 200's
 * {@code resolved} are carried through unchanged rather than reworded.
 */
class HttpBackingBranchMergerTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private record Received(String method, String path, String auth, String body) {}

  private HttpServer server;
  private final List<Received> received = new CopyOnWriteArrayList<>();
  private final AtomicInteger status = new AtomicInteger(200);
  private final AtomicReference<String> responseBody =
      new AtomicReference<>("{\"outcome\":\"merged\",\"sha\":\"fold-sha\",\"parents\":[]}");

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
                  exchange.getRequestHeaders().getFirst("Authorization"),
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

  private HttpBackingBranchMerger against(String base) {
    HttpBackingBranchMerger merger = new HttpBackingBranchMerger();
    merger.githostUrl = Optional.ofNullable(base);
    merger.bearer = () -> Optional.of("machine-token");
    return merger;
  }

  private static final List<String> SOURCES =
      List.of("refs/heads/main", "refs/heads/work", "refs/tags/2026.903.1");

  @Test
  void aFoldWithNothingToDecideSendsNoResolutionsKeyAtAll() throws Exception {
    String base = startServer();

    against(base).merge("repo-1", "refs/heads/release/r1", SOURCES, "a fold");

    JsonNode body = MAPPER.readTree(received.get(0).body());
    assertEquals("POST", received.get(0).method());
    assertEquals("/githost/api/repositories/repo-1/merges", received.get(0).path());
    assertEquals("Bearer machine-token", received.get(0).auth());
    // Absent, not empty: the far side must see the identical request it saw before directives
    // existed, so that this feature cannot change a fold it does not decide.
    assertFalse(body.has("resolutions"), body.toString());
    assertEquals("refs/heads/release/r1", body.get("target").asText());
    assertEquals(3, body.get("sources").size());
  }

  @Test
  void anEmptyResolutionListIsTheSameRequestAsNoneAtAll() throws Exception {
    String base = startServer();

    against(base).merge("repo-1", "refs/heads/release/r1", SOURCES, "a fold", List.of());

    assertFalse(MAPPER.readTree(received.get(0).body()).has("resolutions"));
  }

  @Test
  void aDirectiveTravelsAsAPathAndAGitlink() throws Exception {
    String base = startServer();

    against(base)
        .merge(
            "repo-1",
            "refs/heads/release/r1",
            SOURCES,
            "a fold",
            List.of(
                new BackingBranchMerger.Resolution(
                    "components/member-a/member-a",
                    "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")));

    JsonNode resolutions = MAPPER.readTree(received.get(0).body()).get("resolutions");
    assertEquals(1, resolutions.size());
    assertEquals("components/member-a/member-a", resolutions.get(0).get("path").asText());
    assertEquals(
        "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
        resolutions.get(0).get("gitlink").asText());
  }

  @Test
  void theResolvedPathsOfASuccessfulFoldAreRead() throws Exception {
    String base = startServer();
    responseBody.set(
        "{\"outcome\":\"merged\",\"sha\":\"fold-sha\",\"parents\":[\"p1\"],"
            + "\"resolved\":[\"components/member-a/member-a\"]}");

    BackingBranchMerger.Outcome outcome =
        against(base).merge("repo-1", "refs/heads/release/r1", SOURCES, "a fold", List.of());

    assertEquals(BackingBranchMerger.Result.MERGED, outcome.result());
    assertEquals(List.of("components/member-a/member-a"), outcome.resolved());
  }

  @Test
  void aBodyWithNoResolvedKeyIsAnEmptyListAndNotAFailure() throws Exception {
    String base = startServer();

    BackingBranchMerger.Outcome outcome =
        against(base).merge("repo-1", "refs/heads/release/r1", SOURCES, "a fold");

    assertTrue(outcome.folded());
    assertEquals(List.of(), outcome.resolved());
  }

  @Test
  void theConflictsSidesAndKindAreForwardedUnchanged() throws Exception {
    String base = startServer();
    status.set(409);
    responseBody.set(
        "{\"error\":\"merge-conflict\",\"target\":\"refs/heads/release/r1\",\"conflicts\":["
            + "{\"path\":\"components/member-a/member-a\",\"head\":\"refs/heads/work\","
            + "\"headSha\":\"head-sha\",\"reason\":\"content\",\"kind\":\"gitlink\","
            + "\"base\":\"base-sha\",\"ours\":\"ours-sha\",\"theirs\":\"theirs-sha\"}]}");

    BackingBranchMerger.Outcome outcome =
        against(base).merge("repo-1", "refs/heads/release/r1", SOURCES, "a fold", List.of());

    assertEquals(BackingBranchMerger.Result.CONFLICT, outcome.result());
    BackingBranchMerger.Conflict conflict = outcome.conflicts().get(0);
    assertEquals("components/member-a/member-a", conflict.path());
    assertEquals(BackingBranchMerger.KIND_GITLINK, conflict.kind());
    assertEquals("base-sha", conflict.base());
    assertEquals("ours-sha", conflict.ours());
    assertEquals("theirs-sha", conflict.theirs());
  }

  @Test
  void aSideThatIsJsonNullIsNullAndNeverTheStringNull() throws Exception {
    String base = startServer();
    status.set(409);
    responseBody.set(
        "{\"error\":\"merge-conflict\",\"target\":\"refs/heads/release/r1\",\"conflicts\":["
            + "{\"path\":\"components/member-a/member-a\",\"head\":\"refs/heads/work\","
            + "\"headSha\":\"head-sha\",\"reason\":\"content\",\"kind\":\"gitlink\","
            + "\"base\":null,\"ours\":\"ours-sha\",\"theirs\":null}]}");

    BackingBranchMerger.Conflict conflict =
        against(base)
            .merge("repo-1", "refs/heads/release/r1", SOURCES, "a fold", List.of())
            .conflicts()
            .get(0);

    // "theirs": null is a side that deletes the submodule, and a resolver reads it as a person's
    // call. Jackson's asText(default) would have made it the four-character string "null", which
    // reads as a sha-shaped value nothing could act on.
    assertNull(conflict.theirs());
    assertNull(conflict.base());
    assertEquals("ours-sha", conflict.ours());
  }

  @Test
  void aConflictBodyWithNoKindIsReadAsAFileConflict() throws Exception {
    String base = startServer();
    status.set(409);
    responseBody.set(
        "{\"error\":\"merge-conflict\",\"target\":\"refs/heads/release/r1\",\"conflicts\":["
            + "{\"path\":\"pom.xml\",\"head\":\"refs/heads/work\",\"headSha\":\"head-sha\","
            + "\"reason\":\"content\"}]}");

    BackingBranchMerger.Conflict conflict =
        against(base)
            .merge("repo-1", "refs/heads/release/r1", SOURCES, "a fold", List.of())
            .conflicts()
            .get(0);

    // Fail closed: the gitlink arm is the only one anything acts on, so an answer that says nothing
    // about the kind must never fall into it.
    assertEquals(BackingBranchMerger.KIND_FILE, conflict.kind());
  }
}
