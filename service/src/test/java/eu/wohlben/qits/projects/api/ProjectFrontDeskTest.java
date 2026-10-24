package eu.wohlben.qits.projects.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.agenthost.AgentTunnels;
import eu.wohlben.qits.projects.agenthost.ContainerProxyPath;
import eu.wohlben.qits.projects.agenthost.FakeProjectDaemon;
import eu.wohlben.qits.projects.security.FakeSessionIntrospection;
import eu.wohlben.qits.projects.security.PersonCheck;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import io.vertx.core.Vertx;
import jakarta.inject.Inject;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The front desk's prompt and last-answer doors (qits-1572), over HTTP, against a fake daemon
 * standing behind the real reverse tunnel ({@link FakeProjectDaemon}): who may knock, what reaches
 * the daemon, which of its answers pass through verbatim, and what this service says when it cannot
 * ask at all.
 *
 * <p>No profile of its own: the default application every door suite shares.
 */
@QuarkusTest
public class ProjectFrontDeskTest {

  private static final String PROMPTED =
      "{\"commandId\":\"cmd-1\",\"state\":\"RUNNING\",\"delivered\":true,\"queued\":false,"
          + "\"position\":null}";

  private static final String LAST_ANSWER =
      "{\"commandId\":\"cmd-1\",\"sessionId\":\"session-1\",\"state\":\"RUNNING\","
          + "\"text\":\"Two tickets are ready for dev.\",\"at\":\"2026-10-24T10:00:00Z\"}";

  @Inject Vertx vertx;

  @Inject AgentTunnels tunnels;

  @TestHTTPResource("/")
  URL root;

  private FakeProjectDaemon daemon;

  /** What the fake answers next, by route; set per test. */
  private final AtomicReference<Map<String, FakeProjectDaemon.Reply>> replies =
      new AtomicReference<>(Map.of());

  @AfterEach
  void closeTheDaemon() {
    if (daemon != null) {
      daemon.close();
      daemon = null;
    }
  }

  private RequestSpecification as(String user, String roles) {
    return given()
        .contentType(ContentType.JSON)
        .header("X-Qits-User", user)
        .header("X-Qits-Roles", roles);
  }

  private RequestSpecification asAdmin() {
    return as("dana", "qits:admin")
        .cookie(PersonCheck.SESSION_COOKIE, FakeSessionIntrospection.admin("dana"));
  }

  private String createProject(String name) {
    return asAdmin()
        .body(new ProjectController.CreateProjectRequest(name, null, null, null, ProjectRequests.DNS))
        .when()
        .post("/projects/api/projects")
        .then()
        .statusCode(200)
        .extract()
        .path("project.id");
  }

  private static String door(String projectId, String route) {
    return "/projects/api/projects/" + projectId + "/front-desk/" + route;
  }

  private void connectDaemon(String projectId) {
    daemon =
        FakeProjectDaemon.connect(
            vertx,
            tunnels,
            root.getHost(),
            root.getPort(),
            projectId,
            seen -> {
              for (var entry : replies.get().entrySet()) {
                if (seen.uri().endsWith("/" + entry.getKey())) {
                  return entry.getValue();
                }
              }
              return new FakeProjectDaemon.Reply(404, "{\"message\":\"No such endpoint\"}");
            });
  }

  // ---- roles -------------------------------------------------------------------------------------

  @Test
  public void anAgentAndTheDeskAreRefusedBothDoors() {
    String projectId = createProject("Front Desk Roles " + UUID.randomUUID());
    for (String roles : List.of("qits:agent", "qits:desk", "qits:agent,qits:desk")) {
      as("someone", roles)
          .body(Map.of("text", "hello"))
          .when()
          .post(door(projectId, "prompt"))
          .then()
          .statusCode(403);
      as("someone", roles).when().get(door(projectId, "last-answer")).then().statusCode(403);
    }
  }

  // ---- through the tunnel ------------------------------------------------------------------------

  @Test
  public void aPromptReachesTheDaemonWithItsBearerAndTheAnswerPassesThroughVerbatim() {
    String projectId = createProject("Front Desk Prompt " + UUID.randomUUID());
    replies.set(Map.of("front-desk/prompt", new FakeProjectDaemon.Reply(200, PROMPTED)));
    connectDaemon(projectId);

    String answered =
        asAdmin()
            .body(Map.of("text", "What is ready for dev?"))
            .when()
            .post(door(projectId, "prompt"))
            .then()
            .statusCode(200)
            .extract()
            .asString();
    assertEquals(PROMPTED, answered, "the daemon's body, byte for byte");

    FakeProjectDaemon.Seen seen = daemon.seen().get(0);
    assertEquals("POST", seen.method());
    assertEquals(ContainerProxyPath.base(projectId) + "front-desk/prompt", seen.uri());
    assertEquals("Bearer qits-projects-daemon", seen.authorization());
    assertEquals("localhost:13338", seen.host());
    assertEquals("{\"text\":\"What is ready for dev?\"}", seen.body());
  }

  @Test
  public void theLastAnswerPassesThroughVerbatim() {
    String projectId = createProject("Front Desk Last " + UUID.randomUUID());
    replies.set(Map.of("front-desk/last-answer", new FakeProjectDaemon.Reply(200, LAST_ANSWER)));
    connectDaemon(projectId);

    String answered =
        asAdmin()
            .when()
            .get(door(projectId, "last-answer"))
            .then()
            .statusCode(200)
            .extract()
            .asString();
    assertEquals(LAST_ANSWER, answered);
    FakeProjectDaemon.Seen seen = daemon.seen().get(0);
    assertEquals("GET", seen.method());
    assertEquals(ContainerProxyPath.base(projectId) + "front-desk/last-answer", seen.uri());
  }

  @Test
  public void theDaemonsRefusalsPassThroughWithTheirStatus() {
    String projectId = createProject("Front Desk Refusals " + UUID.randomUUID());
    connectDaemon(projectId);

    for (int status : List.of(400, 409, 429)) {
      String body = "{\"reason\":\"refused with " + status + "\"}";
      replies.set(Map.of("front-desk/prompt", new FakeProjectDaemon.Reply(status, body)));
      String answered =
          asAdmin()
              .body(Map.of("text", "hello"))
              .when()
              .post(door(projectId, "prompt"))
              .then()
              .statusCode(status)
              .extract()
              .asString();
      assertEquals(body, answered);
    }

    String none = "{\"reason\":\"no kept desk command\"}";
    replies.set(Map.of("front-desk/last-answer", new FakeProjectDaemon.Reply(404, none)));
    assertEquals(
        none,
        asAdmin()
            .when()
            .get(door(projectId, "last-answer"))
            .then()
            .statusCode(404)
            .extract()
            .asString());
  }

  /** A daemon whose router does not know the routes answers 404 without a reason: 409, said so. */
  @Test
  public void aDaemonTooOldForTheRoutesIsA409() {
    String projectId = createProject("Front Desk Old " + UUID.randomUUID());
    replies.set(Map.of());
    connectDaemon(projectId);

    asAdmin()
        .body(Map.of("text", "hello"))
        .when()
        .post(door(projectId, "prompt"))
        .then()
        .statusCode(409)
        .body("reason", containsString("too old"));
    asAdmin()
        .when()
        .get(door(projectId, "last-answer"))
        .then()
        .statusCode(409)
        .body("reason", containsString("too old"));
  }

  @Test
  public void aDaemonOutsideItsContractIsA503() {
    String projectId = createProject("Front Desk Booting " + UUID.randomUUID());
    replies.set(
        Map.of(
            "front-desk/prompt",
            new FakeProjectDaemon.Reply(503, "{\"message\":\"Coding agents are not available yet\"}")));
    connectDaemon(projectId);

    asAdmin()
        .body(Map.of("text", "hello"))
        .when()
        .post(door(projectId, "prompt"))
        .then()
        .statusCode(503)
        .body("reason", containsString("503"));
  }

  // ---- this service's own answers ----------------------------------------------------------------

  @Test
  public void noDaemonIsA503() {
    String projectId = createProject("Front Desk Absent " + UUID.randomUUID());
    asAdmin()
        .body(Map.of("text", "hello"))
        .when()
        .post(door(projectId, "prompt"))
        .then()
        .statusCode(503)
        .body("reason", containsString("No front desk daemon is connected"));
    asAdmin()
        .when()
        .get(door(projectId, "last-answer"))
        .then()
        .statusCode(503)
        .body("reason", containsString("No front desk daemon is connected"));
  }

  @Test
  public void blankTextIsA400BeforeAnythingIsProxied() {
    String projectId = createProject("Front Desk Blank " + UUID.randomUUID());
    replies.set(Map.of("front-desk/prompt", new FakeProjectDaemon.Reply(200, PROMPTED)));
    connectDaemon(projectId);

    for (Object body : List.of(Map.of("text", "   "), Map.of("text", ""), Map.of())) {
      asAdmin()
          .body(body)
          .when()
          .post(door(projectId, "prompt"))
          .then()
          .statusCode(400)
          .body("reason", equalTo("A prompt needs text."));
    }
    assertTrue(daemon.seen().isEmpty(), "a blank prompt never reaches the daemon");
  }

  @Test
  public void anUnknownProjectIsA404() {
    String unknown = UUID.randomUUID().toString();
    asAdmin()
        .body(Map.of("text", "hello"))
        .when()
        .post(door(unknown, "prompt"))
        .then()
        .statusCode(404)
        .body("reason", containsString(unknown));
    asAdmin().when().get(door(unknown, "last-answer")).then().statusCode(404);
  }
}
