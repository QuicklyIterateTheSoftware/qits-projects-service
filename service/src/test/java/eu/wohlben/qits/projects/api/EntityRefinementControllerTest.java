package eu.wohlben.qits.projects.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.refinementhost.FakeRefinementCredentials;
import eu.wohlben.qits.projects.refinementhost.FakeRefinementRuntime;
import eu.wohlben.qits.projects.testsupport.RecordingWorkspaceAgentDispatch;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The refine action for any archetype (qits-395): {@code POST/GET /entities/{id}/refinement}, the
 * refusals it shares with the deployed SPA's {@code POST /refinements}, and a ticket's resolving
 * transition discarding its room as an epic's always has.
 *
 * <p>No profile of its own: the default {@code @QuarkusTest} application every door suite shares
 * (the test-profile budget rule), against the refinement fakes and the recording dispatch port.
 */
@QuarkusTest
public class EntityRefinementControllerTest {

  @Inject RecordingWorkspaceAgentDispatch workspaces;

  @Inject FakeRefinementRuntime runtime;

  @Inject FakeRefinementCredentials credentials;

  @BeforeEach
  void reset() {
    workspaces.reset();
    runtime.reset();
    credentials.reset();
  }

  /** The dispatch fake answers its scripted references to every caller; leave none behind. */
  @AfterEach
  void forgetScriptedReferences() {
    workspaces.reset();
  }

  // ---- fixtures ------------------------------------------------------------------------------

  private static RequestSpecification asAdmin() {
    return given()
        .contentType(ContentType.JSON)
        .header("X-Qits-User", "dana")
        .header("X-Qits-Roles", "qits:admin");
  }

  private String createProject(String name) {
    return asAdmin()
        .body(
            new ProjectController.CreateProjectRequest(
                name, null, null, null, ProjectRequests.DNS))
        .when()
        .post("/projects/api/projects")
        .then()
        .statusCode(200)
        .extract()
        .path("project.id");
  }

  private String createTicket(String projectId, String title) {
    return asAdmin()
        .body(Map.of("title", title, "type", "BUG", "impetus", "something occurs here"))
        .when()
        .post("/projects/api/projects/" + projectId + "/tickets")
        .then()
        .statusCode(200)
        .extract()
        .path("ticket.id");
  }

  private String createEpic(String projectId, String title) {
    return asAdmin()
        .body(Map.of("title", title, "description", "The pitch."))
        .when()
        .post("/projects/api/projects/" + projectId + "/epics")
        .then()
        .statusCode(200)
        .extract()
        .path("epic.id");
  }

  private io.restassured.response.Response open(String entityId) {
    return asAdmin().when().post("/projects/api/entities/" + entityId + "/refinement");
  }

  private void transition(String archetypePath, String id, String target) {
    asAdmin()
        .body(Map.of("target", target))
        .when()
        .post("/projects/api/" + archetypePath + "/" + id + "/transition")
        .then()
        .statusCode(200);
  }

  // ---- a ticket opens a room -----------------------------------------------------------------

  @Test
  public void aTicketOpensARefinementAndASecondOpenAnswersTheSameRoom() {
    String projectId = createProject("Refine Ticket");
    String ticketId = createTicket(projectId, "Flaky login");

    Number first =
        open(ticketId)
            .then()
            .statusCode(200)
            .body("refinement.entityId", equalTo(ticketId))
            .body("refinement.epicId", equalTo(ticketId))
            .body("refinement.projectId", equalTo(projectId))
            .body("refinement.branch", equalTo("refining/flaky-login"))
            .body("refinement.parent", equalTo("main"))
            .body("refinement.label", equalTo("refining-flaky-login"))
            .extract()
            .path("refinement.id");

    Number second = open(ticketId).then().statusCode(200).extract().path("refinement.id");
    assertEquals(first.longValue(), second.longValue(), "one room per entity");

    Number found =
        asAdmin()
            .when()
            .get("/projects/api/entities/" + ticketId + "/refinement")
            .then()
            .statusCode(200)
            .body("refinement.entityId", equalTo(ticketId))
            .extract()
            .path("refinement.id");
    assertEquals(first.longValue(), found.longValue());
  }

  @Test
  public void theFindNeverCreatesAndTellsNoRoomFromNoEntity() {
    String projectId = createProject("Refine Find");
    String ticketId = createTicket(projectId, "Nobody refines me");

    asAdmin()
        .when()
        .get("/projects/api/entities/" + ticketId + "/refinement")
        .then()
        .statusCode(200)
        .body("refinement", nullValue());
    asAdmin()
        .when()
        .get("/projects/api/projects/" + projectId + "/refinements")
        .then()
        .statusCode(200)
        .body("refinements.size()", equalTo(0));

    asAdmin().when().get("/projects/api/entities/no-such-entity/refinement").then().statusCode(404);
    open("no-such-entity").then().statusCode(404);
  }

  // ---- the refusals --------------------------------------------------------------------------

  @Test
  public void aFeatureHasNoLifecycleAndIsRefused() {
    String projectId = createProject("Refine Feature");
    String epicId = createEpic(projectId, "Parent epic");
    String featureId =
        asAdmin()
            .body(Map.of("title", "A slice", "description", "part of it"))
            .when()
            .post("/projects/api/epics/" + epicId + "/features")
            .then()
            .statusCode(200)
            .extract()
            .path("feature.id");

    open(featureId)
        .then()
        .statusCode(409)
        .body("message", containsString("feature has no lifecycle"));
    asAdmin()
        .when()
        .get("/projects/api/projects/" + projectId + "/refinements")
        .then()
        .body("refinements.size()", equalTo(0));
  }

  @Test
  public void aRefinedTicketOrEpicIsRefused() {
    String projectId = createProject("Refine Frozen");
    String ticketId = createTicket(projectId, "Already decided");
    transition("tickets", ticketId, "REFINED");
    open(ticketId)
        .then()
        .statusCode(409)
        .body("message", containsString("is REFINED — only a REPORTED ticket can be refined"));

    String epicId = createEpic(projectId, "Already planned");
    transition("epics", epicId, "REFINED");
    open(epicId)
        .then()
        .statusCode(409)
        .body("message", containsString("only a REPORTED epic can be refined"));
  }

  @Test
  public void openingWhileADispatchedWorkspaceIsActiveOnTheEntityIsRefused() {
    String projectId = createProject("Refine Busy");
    String ticketId = createTicket(projectId, "Agent on it");
    workspaces.willReference(
        RecordingWorkspaceAgentDispatch.live(
            7L, "repo-1", "ticket-agent-on-it", "ticket/agent-on-it", ticketId, null));

    open(ticketId)
        .then()
        .statusCode(409)
        .body("message", containsString("has a dispatched agent working it"))
        .body("message", containsString("ticket/agent-on-it"));
    assertEquals(
        java.util.List.of(ticketId), workspaces.lookups().get(0).ticketIds(), "asked as a ticket");
    asAdmin()
        .when()
        .get("/projects/api/projects/" + projectId + "/refinements")
        .then()
        .body("refinements.size()", equalTo(0));
  }

  @Test
  public void aResolvedWorkspaceOrOneAboutAnotherEntityDoesNotBlockTheOpen() {
    String projectId = createProject("Refine Tidy");
    String epicId = createEpic(projectId, "Worked before");
    workspaces.willReference(
        RecordingWorkspaceAgentDispatch.resolved(
            8L,
            "repo-1",
            "epic-worked-before",
            "epic/worked-before",
            null,
            epicId,
            "INTEGRATED",
            java.time.Instant.parse("2026-09-01T00:00:00Z")),
        RecordingWorkspaceAgentDispatch.live(
            9L, "repo-1", "epic-other", "epic/other", null, "some-other-epic"));

    open(epicId).then().statusCode(200).body("refinement.entityId", equalTo(epicId));
    assertTrue(
        workspaces.lookups().get(0).epicIds().contains(epicId), "an epic is asked as an epic");
  }

  // ---- what the SPA still reads of the legacy shape -------------------------------------------

  /**
   * The epic-only {@code POST /refinements {"epicId"}} door is gone (qits-399) — {@code
   * RetiredEntityDoorsTest} pins what it answers now — but {@code epicId} stays on {@link
   * RefinementDto}: the SPA reads {@code row.entityId ?? row.epicId}, so a room opened on an epic
   * keeps answering with both on the listing and on the row read.
   */
  @Test
  public void theProjectListingAndTheRowReadStillCarryEpicId() {
    String projectId = createProject("Refine Legacy");
    String epicId = createEpic(projectId, "Legacy epic");

    Number room =
        open(epicId)
            .then()
            .statusCode(200)
            .body("refinement.epicId", equalTo(epicId))
            .body("refinement.entityId", equalTo(epicId))
            .body("refinement.branch", equalTo("refining/legacy-epic"))
            .extract()
            .path("refinement.id");

    asAdmin()
        .when()
        .get("/projects/api/projects/" + projectId + "/refinements")
        .then()
        .statusCode(200)
        .body("refinements.epicId", hasItem(epicId))
        .body("refinements.entityId", hasItem(epicId));
    asAdmin()
        .when()
        .get("/projects/api/refinements/" + room)
        .then()
        .statusCode(200)
        .body("refinement.epicId", equalTo(epicId));
  }

  // ---- a ticket's resolving move discards its room ------------------------------------------

  @Test
  public void droppingATicketDiscardsItsRoomAndTheFreezeDoesNot() {
    String projectId = createProject("Refine Drop");
    String kept = createTicket(projectId, "Frozen but kept");
    String dropped = createTicket(projectId, "Not worth doing");
    Number keptRoom = open(kept).then().statusCode(200).extract().path("refinement.id");
    Number droppedRoom = open(dropped).then().statusCode(200).extract().path("refinement.id");

    transition("tickets", kept, "REFINED");
    transition("tickets", dropped, "DROPPED");

    asAdmin()
        .when()
        .get("/projects/api/projects/" + projectId + "/refinements")
        .then()
        .statusCode(200)
        .body("refinements.id", hasItem(keptRoom.intValue()))
        .body("refinements.id", not(hasItem(droppedRoom.intValue())));
    assertTrue(
        runtime.calls().contains("delete:" + droppedRoom.longValue()),
        "the container went with the room: " + runtime.calls());
  }
}
