package eu.wohlben.qits.projects.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.projects.agenthost.AgentTunnels;
import eu.wohlben.qits.projects.agenthost.ContainerProxyPath;
import eu.wohlben.qits.projects.agenthost.FakeProjectDaemon;
import eu.wohlben.qits.projects.contracts.ContractBearers;
import eu.wohlben.qits.projects.control.DevelopmentGuideDefaults;
import eu.wohlben.qits.projects.control.DevelopmentGuideService;
import eu.wohlben.qits.projects.deskhost.FakeFrontDeskTokens;
import eu.wohlben.qits.projects.deskhost.FrontDeskHooks;
import eu.wohlben.qits.projects.deskhost.FrontDeskTokenReconcile;
import eu.wohlben.qits.projects.deskhost.FrontDeskTokens;
import eu.wohlben.qits.projects.deskhost.FrontDesks;
import eu.wohlben.qits.projects.deskhost.NamedAgentHost;
import eu.wohlben.qits.projects.entity.FrontDesk;
import eu.wohlben.qits.projects.entity.NamedAgent;
import eu.wohlben.qits.projects.persistence.NamedAgentRepository;
import eu.wohlben.qits.projects.refinementhost.RefinementSessions;
import eu.wohlben.qits.projects.security.FakeSessionIntrospection;
import eu.wohlben.qits.projects.security.PersonCheck;
import eu.wohlben.qits.projectsdaemon.protocol.DaemonProtocol;
import eu.wohlben.qits.projectsdaemon.protocol.DeskBrief;
import eu.wohlben.qits.projectsdaemon.protocol.SessionReport;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import java.net.URL;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The named agents' doors (qits-2154), over HTTP, against a fake daemon standing behind the real
 * reverse tunnel ({@link FakeProjectDaemon}, announcing capability 4): who may knock, the listing,
 * a prompt to the UI builder delivered, held while the desk is down and delivered once its daemon
 * dials home, a second one refused meanwhile, the desk's prompt waking the desk and keeping its
 * brief pointer, the last answer, the session id kept from a report, and the agent's token kept by
 * the reconcile and revoked with the project.
 */
@QuarkusTest
public class ProjectAgentsTest {

  /** The capability that hosts named agents. */
  private static final int NAMED_AGENTS = 4;

  private static final String PROMPTED =
      "{\"commandId\":\"cmd-ui\",\"state\":\"IDLE\",\"delivered\":true,\"queued\":false,"
          + "\"position\":null}";

  private static final String LISTED =
      "{\"agents\":[{\"name\":\"ui-builder\",\"key\":\"agent:ui-builder\","
          + "\"surface\":\"project.ui-builder\",\"state\":\"RUNNING\",\"commandId\":\"cmd-ui\","
          + "\"sessionId\":\"sess-ui\"}]}";

  private static final String ANSWERED =
      "{\"commandId\":\"cmd-ui\",\"sessionId\":\"sess-ui\",\"state\":\"IDLE\","
          + "\"text\":\"Pitched one snapshot.\",\"at\":\"2026-11-13T12:00:07.000Z\"}";

  private static final String DESK_PROMPTED =
      "{\"commandId\":\"desk-cmd\",\"state\":\"RUNNING\",\"delivered\":true,\"queued\":false,"
          + "\"position\":null}";

  @Inject Vertx vertx;

  @Inject AgentTunnels tunnels;

  @Inject DevelopmentGuideService guide;

  @Inject ObjectMapper json;

  @Inject NamedAgentRepository rows;

  @Inject FrontDesks frontDesks;

  @Inject FakeFrontDeskTokens tokens;

  @Inject FrontDeskTokenReconcile reconcile;

  @Inject FrontDeskHooks hooks;

  @Inject RefinementSessions sessions;

  /** What the registry hands a {@code SessionReport} to, beside {@link #sessions}. */
  @Inject NamedAgentHost host;

  @TestHTTPResource("/")
  URL root;

  private FakeProjectDaemon daemon;

  private String projectId;

  /** What the fake answers next, by route suffix; set per test. */
  private final AtomicReference<Map<String, FakeProjectDaemon.Reply>> replies =
      new AtomicReference<>(Map.of());

  @BeforeEach
  void arrange() {
    tokens.reset();
  }

  @AfterEach
  void tidy() {
    if (daemon != null) {
      daemon.close();
      daemon = null;
    }
    if (projectId != null) {
      hooks.projectDeleting(projectId);
      projectId = null;
    }
  }

  // ---- fixtures ----------------------------------------------------------------------------------

  private RequestSpecification as(String user, String roles) {
    return given()
        .contentType(ContentType.JSON)
        .header("Authorization", ContractBearers.caller(user, roles));
  }

  private RequestSpecification asAdmin() {
    return as("dana", "qits:admin")
        .cookie(PersonCheck.SESSION_COOKIE, FakeSessionIntrospection.admin("dana"));
  }

  private String createProject(String name) {
    projectId =
        asAdmin()
            .body(
                new ProjectController.CreateProjectRequest(
                    name, null, null, null, ProjectRequests.DNS))
            .when()
            .post("/projects/api/projects")
            .then()
            .statusCode(200)
            .extract()
            .path("project.id");
    return projectId;
  }

  private static String agents(String projectId) {
    return "/projects/api/projects/" + projectId + "/agents";
  }

  private static String door(String projectId, String name, String route) {
    return agents(projectId) + "/" + name + "/" + route;
  }

  private void connectDaemon(String projectId) {
    daemon =
        FakeProjectDaemon.connect(
            vertx,
            tunnels,
            root.getHost(),
            root.getPort(),
            projectId,
            NAMED_AGENTS,
            seen -> {
              for (var entry : replies.get().entrySet()) {
                if (seen.uri().endsWith("/" + entry.getKey())) {
                  return entry.getValue();
                }
              }
              return new FakeProjectDaemon.Reply(404, "{\"message\":\"No such endpoint\"}");
            });
  }

  private NamedAgent row(String name) {
    return QuarkusTransaction.requiringNew().call(() -> rows.find(projectId, name));
  }

  private static void await(String what, BooleanSupplier condition) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 15_000;
    while (!condition.getAsBoolean()) {
      if (System.currentTimeMillis() > deadline) {
        throw new AssertionError("timed out waiting for " + what);
      }
      Thread.sleep(50);
    }
  }

  private List<FakeProjectDaemon.Seen> seen(String suffix) {
    return daemon.seen().stream().filter(s -> s.uri().endsWith(suffix)).toList();
  }

  // ---- roles and refusals ------------------------------------------------------------------------

  @Test
  public void anAgentAndTheDeskAreRefusedEveryDoor() {
    String projectId = createProject("Named Agent Roles " + UUID.randomUUID());
    for (String roles : List.of("qits:agent", "qits:desk", "qits:agent,qits:desk")) {
      as("someone", roles).when().get(agents(projectId)).then().statusCode(403);
      as("someone", roles)
          .body(Map.of("text", "hello"))
          .when()
          .post(door(projectId, "ui-builder", "prompt"))
          .then()
          .statusCode(403);
      as("someone", roles)
          .when()
          .get(door(projectId, "ui-builder", "last-answer"))
          .then()
          .statusCode(403);
    }
    assertNull(row("ui-builder"), "a refused prompt creates nothing");
  }

  @Test
  public void anUnknownNameAnUnknownProjectAndBlankTextAreRefusedBeforeAnythingMoves() {
    String projectId = createProject("Named Agent Unknown " + UUID.randomUUID());
    asAdmin()
        .body(Map.of("text", "hello"))
        .when()
        .post(door(projectId, "front-desk", "prompt"))
        .then()
        .statusCode(404)
        .body("reason", containsString("ui-builder"));
    asAdmin()
        .when()
        .get(door(projectId, "nobody", "last-answer"))
        .then()
        .statusCode(404);
    asAdmin()
        .body(Map.of("text", "hello"))
        .when()
        .post(door(UUID.randomUUID().toString(), "ui-builder", "prompt"))
        .then()
        .statusCode(404);
    asAdmin()
        .body(Map.of("text", "  "))
        .when()
        .post(door(projectId, "ui-builder", "prompt"))
        .then()
        .statusCode(400);
    assertNull(row("ui-builder"));
    assertTrue(frontDesks.find(projectId).isEmpty(), "no desk was woken for a refused prompt");
  }

  // ---- the listing -------------------------------------------------------------------------------

  @Test
  public void everyProjectListsTheDeskAndTheUiBuilderPromptedOrNot() {
    String projectId = createProject("Named Agent List " + UUID.randomUUID());

    asAdmin()
        .when()
        .get(agents(projectId))
        .then()
        .statusCode(200)
        .body("agents.name", equalTo(List.of("desk", "ui-builder")))
        .body("agents.surface", equalTo(List.of("project.work", "project.ui-builder")))
        .body("agents.state", equalTo(List.of("STOPPED", "STOPPED")))
        .body("agents[1].sessionId", equalTo(null))
        .body("agents[1].commandId", equalTo(null));
  }

  @Test
  public void aConnectedDaemonSaysWhichCommandRunsEachAgent() {
    String projectId = createProject("Named Agent Listed " + UUID.randomUUID());
    replies.set(
        Map.of(
            "named-agents", new FakeProjectDaemon.Reply(200, LISTED),
            "front-desk/last-answer",
                new FakeProjectDaemon.Reply(
                    200,
                    "{\"commandId\":\"desk-cmd\",\"sessionId\":\"desk-sess\",\"state\":\"BUSY\","
                        + "\"text\":null,\"at\":null}")));
    connectDaemon(projectId);

    asAdmin()
        .when()
        .get(agents(projectId))
        .then()
        .statusCode(200)
        .body("agents[0].state", equalTo("BUSY"))
        .body("agents[0].commandId", equalTo("desk-cmd"))
        .body("agents[0].sessionId", equalTo("desk-sess"))
        .body("agents[1].state", equalTo("IDLE"))
        .body("agents[1].commandId", equalTo("cmd-ui"))
        .body("agents[1].sessionId", equalTo("sess-ui"));
  }

  // ---- the prompt --------------------------------------------------------------------------------

  @Test
  public void aPromptToAConnectedUiBuilderIsForwardedWithoutTheDesksBrief() throws Exception {
    String projectId = createProject("Named Agent Prompt " + UUID.randomUUID());
    replies.set(Map.of("named-agents/ui-builder/prompt", new FakeProjectDaemon.Reply(200, PROMPTED)));
    connectDaemon(projectId);

    String answered =
        asAdmin()
            .body(Map.of("text", "Pitch a calmer header."))
            .when()
            .post(door(projectId, "ui-builder", "prompt"))
            .then()
            .statusCode(200)
            .extract()
            .asString();
    assertEquals(PROMPTED, answered, "the daemon's body, byte for byte");

    FakeProjectDaemon.Seen seen = seen("/named-agents/ui-builder/prompt").get(0);
    assertEquals("POST", seen.method());
    assertEquals(
        ContainerProxyPath.base(projectId) + "named-agents/ui-builder/prompt", seen.uri());
    assertEquals("Bearer qits-projects-daemon", seen.authorization());
    assertEquals(
        "Pitch a calmer header.",
        json.readTree(seen.body()).get("text").asText(),
        "a named agent's prompt never carries the desk's brief pointer");

    NamedAgent row = row("ui-builder");
    assertNotNull(row, "created on first use");
    assertEquals("project.ui-builder", row.surface);
    assertNotNull(row.lastDemandAt);
    assertNull(row.pendingPrompt);
    assertEquals(FrontDeskTokens.CONTEXT_KIND_NAMED_AGENT, tokens.kindOf(row.tokenId));
    assertEquals(1, tokens.namedAgentMintsFor(projectId, "ui-builder"));
    FrontDesk desk = frontDesks.find(projectId).orElseThrow();
    assertTrue(desk.wanted(), "the desk's demand was stamped: it runs the agent");
    assertNotNull(desk.lastDemandAt);

    // The daemon was told: its credential under agent:ui-builder, then the agent in the frame.
    await(
        "a sessions frame naming agent:ui-builder",
        () ->
            daemon.frames(DaemonProtocol.Type.SESSIONS).stream()
                .anyMatch(frame -> entry(frame, "agent:ui-builder") != null));
    JsonObject entry =
        daemon.frames(DaemonProtocol.Type.SESSIONS).stream()
            .map(frame -> entry(frame, "agent:ui-builder"))
            .filter(java.util.Objects::nonNull)
            .reduce((first, second) -> second)
            .orElseThrow();
    assertEquals("project.ui-builder", entry.getString("surface"));
    assertEquals(DaemonProtocol.DesiredState.RUNNING, entry.getString("state"));
    assertTrue(
        daemon.frames(DaemonProtocol.Type.SESSION_CREDENTIALS).stream()
            .anyMatch(
                frame ->
                    credentials(frame).stream()
                        .anyMatch(c -> "agent:ui-builder".equals(c.getString("key"))
                            && row.tokenSubject.equals(c.getString("subject")))),
        "its own token, under its own key");

    // The same agent, prompted again, is not minted a second token.
    asAdmin()
        .body(Map.of("text", "And the footer."))
        .when()
        .post(door(projectId, "ui-builder", "prompt"))
        .then()
        .statusCode(200);
    assertEquals(1, tokens.namedAgentMintsFor(projectId, "ui-builder"));
  }

  @Test
  public void aPromptWhileTheDeskIsDownIsHeldStartsTheDeskAndIsDeliveredOnceItsDaemonDials()
      throws Exception {
    String projectId = createProject("Named Agent Held " + UUID.randomUUID());

    asAdmin()
        .body(Map.of("text", "Pitch a calmer header."))
        .when()
        .post(door(projectId, "ui-builder", "prompt"))
        .then()
        .statusCode(202)
        .body("state", equalTo("STARTING"))
        .body("queued", equalTo(true))
        .body("delivered", equalTo(false))
        .body("commandId", equalTo(null));
    assertEquals("Pitch a calmer header.", row("ui-builder").pendingPrompt);
    assertTrue(frontDesks.find(projectId).orElseThrow().wanted(), "the stopped desk was started");

    asAdmin()
        .body(Map.of("text", "A second thought."))
        .when()
        .post(door(projectId, "ui-builder", "prompt"))
        .then()
        .statusCode(409)
        .body("reason", containsString("already waiting"));
    assertEquals("Pitch a calmer header.", row("ui-builder").pendingPrompt, "the first one stands");

    asAdmin()
        .when()
        .get(agents(projectId))
        .then()
        .statusCode(200)
        .body("agents[1].state", equalTo("STARTING"));
    asAdmin()
        .when()
        .get(door(projectId, "ui-builder", "last-answer"))
        .then()
        .statusCode(503);

    replies.set(Map.of("named-agents/ui-builder/prompt", new FakeProjectDaemon.Reply(200, PROMPTED)));
    connectDaemon(projectId);

    await("the held prompt delivered", () -> row("ui-builder").pendingPrompt == null);
    List<FakeProjectDaemon.Seen> delivered = seen("/named-agents/ui-builder/prompt");
    assertEquals(1, delivered.size(), "delivered once");
    assertEquals(
        "Pitch a calmer header.", json.readTree(delivered.get(0).body()).get("text").asText());
  }

  @Test
  public void aDaemonThatHasNotTakenTheFrameYetHoldsThePromptRatherThanRefusingIt()
      throws Exception {
    String projectId = createProject("Named Agent Race " + UUID.randomUUID());
    replies.set(
        Map.of(
            "named-agents/ui-builder/prompt",
            new FakeProjectDaemon.Reply(404, "{\"reason\":\"ui-builder is not in the frame\"}")));
    connectDaemon(projectId);

    asAdmin()
        .body(Map.of("text", "Pitch a calmer header."))
        .when()
        .post(door(projectId, "ui-builder", "prompt"))
        .then()
        .statusCode(202)
        .body("state", equalTo("STARTING"));
    assertEquals("Pitch a calmer header.", row("ui-builder").pendingPrompt);

    // The report naming the agent is the daemon saying it holds the frame: the registry hands it
    // to both, the session id to the row and the held prompt to the daemon.
    replies.set(Map.of("named-agents/ui-builder/prompt", new FakeProjectDaemon.Reply(200, PROMPTED)));
    sessions.onReport(
        projectId,
        new SessionReport(
            List.of(
                new SessionReport.Session(
                    "agent:ui-builder", "STARTING", "cmd-ui", "sess-ui-7", null))));
    host.onReport(
        projectId,
        new SessionReport(
            List.of(
                new SessionReport.Session(
                    "agent:ui-builder", "STARTING", "cmd-ui", "sess-ui-7", null))));

    await("the held prompt delivered", () -> row("ui-builder").pendingPrompt == null);
    assertEquals("sess-ui-7", row("ui-builder").sessionId, "the reported session id is kept");
  }

  @Test
  public void theDaemonsRefusalsPassThrough() {
    String projectId = createProject("Named Agent Refusals " + UUID.randomUUID());
    connectDaemon(projectId);
    for (int status : List.of(409, 429)) {
      String body = "{\"reason\":\"refused with " + status + "\"}";
      replies.set(
          Map.of("named-agents/ui-builder/prompt", new FakeProjectDaemon.Reply(status, body)));
      assertEquals(
          body,
          asAdmin()
              .body(Map.of("text", "hello"))
              .when()
              .post(door(projectId, "ui-builder", "prompt"))
              .then()
              .statusCode(status)
              .extract()
              .asString());
    }
    assertNull(row("ui-builder").pendingPrompt, "a refusal is not held");
  }

  // ---- the desk ----------------------------------------------------------------------------------

  @Test
  public void promptingTheDeskWakesItAndKeepsTheBriefPointer() throws Exception {
    String projectId = createProject("Named Agent Desk " + UUID.randomUUID());

    asAdmin()
        .body(Map.of("text", "What is ready for dev?"))
        .when()
        .post(door(projectId, "desk", "prompt"))
        .then()
        .statusCode(503)
        .body("reason", containsString("No front desk daemon"));
    FrontDesk desk = frontDesks.find(projectId).orElseThrow();
    assertTrue(desk.wanted(), "a stopped desk is started by prompting it");
    assertNull(row("desk"), "the desk keeps its own row and never has a named_agent one");

    replies.set(Map.of("front-desk/prompt", new FakeProjectDaemon.Reply(200, DESK_PROMPTED)));
    connectDaemon(projectId);
    assertEquals(
        DESK_PROMPTED,
        asAdmin()
            .body(Map.of("text", "What is ready for dev?"))
            .when()
            .post(door(projectId, "desk", "prompt"))
            .then()
            .statusCode(200)
            .extract()
            .asString());
    FakeProjectDaemon.Seen seen = seen("/front-desk/prompt").get(0);
    assertEquals(
        DeskBrief.pointer(guide.renderHash(DevelopmentGuideDefaults.DESK))
            + "\n\nWhat is ready for dev?",
        json.readTree(seen.body()).get("text").asText());
  }

  // ---- the last answer ---------------------------------------------------------------------------

  @Test
  public void theLastAnswerIsTheDaemonsVerbatim() {
    String projectId = createProject("Named Agent Answer " + UUID.randomUUID());
    replies.set(
        Map.of("named-agents/ui-builder/last-answer", new FakeProjectDaemon.Reply(200, ANSWERED)));
    connectDaemon(projectId);

    assertEquals(
        ANSWERED,
        asAdmin()
            .when()
            .get(door(projectId, "ui-builder", "last-answer"))
            .then()
            .statusCode(200)
            .extract()
            .asString());
    assertEquals(
        ContainerProxyPath.base(projectId) + "named-agents/ui-builder/last-answer",
        seen("/last-answer").get(0).uri());
  }

  // ---- the token ---------------------------------------------------------------------------------

  @Test
  public void theReconcileKeepsANamedAgentsTokenAndReapsAnOrphanAndTheProjectTakesItsOwn()
      throws Exception {
    String projectId = createProject("Named Agent Token " + UUID.randomUUID());
    asAdmin()
        .body(Map.of("text", "Pitch a calmer header."))
        .when()
        .post(door(projectId, "ui-builder", "prompt"))
        .then()
        .statusCode(202);
    String owned = row("ui-builder").tokenId;
    tokens.plant(
        new FrontDeskTokens.Live(
            "n-orphan",
            FrontDeskTokens.CONTEXT_KIND_NAMED_AGENT,
            projectId,
            Instant.now().minusSeconds(3600)));

    reconcile.reconcile(Instant.now());

    assertTrue(tokens.isLive(owned), "a token a named agent's row holds is owned");
    assertFalse(tokens.isLive("n-orphan"), "one no row names is reaped");

    hooks.projectDeleting(projectId);
    assertFalse(tokens.isLive(owned), "the project's deletion revokes its agents' tokens");
    assertNull(row("ui-builder"));
  }

  // ---- helpers -----------------------------------------------------------------------------------

  private static JsonObject entry(JsonObject frame, String key) {
    JsonArray list = frame.getJsonArray("sessions");
    if (list == null) {
      return null;
    }
    for (int i = 0; i < list.size(); i++) {
      JsonObject session = list.getJsonObject(i);
      if (key.equals(session.getString("key"))) {
        return session;
      }
    }
    return null;
  }

  private static List<JsonObject> credentials(JsonObject frame) {
    JsonArray list = frame.getJsonArray("credentials");
    if (list == null) {
      return List.of();
    }
    return list.stream().map(JsonObject.class::cast).toList();
  }
}
