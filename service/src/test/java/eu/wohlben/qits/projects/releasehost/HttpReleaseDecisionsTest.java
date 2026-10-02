package eu.wohlben.qits.projects.releasehost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import eu.wohlben.qits.projects.control.ReleaseDecisions;
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
 * {@link HttpReleaseDecisions} against a local server standing in for qits-ci — {@link
 * HttpPublishRunsTest}'s shape. The body is the live door's, as it answered on 2026-10-02.
 *
 * <p>The rule only a wire test can check: <b>no failure is ever an empty record.</b> An empty list
 * means nothing was owed at that version; an outage read as that would say a release published
 * nothing.
 */
class HttpReleaseDecisionsTest {

  private record Received(String method, String path, String user) {}

  private HttpServer server;
  private final List<Received> received = new CopyOnWriteArrayList<>();
  private final AtomicInteger status = new AtomicInteger(200);
  private final AtomicReference<String> responseBody =
      new AtomicReference<>(
          """
          {"repository":"repo-1","version":"2026.1002.1","artifacts":[
            {"type":"docker","name":"qits/qits-thing","publish":"always","decision":"published",
             "unchangedSince":null,"runId":"r1","decidedAt":"2026-10-02T05:03:10Z"},
            {"type":"maven","name":"eu.wohlben.qits:qits-thing-golden-masters","publish":"if-changed",
             "decision":"unchanged","unchangedSince":"2026.1001.7","runId":"r1","decidedAt":null}]}
          """);

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
          received.add(
              new Received(
                  exchange.getRequestMethod(),
                  exchange.getRequestURI().getPath(),
                  exchange.getRequestHeaders().getFirst("X-Qits-User")));
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

  private HttpReleaseDecisions against(String base) {
    HttpReleaseDecisions decisions = new HttpReleaseDecisions();
    decisions.ciUrl = Optional.ofNullable(base);
    decisions.bearer = new IdpCiBearer();
    return decisions;
  }

  @Test
  void theRecordIsReadEntryForEntryFromTheDoor() throws IOException {
    String base = startServer();

    ReleaseDecisions.Answer answer = against(base).of("repo-1", "2026.1002.1");

    assertTrue(answer.ok());
    assertEquals(
        List.of(
            new ReleaseDecisions.Decision("docker", "qits/qits-thing", "published", null),
            new ReleaseDecisions.Decision(
                "maven", "eu.wohlben.qits:qits-thing-golden-masters", "unchanged", "2026.1001.7")),
        answer.decisions());
    assertEquals(
        List.of(
            new Received(
                "GET", "/ci/api/repositories/repo-1/releases/2026.1002.1/artifacts", "qits-projects")),
        received);
  }

  @Test
  void anEmptyRecordIsAnAnswer() throws IOException {
    String base = startServer();
    responseBody.set("{\"repository\":\"repo-1\",\"version\":\"v\",\"artifacts\":[]}");

    ReleaseDecisions.Answer answer = against(base).of("repo-1", "v");

    assertTrue(answer.ok());
    assertEquals(List.of(), answer.decisions());
  }

  @Test
  void aServerErrorIsAFailureNamingTheStatus() throws IOException {
    String base = startServer();
    status.set(503);

    ReleaseDecisions.Answer answer = against(base).of("repo-1", "v");

    assertFalse(answer.ok());
    assertTrue(answer.failure().contains("503"), answer.failure());
  }

  @Test
  void aBodyWithoutAnArtifactsListIsAFailure() throws IOException {
    String base = startServer();
    responseBody.set("{\"repository\":\"repo-1\"}");

    assertFalse(against(base).of("repo-1", "v").ok());
  }

  @Test
  void anEntryWithoutADecisionIsAFailure() throws IOException {
    String base = startServer();
    responseBody.set("{\"artifacts\":[{\"type\":\"docker\",\"name\":\"qits/x\"}]}");

    assertFalse(against(base).of("repo-1", "v").ok());
  }

  @Test
  void anUnsetAddressIsAFailure() {
    assertFalse(against(null).of("repo-1", "v").ok());
    assertFalse(against("  ").of("repo-1", "v").ok());
  }

  @Test
  void nobodyListeningIsAFailure() {
    ReleaseDecisions.Answer answer = against("http://127.0.0.1:1").of("repo-1", "v");

    assertFalse(answer.ok());
    assertTrue(answer.failure().contains("could not be reached"), answer.failure());
  }
}
