package eu.wohlben.qits.projects.refinementhost;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.entity.Refinement;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpServer;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link RefinementAgentEntities} — plain JUnit over a directly-constructed bean, a Vert.x server
 * standing in for the far end of the tunnel (qits-614, qits-617, qits-1285).
 *
 * <p>What is pinned is what the proxy would otherwise have done for this hop and nothing does now:
 * the path keeps the proxy prefix (the daemon refuses anything outside the base it was told), the
 * bearer is the daemon api token, the {@code Host} is the daemon's own authority, and the body is
 * {@code {"title": …, "status": …, "blocked": …}}; that the entity id rides in the path as the
 * agent id, with no second id to carry; that a 404 — no agent started in the room yet — is logged
 * and never retried; and that nothing — no refinement, no tunnel, any other refusal, a lookup that
 * throws — escapes as an exception.
 *
 * <p><b>Every request is issued from a Vert.x worker</b> ({@code executeBlocking}), never from the
 * JUnit thread: a client driven from a thread with no context mints a new context per call and can
 * leave a request unwritten under load. In production the callers are request workers too.
 */
class RefinementAgentEntitiesTest {

  private record Received(String method, String uri, String auth, String host, String body) {}

  private Vertx vertx;
  private HttpServer server;
  private HttpClient client;
  private final List<Received> received = new CopyOnWriteArrayList<>();
  private final AtomicInteger status = new AtomicInteger(200);

  @BeforeEach
  void start() throws Exception {
    vertx = Vertx.vertx();
    server =
        vertx
            .createHttpServer()
            .requestHandler(
                request ->
                    request
                        .body()
                        .onSuccess(
                            body -> {
                              received.add(
                                  new Received(
                                      request.method().name(),
                                      request.uri(),
                                      request.getHeader("Authorization"),
                                      request.getHeader("Host"),
                                      body.toString()));
                              request.response().setStatusCode(status.get()).end();
                            }))
            .listen(0, "127.0.0.1")
            .toCompletionStage()
            .toCompletableFuture()
            .get(10, TimeUnit.SECONDS);
    client = vertx.createHttpClient();
  }

  @AfterEach
  void stop() throws Exception {
    vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
  }

  private RefinementAgentEntities blocks(Optional<Refinement> refinement, boolean tunnelUp) {
    RefinementAgentEntities blocks = new RefinementAgentEntities();
    blocks.daemonApiToken = "daemon-token";
    blocks.daemonApiPort = 13338;
    blocks.timeoutMs = 5000;
    blocks.refinements =
        new RefinementService() {
          @Override
          public Optional<Refinement> findByEntity(String entityId) {
            return refinement;
          }
        };
    blocks.tunnels =
        new RefinementTunnels() {
          @Override
          public Optional<TunnelOrigin> originFor(Long refinementId) {
            return tunnelUp
                ? Optional.of(new TunnelOrigin(client, server.actualPort()))
                : Optional.empty();
          }
        };
    return blocks;
  }

  private static Optional<Refinement> refinement(long id) {
    Refinement refinement = new Refinement();
    refinement.id = id;
    refinement.entityId = "epic-1";
    return Optional.of(refinement);
  }

  /** Runs on a Vert.x worker, so the client is driven from a context — see the class javadoc. */
  private void onAWorker(Runnable call) throws Exception {
    vertx
        .executeBlocking(
            () -> {
              call.run();
              return null;
            })
        .toCompletionStage()
        .toCompletableFuture()
        .get(20, TimeUnit.SECONDS);
  }

  @Test
  void theEntityIsPostedToTheDaemonUnderItsProxiedBase() throws Exception {
    RefinementAgentEntities blocks = blocks(refinement(7), true);

    onAWorker(() -> blocks.changed("epic-1", "Onboarding \"v2\"", "REPORTED", true));
    onAWorker(() -> blocks.changed("epic-1", "Onboarding", "REFINED", false));

    assertEquals(2, received.size(), "one POST per signal, no fallback on a 200: " + received);
    Received first = received.get(0);
    assertEquals("POST", first.method());
    assertEquals("/projects/refinement-container/7/agent-worktrees/epic-1/entity", first.uri());
    assertEquals("Bearer daemon-token", first.auth());
    assertEquals("localhost:13338", first.host());
    // A title is a person's text: it is JSON-encoded, never concatenated.
    assertEquals(
        "{\"title\":\"Onboarding \\\"v2\\\"\",\"status\":\"REPORTED\",\"blocked\":true}",
        first.body());
    assertEquals(
        "{\"title\":\"Onboarding\",\"status\":\"REFINED\",\"blocked\":false}",
        received.get(1).body());
  }

  /**
   * With a block source (qits-895), it rides beside the flag; a null source — not blocked, or a
   * caller that does not know it, exactly what the 4-arg {@code changed} above still sends — is
   * OMITTED rather than sent as a JSON null.
   */
  @Test
  void aBlockSourceIsAddedToTheBodyAndOmittedWhenNull() throws Exception {
    RefinementAgentEntities blocks = blocks(refinement(7), true);

    onAWorker(() -> blocks.changed("epic-1", "Onboarding", "REFINED", true, "AGENT_WAITING"));
    onAWorker(() -> blocks.changed("epic-1", "Onboarding", "REPORTED", false, null));

    assertEquals(2, received.size());
    assertEquals(
        "{\"title\":\"Onboarding\",\"status\":\"REFINED\",\"blocked\":true,"
            + "\"blockSource\":\"AGENT_WAITING\"}",
        received.get(0).body());
    assertEquals(
        "{\"title\":\"Onboarding\",\"status\":\"REPORTED\",\"blocked\":false}",
        received.get(1).body());
  }

  /**
   * A 404 is the ordinary case — no agent has been started in this refinement room yet — so it is
   * never a throw, never retried on a second route (there is no second route any more), and asks
   * only once.
   */
  @Test
  void aDaemonThatHasNoAgentStartedYetIsNeverAThrow() throws Exception {
    status.set(404);
    RefinementAgentEntities blocks = blocks(refinement(7), true);

    onAWorker(
        () -> assertDoesNotThrow(() -> blocks.changed("epic-1", "Onboarding", "REFINED", true)));

    assertEquals(1, received.size(), "exactly one POST, no retry on a 404: " + received);
  }

  /** Any other refusal is the one WARN, never a throw, and asks only once. */
  @Test
  void aRefusalOtherThan404IsNeverAThrow() throws Exception {
    status.set(500);
    RefinementAgentEntities blocks = blocks(refinement(7), true);

    onAWorker(
        () -> assertDoesNotThrow(() -> blocks.changed("epic-1", "Onboarding", "REFINED", true)));

    assertEquals(1, received.size());
  }

  /** No room, or a room whose daemon is not connected: nothing is dialled and nothing thrown. */
  @Test
  void noRefinementOrNoTunnelDialsNothing() throws Exception {
    onAWorker(() -> blocks(Optional.empty(), true).changed("epic-1", "Onboarding", "REFINED", true));
    onAWorker(() -> blocks(refinement(7), false).changed("epic-1", "Onboarding", "REFINED", true));

    assertTrue(received.isEmpty(), "nothing standing, nothing asked: " + received);
  }

  /** The lookup failing is the caller's problem never: the block is already recorded. */
  @Test
  void aLookupThatThrowsIsSwallowed() {
    RefinementAgentEntities blocks = blocks(Optional.empty(), false);
    blocks.refinements =
        new RefinementService() {
          @Override
          public Optional<Refinement> findByEntity(String entityId) {
            throw new IllegalStateException("the projects database is gone");
          }
        };

    assertDoesNotThrow(() -> blocks.changed("epic-1", "Onboarding", "REFINED", true));
  }
}
