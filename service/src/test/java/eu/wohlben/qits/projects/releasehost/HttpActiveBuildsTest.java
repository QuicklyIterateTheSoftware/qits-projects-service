package eu.wohlben.qits.projects.releasehost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link HttpActiveBuilds} against a local server standing in for qits-ci — plain JUnit over a
 * directly-constructed bean, {@link HttpPublishRunsTest}'s shape.
 *
 * <p>The rule pinned here is the one qits-760 turned on: the gate holds on a CONFIGURED probe's
 * silence and lets a vouch through only where nothing is configured, so the adapter must keep the
 * two apart — {@link HttpActiveBuilds#configured} false only for an unset address, and every
 * failure of a set one answering "could not ask" rather than a count.
 */
class HttpActiveBuildsTest {

  private HttpServer server;
  private final AtomicInteger status = new AtomicInteger(200);
  private final AtomicReference<String> responseBody = new AtomicReference<>("{\"runs\":[]}");

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

  private HttpActiveBuilds against(String base) {
    HttpActiveBuilds probe = new HttpActiveBuilds();
    probe.ciUrl = Optional.ofNullable(base);
    probe.bearer = new IdpCiBearer();
    return probe;
  }

  @Test
  void anUnsetAddressIsNotConfiguredAndAsksNothing() {
    HttpActiveBuilds probe = against(null);
    assertFalse(probe.configured());
    assertEquals(Optional.empty(), probe.activeFor("repo-1", "abc"));

    HttpActiveBuilds blank = against("  ");
    assertFalse(blank.configured(), "a blank address is no address");
  }

  @Test
  void aSetAddressNobodyAnswersIsConfiguredAndCouldNotAsk() throws Exception {
    int closed;
    try (ServerSocket socket = new ServerSocket(0)) {
      closed = socket.getLocalPort();
    }
    HttpActiveBuilds probe = against("http://127.0.0.1:" + closed);

    assertTrue(probe.configured(), "qits-ci restarting is not a tier without qits-ci");
    assertEquals(
        Optional.empty(),
        probe.activeFor("repo-1", "abc"),
        "an unreachable qits-ci is never read as zero runs");
  }

  @Test
  void aNon200IsCouldNotAskAndAListingIsCountedForTheFoldOnly() throws Exception {
    String base = startServer();
    HttpActiveBuilds probe = against(base);

    status.set(503);
    assertEquals(Optional.empty(), probe.activeFor("repo-1", "abc"));

    status.set(200);
    responseBody.set(
        "{\"runs\":["
            + "{\"repoId\":\"repo-1\",\"commitSha\":\"abc\"},"
            + "{\"repoId\":\"repo-1\",\"commitSha\":\"other\"},"
            + "{\"repoId\":\"repo-2\",\"commitSha\":\"abc\"}]}");
    assertEquals(Optional.of(1), probe.activeFor("repo-1", "abc"));
  }
}
