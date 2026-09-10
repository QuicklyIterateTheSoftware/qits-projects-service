package eu.wohlben.qits.projects.maintenancehost;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import eu.wohlben.qits.projects.control.EstatePins;
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
 * {@link HttpEstatePins} against a local server standing in for qits-maintenance — plain JUnit over a
 * directly-constructed bean, {@link HttpDownstreamComponentsTest}'s shape beside it.
 *
 * <p>Two things are under test and the flow can see neither. The first is the exact wire shape: the
 * method, the one path with the repository's <b>name</b> in it (this route resolves by name where its
 * neighbour resolves by row id, and a test is the only thing that will notice if that is ever
 * "tidied"), the credential, the header pair it falls back to, and the six fields of a change with
 * {@code "ecosystem":"gitlink"} among them. The second is that <b>every</b> way this can go wrong
 * answers {@link Optional#empty()} without throwing — including the 409 that means somebody else's
 * bump is already in flight, which is a refusal like any other from here.
 */
class HttpEstatePinsTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private record Received(
      String method, String path, String auth, String user, String roles, String body) {}

  private HttpServer server;
  private final List<Received> received = new CopyOnWriteArrayList<>();
  private final AtomicInteger status = new AtomicInteger(202);
  private final AtomicReference<String> responseBody =
      new AtomicReference<>("{\"id\":\"bump-1\"}");

  private static final EstatePins.GitlinkChange ONE_CHANGE =
      new EstatePins.GitlinkChange(
          "components/qits-ci/qits-ci-frontend",
          "qits-ci-frontend",
          "0123456789abcdef0123456789abcdef01234567",
          "2026.910.180413");

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
                  exchange.getRequestHeaders().getFirst("X-Qits-User"),
                  exchange.getRequestHeaders().getFirst("X-Qits-Roles"),
                  body));
          byte[] responseBytes = responseBody.get().getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(
              status.get(), responseBytes.length == 0 ? -1 : responseBytes.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(responseBytes);
          }
        });
    server.start();
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  private HttpEstatePins against(String base, Optional<String> authorization) {
    HttpEstatePins pins = new HttpEstatePins();
    pins.maintenanceUrl = Optional.ofNullable(base);
    pins.bearer = () -> authorization;
    return pins;
  }

  private HttpEstatePins against(String base) {
    return against(base, Optional.of("Bearer machine-token"));
  }

  @Test
  void aBumpIsPostedToTheBranchesRouteAddressedByTheRepositoryName() throws Exception {
    String base = startServer();
    responseBody.set("{\"id\":\"bump-42\"}");

    Optional<String> id = against(base).bump("qits-qits", "work", List.of(ONE_CHANGE));

    assertEquals(1, received.size());
    Received request = received.get(0);
    assertEquals("POST", request.method());
    assertEquals(
        "/maintenance/api/repositories/qits-qits/branches/bumps",
        request.path(),
        "addressed by the catalogue NAME — this route does not resolve a row id");
    assertEquals("Bearer machine-token", request.auth());
    assertEquals(Optional.of("bump-42"), id, "202 answers the bump's id");
  }

  /** The six fields, spelled the way the far side reads them, with the ecosystem as a string. */
  @Test
  void thePostedChangeCarriesTheSixFieldsAndSaysGitlink() throws Exception {
    String base = startServer();

    against(base).bump("qits-qits", "work", List.of(ONE_CHANGE));

    JsonNode body = MAPPER.readTree(received.get(0).body());
    assertEquals("work", body.get("branch").asText(), "the bare branch, never refs/heads/…");
    assertEquals(1, body.get("changes").size());
    JsonNode change = body.get("changes").get(0);
    assertEquals("gitlink", change.get("ecosystem").asText());
    assertEquals("components/qits-ci/qits-ci-frontend", change.get("manifestPath").asText());
    assertEquals(
        "qits-ci-frontend",
        change.get("name").asText(),
        "the SIBLING's name: it is what the far side derives a clone url from");
    assertEquals("0123456789abcdef0123456789abcdef01234567", change.get("from").asText());
    assertEquals("2026.910.180413", change.get("to").asText());
    assertEquals(
        "gitlink:components/qits-ci/qits-ci-frontend",
        change.get("location").asText(),
        "carried and ignored for a gitlink, and sent anyway rather than dropped");
  }

  /** No bearer is not a reason to stay silent: the fallback is the forwarded pair, as next door. */
  @Test
  void withNoBearerItAsksWithTheForwardedHeaderPair() throws Exception {
    String base = startServer();

    Optional<String> id =
        against(base, Optional.empty()).bump("qits-qits", "work", List.of(ONE_CHANGE));

    Received request = received.get(0);
    assertNull(request.auth(), "no credential means no Authorization header at all");
    assertEquals("qits-projects", request.user());
    assertEquals("qits:system", request.roles());
    assertEquals(Optional.of("bump-1"), id);
  }

  /**
   * A 409 is qits-maintenance saying a targeted bump is already REQUESTED or RUNNING on this branch.
   * It reads here as "could not ask", which is safe because the caller's record of a bump is
   * positive: it simply holds and asks again next sweep, and the far side's own 409 is what stops a
   * second ask ever becoming a second bump.
   */
  @Test
  void aBumpAlreadyInFlightIsCouldNotAskAndNeverThrown() throws Exception {
    String base = startServer();
    status.set(409);
    responseBody.set("{\"message\":\"a bump is already running on work\"}");

    assertEquals(
        Optional.empty(),
        assertDoesNotThrow(() -> against(base).bump("qits-qits", "work", List.of(ONE_CHANGE))));
  }

  @Test
  void aRefusalIsCouldNotAskAndNeverThrown() throws Exception {
    String base = startServer();
    status.set(404);
    responseBody.set("{\"message\":\"no such repository\"}");

    assertEquals(
        Optional.empty(),
        assertDoesNotThrow(() -> against(base).bump("qits-qits", "work", List.of(ONE_CHANGE))));
  }

  /** A 202 that names no bump is a door that answered without accepting anything. */
  @Test
  void anAcceptanceWithNoIdIsCouldNotAsk() throws Exception {
    String base = startServer();
    responseBody.set("{\"accepted\":true}");

    assertEquals(
        Optional.empty(),
        assertDoesNotThrow(() -> against(base).bump("qits-qits", "work", List.of(ONE_CHANGE))));
  }

  @Test
  void anUnreachableMaintenanceIsCouldNotAskAndNeverThrown() {
    // Port 1 on loopback: nothing listens, and connecting fails fast.
    assertEquals(
        Optional.empty(),
        assertDoesNotThrow(
            () -> against("http://127.0.0.1:1").bump("qits-qits", "work", List.of(ONE_CHANGE))));
  }

  @Test
  void anUnsetOrBlankAddressAsksNothingAndAnswersCouldNotAsk() throws Exception {
    String base = startServer();

    assertEquals(Optional.empty(), against(null).bump("qits-qits", "work", List.of(ONE_CHANGE)));
    assertEquals(Optional.empty(), against("   ").bump("qits-qits", "work", List.of(ONE_CHANGE)));

    assertTrue(received.isEmpty(), "a switched-off hop dials nothing: " + received);
    assertTrue(base.startsWith("http://"), "the server was up, so an attempt would have landed");
  }

  /** A null {@code from} is commit-message material and travels as a null, not as a crash. */
  @Test
  void aChangeWithNoCurrentShaStillPosts() throws Exception {
    String base = startServer();

    Optional<String> id =
        against(base)
            .bump(
                "qits-qits",
                "main",
                List.of(
                    new EstatePins.GitlinkChange(
                        "components/qits-ci/qits-ci-service", "qits-ci-service", null, "2026.1.1")));

    assertEquals(Optional.of("bump-1"), id);
    JsonNode change = MAPPER.readTree(received.get(0).body()).get("changes").get(0);
    assertTrue(change.get("from").isNull(), "nothing was pinned there, and that is a value");
  }
}
