package eu.wohlben.qits.projects.maintenancehost;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import eu.wohlben.qits.projects.control.AutomationLedger;
import eu.wohlben.qits.projects.control.ReleaseRequestAutomations;
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
 * {@link HttpReleaseRequestAutomations} against a local server standing in for qits-maintenance —
 * plain JUnit over a directly-constructed bean, {@link HttpDownstreamComponentsTest}'s shape beside
 * it.
 *
 * <p>Two things are under test and the flow can see neither. The first is the exact wire shape of
 * the three doors: the paths, the credential and its fallback, the trigger's six fields — with
 * {@code changedSincePrevious} null and empty kept apart, because the far side carries an outcome
 * over on one and never on the other — and the answer read back field for field. The second is the
 * two failure contracts: the trigger and the read answer {@link Optional#empty()} for every way they
 * can go wrong, while the re-run keeps a refusal's status and sentence for the person who pressed it.
 */
class HttpReleaseRequestAutomationsTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final String FOLD = "0123456789abcdef0123456789abcdef01234567";

  private static final String ANSWER =
      """
      {"requestId":"rr-1","foldSha":"%s","automations":[
        {"kind":"screenshot-baselines","label":"Screenshot baselines","state":"RUNNING",
         "detail":"running","bumpId":"b-1","runIds":["run-1","run-2"],
         "branch":"maintenance/automations/screenshot-baselines/rr-1","resultSha":null,
         "updatedAt":"2026-10-06T12:00:00Z"}]}
      """
          .formatted(FOLD);

  private record Received(
      String method, String path, String query, String auth, String user, String roles, String body) {}

  private HttpServer server;
  private final List<Received> received = new CopyOnWriteArrayList<>();
  private final AtomicInteger status = new AtomicInteger(200);
  private final AtomicReference<String> responseBody = new AtomicReference<>(ANSWER);

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
                  exchange.getRequestURI().getQuery(),
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

  private HttpReleaseRequestAutomations against(String base, Optional<String> authorization) {
    HttpReleaseRequestAutomations port = new HttpReleaseRequestAutomations();
    port.maintenanceUrl = Optional.ofNullable(base);
    port.bearer = () -> authorization;
    return port;
  }

  private HttpReleaseRequestAutomations against(String base) {
    return against(base, Optional.of("Bearer machine-token"));
  }

  private Optional<ReleaseRequestAutomations.Answer> trigger(
      HttpReleaseRequestAutomations port, List<String> changed) {
    return port.request(
        "qits-landing-app", "rr-1", FOLD, "fedcba9876543210fedcba9876543210fedcba98", changed,
        List.of("ticket/x"), null);
  }

  @Test
  void theTriggerPostsTheFoldAndReadsEveryFieldBack() throws Exception {
    String base = startServer();

    ReleaseRequestAutomations.Answer answer =
        trigger(against(base), List.of("src/app/a.ts")).orElseThrow();

    Received request = received.get(0);
    assertEquals("POST", request.method());
    assertEquals("/maintenance/api/release-requests/rr-1/automations", request.path());
    assertEquals("Bearer machine-token", request.auth());
    JsonNode body = MAPPER.readTree(request.body());
    assertEquals("qits-landing-app", body.get("repository").asText(), "by the catalogue NAME");
    assertEquals(FOLD, body.get("foldSha").asText());
    assertEquals("fedcba9876543210fedcba9876543210fedcba98", body.get("previousFoldSha").asText());
    assertEquals("src/app/a.ts", body.get("changedSincePrevious").get(0).asText());
    assertEquals("ticket/x", body.get("sourceBranches").get(0).asText());
    assertTrue(body.get("workItem").isNull());

    assertEquals(FOLD, answer.foldSha());
    ReleaseRequestAutomations.Automation entry = answer.automations().get(0);
    assertEquals("screenshot-baselines", entry.kind());
    assertEquals("Screenshot baselines", entry.label());
    assertEquals("RUNNING", entry.state());
    assertEquals(List.of("run-1", "run-2"), entry.runIds());
    assertEquals("maintenance/automations/screenshot-baselines/rr-1", entry.branch());
    assertNull(entry.resultSha());
    assertEquals(Instant.parse("2026-10-06T12:00:00Z"), entry.updatedAt());
    assertNull(entry.failure(), "an older qits-maintenance sends no failure, and that is no failure");
  }

  /** Null never carries an outcome over at the far side, empty says nothing changed: two answers. */
  @Test
  void anUnknownDiffTravelsAsNullAndAnEmptyOneAsEmpty() throws Exception {
    String base = startServer();

    trigger(against(base), null);
    trigger(against(base), List.of());

    assertTrue(MAPPER.readTree(received.get(0).body()).get("changedSincePrevious").isNull());
    JsonNode empty = MAPPER.readTree(received.get(1).body()).get("changedSincePrevious");
    assertTrue(empty.isArray() && empty.isEmpty());
  }

  @Test
  void theReadAsksAboutOneFold() throws Exception {
    String base = startServer();

    ReleaseRequestAutomations.Answer answer =
        against(base).status("qits-landing-app", "rr-1", FOLD).orElseThrow();

    Received request = received.get(0);
    assertEquals("GET", request.method());
    assertEquals("/maintenance/api/release-requests/rr-1/automations", request.path());
    assertEquals("foldSha=" + FOLD, request.query());
    assertEquals(1, answer.automations().size());
  }

  /** A red run's failure is read field for field; a null exit code and excerpt stay null. */
  @Test
  void aFailedAutomationCarriesItsFailure() throws Exception {
    String base = startServer();
    responseBody.set(
        """
        {"requestId":"rr-1","foldSha":"%s","automations":[
          {"kind":"screenshot-baselines","label":"Screenshot baselines","state":"FAILED",
           "bumpId":"b-1","runIds":["run-1"],
           "failure":{"stepIndex":2,"image":"node:22","exitCode":1,"excerpt":"3 screenshots differ"}},
          {"kind":"estate-pins","label":"Estate pins","state":"FAILED","bumpId":"b-2","runIds":[],
           "failure":{"stepIndex":0,"image":"alpine:3","exitCode":null,"excerpt":null}},
          {"kind":"openapi","label":"OpenAPI","state":"FRESH","bumpId":"b-3","runIds":[],
           "failure":null}]}
        """
            .formatted(FOLD));

    List<ReleaseRequestAutomations.Automation> entries =
        against(base).status("qits-landing-app", "rr-1", FOLD).orElseThrow().automations();

    assertEquals(
        new AutomationLedger.Failure(2, "node:22", 1, "3 screenshots differ"), entries.get(0).failure());
    assertEquals(new AutomationLedger.Failure(0, "alpine:3", null, null), entries.get(1).failure());
    assertNull(entries.get(2).failure(), "null is no failure");
  }

  @Test
  void anEmptyListIsAnAnswerAndNotAFailure() throws Exception {
    String base = startServer();
    responseBody.set("{\"requestId\":\"rr-1\",\"foldSha\":\"" + FOLD + "\",\"automations\":[]}");

    ReleaseRequestAutomations.Answer answer = trigger(against(base), null).orElseThrow();

    assertTrue(answer.automations().isEmpty(), "no kind applies, which releases as before");
  }

  /** No bearer is not a reason to stay silent: the fallback is the forwarded pair, as next door. */
  @Test
  void withNoBearerItAsksWithTheForwardedHeaderPair() throws Exception {
    String base = startServer();

    assertTrue(trigger(against(base, Optional.empty()), null).isPresent());

    Received request = received.get(0);
    assertNull(request.auth(), "no credential means no Authorization header at all");
    assertEquals("qits-projects", request.user());
    assertEquals("qits:system", request.roles());
  }

  @Test
  void aRefusalOrAnUnparseableAnswerIsCouldNotAskAndNeverThrown() throws Exception {
    String base = startServer();
    status.set(404);
    responseBody.set("{\"message\":\"no such repository\"}");
    assertEquals(Optional.empty(), assertDoesNotThrow(() -> trigger(against(base), null)));
    assertEquals(
        Optional.empty(),
        assertDoesNotThrow(() -> against(base).status("qits-landing-app", "rr-1", FOLD)));

    status.set(200);
    responseBody.set("not json");
    assertEquals(Optional.empty(), assertDoesNotThrow(() -> trigger(against(base), null)));
  }

  @Test
  void anUnreachableMaintenanceIsCouldNotAskAndNeverThrown() {
    // Port 1 on loopback: nothing listens, and connecting fails fast.
    HttpReleaseRequestAutomations port = against("http://127.0.0.1:1");
    assertEquals(Optional.empty(), assertDoesNotThrow(() -> trigger(port, null)));
    assertFalse(assertDoesNotThrow(() -> port.rerun("qits-landing-app", "rr-1", "x")).wasAccepted());
  }

  @Test
  void anUnsetOrBlankAddressIsNotConfiguredAndAsksNothing() throws Exception {
    String base = startServer();

    assertFalse(against(null).configured());
    assertFalse(against("   ").configured());
    assertTrue(against(base).configured());
    assertEquals(Optional.empty(), trigger(against(null), null));
    assertEquals(Optional.empty(), against("  ").status("qits-landing-app", "rr-1", FOLD));

    assertTrue(received.isEmpty(), "a switched-off hop dials nothing: " + received);
  }

  @Test
  void aRerunPostsTheRepositoryAndAnswersTheRunsId() throws Exception {
    String base = startServer();
    status.set(202);
    responseBody.set("{\"id\":\"bump-42\"}");

    ReleaseRequestAutomations.Run run =
        against(base).rerun("qits-landing-app", "rr-1", "screenshot-baselines");

    Received request = received.get(0);
    assertEquals("POST", request.method());
    assertEquals(
        "/maintenance/api/release-requests/rr-1/automations/screenshot-baselines/runs",
        request.path());
    JsonNode body = MAPPER.readTree(request.body());
    assertEquals(
        "qits-landing-app",
        body.get("repository").asText(),
        "sent, so a request's first run of a kind can be addressed");
    assertTrue(run.wasAccepted());
    assertEquals("bump-42", run.id());
  }

  /** A refusal is a fact the person pressing the button has not got: status and sentence kept. */
  @Test
  void aRerunRefusalKeepsItsStatusAndSentence() throws Exception {
    String base = startServer();
    status.set(409);
    responseBody.set("{\"message\":\"screenshot-baselines is already running\"}");

    ReleaseRequestAutomations.Run run =
        against(base).rerun("qits-landing-app", "rr-1", "screenshot-baselines");

    assertTrue(run.wasRefused());
    assertEquals(409, run.refusal());
    assertEquals("screenshot-baselines is already running", run.detail());
  }

  @Test
  void aRerunTheFarSideCouldNotServeIsUnreachable() throws Exception {
    String base = startServer();
    status.set(503);
    responseBody.set("{\"message\":\"down\"}");

    ReleaseRequestAutomations.Run run = against(base).rerun("qits-landing-app", "rr-1", "x");

    assertFalse(run.wasAccepted());
    assertFalse(run.wasRefused(), "a 5xx is not the request's fault, so it is no refusal");
  }
}
