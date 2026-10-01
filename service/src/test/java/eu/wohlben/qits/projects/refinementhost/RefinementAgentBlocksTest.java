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
 * {@link RefinementAgentBlocks} — plain JUnit over a directly-constructed bean, a Vert.x server
 * standing in for the far end of the tunnel (qits-614).
 *
 * <p>What is pinned is what the proxy would otherwise have done for this hop and nothing does now:
 * the path keeps the proxy prefix (the daemon refuses anything outside the base it was told), the
 * bearer is the daemon api token, the {@code Host} is the daemon's own authority, and the body is
 * {@code {"blocked": …}}. And that nothing — no refinement, no tunnel, a 404 from an older daemon, a
 * lookup that throws — escapes as an exception.
 *
 * <p><b>Every request is issued from a Vert.x worker</b> ({@code executeBlocking}), never from the
 * JUnit thread: a client driven from a thread with no context mints a new context per call and can
 * leave a request unwritten under load. In production the callers are request workers too.
 */
class RefinementAgentBlocksTest {

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

  private RefinementAgentBlocks blocks(Optional<Refinement> refinement, boolean tunnelUp) {
    RefinementAgentBlocks blocks = new RefinementAgentBlocks();
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
  void theFlagIsPostedToTheDaemonUnderItsProxiedBase() throws Exception {
    RefinementAgentBlocks blocks = blocks(refinement(7), true);

    onAWorker(() -> blocks.blocked("epic-1", true));
    onAWorker(() -> blocks.blocked("epic-1", false));

    assertEquals(2, received.size(), "one POST per signal: " + received);
    Received first = received.get(0);
    assertEquals("POST", first.method());
    assertEquals("/projects/refinement-container/7/agents/blocked", first.uri());
    assertEquals("Bearer daemon-token", first.auth());
    assertEquals("localhost:13338", first.host());
    assertEquals("{\"blocked\":true}", first.body());
    assertEquals("{\"blocked\":false}", received.get(1).body());
  }

  /** A daemon older than the route answers 404: one WARN, and the caller never hears of it. */
  @Test
  void anOlderDaemonWithoutTheRouteIsNeverAThrow() throws Exception {
    status.set(404);
    RefinementAgentBlocks blocks = blocks(refinement(7), true);

    onAWorker(() -> assertDoesNotThrow(() -> blocks.blocked("epic-1", true)));

    assertEquals(1, received.size());
  }

  /** No room, or a room whose daemon is not connected: nothing is dialled and nothing thrown. */
  @Test
  void noRefinementOrNoTunnelDialsNothing() throws Exception {
    onAWorker(() -> blocks(Optional.empty(), true).blocked("epic-1", true));
    onAWorker(() -> blocks(refinement(7), false).blocked("epic-1", true));

    assertTrue(received.isEmpty(), "nothing standing, nothing asked: " + received);
  }

  /** The lookup failing is the caller's problem never: the block is already recorded. */
  @Test
  void aLookupThatThrowsIsSwallowed() {
    RefinementAgentBlocks blocks = blocks(Optional.empty(), false);
    blocks.refinements =
        new RefinementService() {
          @Override
          public Optional<Refinement> findByEntity(String entityId) {
            throw new IllegalStateException("the projects database is gone");
          }
        };

    assertDoesNotThrow(() -> blocks.blocked("epic-1", true));
  }
}
