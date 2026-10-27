package eu.wohlben.qits.projects.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.projects.entity.DeskTriggerEvent;
import eu.wohlben.qits.projects.entity.DeskTriggerWindow;
import eu.wohlben.qits.projects.entity.DeskTriggerWindowState;
import eu.wohlben.qits.projects.security.FakeSessionIntrospection;
import eu.wohlben.qits.projects.security.NoDevUserProfile;
import eu.wohlben.qits.projects.security.PersonCheck;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The desk trigger read door (qits-1612, epic qits-1604), over HTTP: what a window reads as — every
 * member present, nulls included — in what order windows and their lines come, how {@code limit} is
 * bounded, the unknown project's 404, and who may read it.
 *
 * <p>Windows are written straight to the tables, as the listener and the sweep would leave them:
 * this is the read, and the engine has its own suites. Each test makes a project of its own, so the
 * answer holds only what the test wrote.
 *
 * <p>Under {@link NoDevUserProfile}, the deployed posture: a request with no credential is
 * anonymous, not the {@code %test} admin, so every call names its caller as the edge does.
 */
@QuarkusTest
@TestProfile(NoDevUserProfile.class)
public class ProjectDeskTriggersTest {

  private static final Instant T0 = Instant.parse("2026-03-01T10:00:00Z");

  private static final Set<String> MESSAGE_MEMBERS =
      Set.of(
          "id",
          "state",
          "openedAt",
          "closesAt",
          "closedAt",
          "acceptedAt",
          "attempts",
          "lastStatus",
          "lastDetail",
          "commandId",
          "queuePosition",
          "message",
          "events");

  private static final Set<String> EVENT_MEMBERS =
      Set.of("source", "itemRef", "itemId", "label", "cause", "commentId", "eventId", "occurredAt");

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

  private String createProject() {
    return asAdmin()
        .body(
            new ProjectController.CreateProjectRequest(
                "Desk Triggers " + UUID.randomUUID(), null, null, null, ProjectRequests.DNS))
        .when()
        .post("/projects/api/projects")
        .then()
        .statusCode(200)
        .extract()
        .path("project.id");
  }

  private static String door(String projectId) {
    return "/projects/api/projects/" + projectId + "/desk-triggers";
  }

  /** A window of {@code projectId} opened at {@code openedAt}, in {@code state}. */
  private static UUID window(String projectId, Instant openedAt, DeskTriggerWindowState state) {
    UUID id = UUID.randomUUID();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              var window = new DeskTriggerWindow();
              window.id = id;
              window.projectId = projectId;
              window.openedAt = openedAt;
              window.closesAt = openedAt.plusSeconds(900);
              window.state = state;
              if (state != DeskTriggerWindowState.OPEN) {
                window.message = "You are the delivery desk of project p. (window " + id + ")";
                window.closedAt = window.closesAt.plusSeconds(5);
              }
              window.persist();
            });
    return id;
  }

  private static void line(UUID windowId, String source, String itemRef, Instant admittedAt) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              var event = new DeskTriggerEvent();
              event.id = UUID.randomUUID();
              event.windowId = windowId;
              event.source = source;
              event.itemRef = itemRef;
              event.eventId = UUID.randomUUID();
              event.occurredAt = admittedAt;
              event.admittedAt = admittedAt;
              event.persist();
            });
  }

  // ---- shape -------------------------------------------------------------------------------------

  @Test
  public void aDeliveredWindowReadsWithEveryMemberAndItsLines() {
    String projectId = createProject();
    UUID id = window(projectId, T0, DeskTriggerWindowState.DELIVERED);
    UUID eventId = UUID.randomUUID();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              DeskTriggerWindow window = DeskTriggerWindow.findById(id);
              window.attempts = 2;
              window.lastStatus = 200;
              window.acceptedAt = T0.plusSeconds(906);
              window.commandId = "desk-command-1";
              var event = new DeskTriggerEvent();
              event.id = UUID.randomUUID();
              event.windowId = id;
              event.source = "work-verified";
              event.itemRef = "qits-1580";
              event.itemId = "item-1580";
              event.label = "A title";
              event.cause = "tok-workspace-agent-1";
              event.eventId = eventId;
              event.occurredAt = T0;
              event.admittedAt = T0.plusSeconds(1);
              event.persist();
            });

    var body =
        asAdmin()
            .when()
            .get(door(projectId))
            .then()
            .statusCode(200)
            .body("messages", hasSize(1))
            .body("messages[0].id", equalTo(id.toString()))
            .body("messages[0].state", equalTo("DELIVERED"))
            .body("messages[0].openedAt", equalTo("2026-03-01T10:00:00Z"))
            .body("messages[0].closesAt", equalTo("2026-03-01T10:15:00Z"))
            .body("messages[0].closedAt", equalTo("2026-03-01T10:15:05Z"))
            .body("messages[0].acceptedAt", equalTo("2026-03-01T10:15:06Z"))
            .body("messages[0].attempts", equalTo(2))
            .body("messages[0].lastStatus", equalTo(200))
            .body("messages[0].lastDetail", nullValue())
            .body("messages[0].commandId", equalTo("desk-command-1"))
            .body("messages[0].queuePosition", nullValue())
            .body("messages[0].message", equalTo("You are the delivery desk of project p. (window " + id + ")"))
            .body("messages[0].events", hasSize(1))
            .body("messages[0].events[0].source", equalTo("work-verified"))
            .body("messages[0].events[0].itemRef", equalTo("qits-1580"))
            .body("messages[0].events[0].itemId", equalTo("item-1580"))
            .body("messages[0].events[0].label", equalTo("A title"))
            .body("messages[0].events[0].cause", equalTo("tok-workspace-agent-1"))
            .body("messages[0].events[0].commentId", nullValue())
            .body("messages[0].events[0].eventId", equalTo(eventId.toString()))
            .body("messages[0].events[0].occurredAt", equalTo("2026-03-01T10:00:00Z"))
            .extract()
            .jsonPath();
    Map<String, Object> message = body.getMap("messages[0]");
    assertEquals(MESSAGE_MEMBERS, message.keySet(), "every member, the nulls included");
    Map<String, Object> event = body.getMap("messages[0].events[0]");
    assertEquals(EVENT_MEMBERS, event.keySet(), "every member of a line");
  }

  /** An OPEN window has no message, close or delivery yet: those members are null, not absent. */
  @Test
  public void anOpenWindowReadsWithItsNullsPresent() {
    String projectId = createProject();
    window(projectId, T0, DeskTriggerWindowState.OPEN);

    var body =
        asAdmin()
            .when()
            .get(door(projectId))
            .then()
            .statusCode(200)
            .body("messages[0].state", equalTo("OPEN"))
            .body("messages[0].message", nullValue())
            .body("messages[0].closedAt", nullValue())
            .body("messages[0].acceptedAt", nullValue())
            .body("messages[0].lastStatus", nullValue())
            .body("messages[0].commandId", nullValue())
            .body("messages[0].attempts", equalTo(0))
            .body("messages[0].events", hasSize(0))
            .extract()
            .jsonPath();
    assertEquals(MESSAGE_MEMBERS, body.getMap("messages[0]").keySet());
  }

  /** A project with no window is an empty list. */
  @Test
  public void aProjectWithNoWindowReadsEmpty() {
    String projectId = createProject();
    asAdmin().when().get(door(projectId)).then().statusCode(200).body("messages", hasSize(0));
  }

  // ---- order -------------------------------------------------------------------------------------

  @Test
  public void windowsComeNewestFirstAndLinesByAdmissionThenItemRef() {
    String projectId = createProject();
    UUID oldest = window(projectId, T0, DeskTriggerWindowState.FAILED);
    UUID newest = window(projectId, T0.plusSeconds(7200), DeskTriggerWindowState.OPEN);
    UUID middle = window(projectId, T0.plusSeconds(3600), DeskTriggerWindowState.OWED);
    line(middle, "work-verified", "qits-9", T0.plusSeconds(3602));
    line(middle, "approval-needed", "qits-qits-rr-2", T0.plusSeconds(3601));
    line(middle, "work-verified", "qits-1", T0.plusSeconds(3601));

    asAdmin()
        .when()
        .get(door(projectId))
        .then()
        .statusCode(200)
        .body(
            "messages.id",
            contains(newest.toString(), middle.toString(), oldest.toString()))
        .body("messages.state", contains("OPEN", "OWED", "FAILED"))
        .body("messages[1].events.itemRef", contains("qits-1", "qits-qits-rr-2", "qits-9"));
  }

  /** Another project's windows are not this one's. */
  @Test
  public void anotherProjectsWindowsAreNotRead() {
    String projectId = createProject();
    String other = createProject();
    UUID own = window(projectId, T0, DeskTriggerWindowState.DELIVERED);
    window(other, T0.plusSeconds(60), DeskTriggerWindowState.DELIVERED);

    asAdmin()
        .when()
        .get(door(projectId))
        .then()
        .statusCode(200)
        .body("messages.id", contains(own.toString()));
  }

  // ---- limit -------------------------------------------------------------------------------------

  @Test
  public void limitDefaultsToTwentyAndTakesTheNewest() {
    String projectId = createProject();
    for (int i = 0; i < 21; i++) {
      window(projectId, T0.plusSeconds(60L * i), DeskTriggerWindowState.DELIVERED);
    }

    asAdmin()
        .when()
        .get(door(projectId))
        .then()
        .statusCode(200)
        .body("messages", hasSize(20))
        .body("messages[0].openedAt", equalTo("2026-03-01T10:20:00Z"))
        .body("messages[19].openedAt", equalTo("2026-03-01T10:01:00Z"));
    asAdmin()
        .queryParam("limit", 2)
        .when()
        .get(door(projectId))
        .then()
        .statusCode(200)
        .body("messages.openedAt", contains("2026-03-01T10:20:00Z", "2026-03-01T10:19:00Z"));
    asAdmin()
        .queryParam("limit", 100)
        .when()
        .get(door(projectId))
        .then()
        .statusCode(200)
        .body("messages", hasSize(21));
  }

  @Test
  public void aLimitOutsideOneToAHundredIsA400() {
    String projectId = createProject();
    for (int limit : List.of(0, -1, 101)) {
      asAdmin()
          .queryParam("limit", limit)
          .when()
          .get(door(projectId))
          .then()
          .statusCode(400);
    }
  }

  // ---- 404 and roles -----------------------------------------------------------------------------

  @Test
  public void anUnknownProjectIsA404() {
    asAdmin().when().get(door("no-such-project-" + UUID.randomUUID())).then().statusCode(404);
  }

  /** The desk's own token holds qits:agent; a platform service and an admin agent read it too. */
  @Test
  public void anAgentAndTheSystemReadIt() {
    String projectId = createProject();
    UUID id = window(projectId, T0, DeskTriggerWindowState.QUEUED);
    for (String roles : List.of("qits:agent", "qits:system", "qits:admin-agent")) {
      as("reader", roles)
          .when()
          .get(door(projectId))
          .then()
          .statusCode(200)
          .body("messages.id", contains(id.toString()));
    }
  }

  /** A caller holding none of the four roles — the desk's narrow {@code qits:desk} alone — is a 403. */
  @Test
  public void aCallerWithoutTheRolesIsA403() {
    String projectId = createProject();
    as("someone", "qits:desk").when().get(door(projectId)).then().statusCode(403);
  }

  @Test
  public void anAnonymousCallerIsA401() {
    String projectId = createProject();
    given().when().get(door(projectId)).then().statusCode(401);
  }
}
