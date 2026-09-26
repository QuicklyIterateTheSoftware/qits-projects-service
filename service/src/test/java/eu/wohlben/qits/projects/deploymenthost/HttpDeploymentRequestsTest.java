package eu.wohlben.qits.projects.deploymenthost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import eu.wohlben.qits.projects.control.DeploymentRequests;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link HttpDeploymentRequests} against a local server standing in for qits-deployments — plain
 * JUnit over a directly-constructed bean, {@code releasehost/HttpPublishRunsTest}'s shape.
 *
 * <p><b>Everything here is about one rule that only a wire test can check: no failure is ever an
 * empty list.</b> A refusal, any other status, a body with no array, an unset address and nobody
 * listening are all "could not be asked", because the one thing the caller may never be told is that
 * a released, deployable repository has nothing owed when the truth is that nobody could be asked.
 *
 * <p>The second claim is the credential, and it is not the one the neighbouring hops make: this door
 * is {@code qits:admin}/{@code qits:agent} over there while a platform service client carries {@code
 * qits:system} alone, so the read presents the forwarded pair as an agent and no bearer at all.
 */
class HttpDeploymentRequestsTest {

  private record Received(String method, String path, String query, String user, String roles) {}

  private HttpServer server;
  private final List<Received> received = new CopyOnWriteArrayList<>();
  private final AtomicInteger status = new AtomicInteger(200);
  private final AtomicReference<String> responseBody =
      new AtomicReference<>(
          "{\"deploymentRequests\":[{\"id\":\"dr-2\",\"deploymentStatus\":\"ACTIVE\","
              + "\"createdAt\":\"2026-09-16T11:00:00Z\"},{\"id\":\"dr-1\","
              + "\"deploymentStatus\":\"SUPERSEDED\",\"createdAt\":\"2026-09-16T10:00:00Z\"}]}");

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
                  exchange.getRequestURI().getQuery(),
                  exchange.getRequestHeaders().getFirst("X-Qits-User"),
                  exchange.getRequestHeaders().getFirst("X-Qits-Roles")));
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

  private HttpDeploymentRequests against(String base) {
    HttpDeploymentRequests requests = new HttpDeploymentRequests();
    requests.deploymentsUrl = Optional.ofNullable(base);
    return requests;
  }

  @Test
  void thePairIsTheKeyAndTheAnswerKeepsTheFarSidesOrder() throws Exception {
    String base = startServer();

    Optional<List<DeploymentRequests.DeploymentRequestView>> answered =
        against(base).forRelease("repo-1", "2026.916.1");

    assertTrue(answered.isPresent());
    assertEquals(
        List.of("dr-2", "dr-1"),
        answered.get().stream().map(DeploymentRequests.DeploymentRequestView::id).toList(),
        "newest first, exactly as the far side ordered them — nothing is re-sorted here");
    assertEquals("ACTIVE", answered.get().get(0).status());
    assertEquals(Instant.parse("2026-09-16T11:00:00Z"), answered.get().get(0).createdAt());

    Received asked = received.get(0);
    assertEquals("GET", asked.method());
    assertEquals("/deployments/api/deployment-requests", asked.path());
    assertTrue(asked.query().contains("repoId=repo-1"), asked.query());
    assertTrue(asked.query().contains("version=2026.916.1"), asked.query());
  }

  /**
   * The credential, which is deliberately not a machine bearer: that listing is a person's and an
   * agent's door, and this service's client carries {@code qits:system} alone.
   */
  @Test
  void theReadPresentsTheForwardedPairAsAnAgentAndNoBearer() throws Exception {
    String base = startServer();

    against(base).forRelease("repo-1", "2026.916.1");

    assertEquals("qits-projects", received.get(0).user());
    assertEquals("qits:agent", received.get(0).roles());
  }

  /** A null status is a real answer over there — a request whose deployment was never created. */
  @Test
  void aNullDeploymentStatusTravelsAsNullAndNotAsAWord() throws Exception {
    String base = startServer();
    responseBody.set(
        "{\"deploymentRequests\":[{\"id\":\"dr-3\",\"deploymentStatus\":null,"
            + "\"createdAt\":\"2026-09-16T11:00:00Z\"}]}");

    assertNull(against(base).forRelease("repo-1", "v").orElseThrow().get(0).status());
  }

  /** The one answer that is not a failure: asked, and nothing for this version yet. */
  @Test
  void anEmptyListingIsAnAnswerAndNotAFailure() throws Exception {
    String base = startServer();
    responseBody.set("{\"deploymentRequests\":[]}");

    Optional<List<DeploymentRequests.DeploymentRequestView>> answered =
        against(base).forRelease("repo-1", "v");

    assertTrue(answered.isPresent(), "present and empty is not the same as empty");
    assertEquals(List.of(), answered.get());
  }

  @Test
  void aRefusalIsCouldNotAskAndNeverAnEmptyListing() throws Exception {
    String base = startServer();
    status.set(403);
    responseBody.set("{\"message\":\"no\"}");

    assertEquals(Optional.empty(), against(base).forRelease("repo-1", "v"));
  }

  @Test
  void aBodyWithNoArrayIsNotAnAnswer() throws Exception {
    String base = startServer();
    responseBody.set("{\"message\":\"repoId and version are asked together\"}");

    assertEquals(Optional.empty(), against(base).forRelease("repo-1", "v"));
  }

  @Test
  void anUnsetAddressAndHalfAPairAskNobody() {
    assertEquals(Optional.empty(), against(null).forRelease("repo-1", "v"));
    assertEquals(Optional.empty(), against("  ").forRelease("repo-1", "v"));
    assertEquals(Optional.empty(), against("http://127.0.0.1:1").forRelease("repo-1", null));
    assertEquals(Optional.empty(), against("http://127.0.0.1:1").forRelease(" ", "v"));
  }

  @Test
  void nobodyListeningIsCouldNotAskAndNeverAThrow() {
    assertEquals(Optional.empty(), against("http://127.0.0.1:1").forRelease("repo-1", "v"));
  }
}
