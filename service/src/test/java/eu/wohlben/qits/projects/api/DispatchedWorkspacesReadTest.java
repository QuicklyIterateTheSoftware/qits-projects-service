package eu.wohlben.qits.projects.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.entities.api.WorkRequests;
import eu.wohlben.qits.projects.control.WorkspaceAgentDispatch;
import eu.wohlben.qits.projects.testsupport.RecordingWorkspaceAgentDispatch;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The read back: the workspaces working on a ticket or an epic, so "Assign agent" and "Start
 * implementation" stop offering a second one after a reload. Since qits-976 the per-archetype reads
 * and their {@code workspaces} field are gone, and the same lookup is {@code GET
 * /work/{qualifiedId}/workspaces} ({@link WorkWorkspacesController}).
 *
 * <p>What is pinned here is this service's half — that the read asks, that it asks <b>once</b> and
 * about the one row it names, and that what comes back lands on that row. Which workspaces exist is
 * qits-workspaces' answer and is scripted rather than simulated; {@code HttpWorkspaceAgentDispatchTest}
 * is where the wire is under test. The listings no longer carry workspaces at all, so the old
 * once-per-listing batching has nothing left to pin.
 */
@QuarkusTest
public class DispatchedWorkspacesReadTest {

  @Inject RecordingWorkspaceAgentDispatch dispatch;

  @BeforeEach
  void resetThePort() {
    dispatch.reset();
  }

  private RequestSpecification asAdmin() {
    return given()
        .contentType(ContentType.JSON)
        .header("X-Qits-User", "dana")
        .header("X-Qits-Roles", "qits:admin");
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

  private String createTicket(String projectId, String title) {
    return WorkRequests.ticket(
        this::asAdmin, projectId, title, "BUG", "something occurs in this project");
  }

  private String createEpic(String projectId, String title) {
    return WorkRequests.epic(this::asAdmin, projectId, title);
  }

  private io.restassured.response.ValidatableResponse workspacesOf(String id) {
    return asAdmin().when().get("/projects/api/work/" + id + "/workspaces").then().statusCode(200);
  }

  private static WorkspaceAgentDispatch.Reference onTicket(long rowId, String ticketId) {
    return RecordingWorkspaceAgentDispatch.live(
        rowId, "repo-1", "ticket-work", "ticket/work", ticketId, null);
  }

  /** The same workspace after somebody integrated it: still named, and no longer standing. */
  private static WorkspaceAgentDispatch.Reference resolvedOnTicket(long rowId, String ticketId) {
    return RecordingWorkspaceAgentDispatch.resolved(
        rowId,
        "repo-1",
        "ticket-work-done",
        "ticket/work-done",
        ticketId,
        null,
        "INTEGRATED",
        Instant.parse("2026-09-20T11:00:00Z"));
  }

  @Test
  public void aTicketsReadCarriesThemAndAWriteDoesNotAsk() {
    String projectId = createProject("Referenced ticket detail");
    String ticketId = createTicket(projectId, "Read me");
    createTicket(projectId, "Nobody is on this");
    dispatch.willReference(onTicket(41L, ticketId), onTicket(42L, ticketId));

    workspacesOf(ticketId)
        // Two workspaces on one ticket is a real answer and the SPA draws two links for it.
        .body("workspaces", hasSize(2))
        .body("workspaces[0].workspaceRowId", equalTo(41))
        .body("workspaces[0].repositoryId", equalTo("repo-1"))
        .body("workspaces[0].branch", equalTo("ticket/work"))
        // The status travels with every row, live ones included — the browser draws the difference,
        // so "this one is still running" has to be a word on the wire and not an absence.
        .body("workspaces[0].status", equalTo("ACTIVE"));

    // One lookup, about this ticket alone — and no epic half asked.
    assertEquals(1, dispatch.lookups().size());
    RecordingWorkspaceAgentDispatch.Looked asked = dispatch.lookups().get(0);
    assertEquals(List.of(ticketId), asked.ticketIds());
    assertTrue(asked.epicIds().isEmpty(), "a ticket's read asked about epics");

    // An edit answers the row it changed and asks nobody who is working on it.
    int lookups = dispatch.lookups().size();
    asAdmin()
        .body(Map.of("blocked", true, "reason", "waiting on a sibling"))
        .when()
        .post("/projects/api/work/" + ticketId + "/blocked")
        .then()
        .statusCode(200);
    assertEquals(lookups, dispatch.lookups().size(), "a write asked a sibling service who is working");
  }

  @Test
  public void anEpicsWorkspacesAreReadToo() {
    String projectId = createProject("Referenced epics");
    String epicId = createEpic(projectId, "Somebody is implementing this");
    dispatch.willReference(
        RecordingWorkspaceAgentDispatch.live(77L, "repo-1", "epic-work", "epic/work", null, epicId));

    workspacesOf(epicId).body("workspaces", hasSize(1)).body("workspaces[0].workspaceRowId", equalTo(77));

    assertEquals(1, dispatch.lookups().size());
    assertEquals(List.of(epicId), dispatch.lookups().get(0).epicIds());
    assertTrue(dispatch.lookups().get(0).ticketIds().isEmpty(), "an epic's read asked about tickets");
  }

  /** Only a ticket and an epic are dispatched onto a branch: a feature answers empty, asking nobody. */
  @Test
  public void aFeatureHasNoWorkspacesAndAsksNobody() {
    String projectId = createProject("Referenced feature");
    String featureId =
        WorkRequests.feature(this::asAdmin, createEpic(projectId, "Holds a feature"), "A feature");

    workspacesOf(featureId).body("workspaces", hasSize(0));
    assertTrue(dispatch.lookups().isEmpty(), "a feature's read asked qits-workspaces");
  }

  /**
   * <b>A resolved workspace reaches the ticket, carrying the word that says so.</b> This is the
   * whole point of the change: the workspace is where the work happened — the transcripts, the diff,
   * the session — and dropping the reference the moment somebody integrated or abandoned it took the
   * only way back to them with it. So it stays on the list and says what it is, and every reader
   * decides for itself what that means. Nothing in this service filters it out on the read.
   */
  @Test
  public void aResolvedWorkspaceStillReachesTheTicketAndSaysThatItIsResolved() {
    String projectId = createProject("Referenced after the fact");
    String ticketId = createTicket(projectId, "The work is done");
    dispatch.willReference(resolvedOnTicket(58L, ticketId), onTicket(59L, ticketId));

    asAdmin()
        .when()
        .get("/projects/api/work/" + ticketId + "/workspaces")
        .then()
        .statusCode(200)
        // Both of them: one that is over and one that is still running.
        .body("workspaces", hasSize(2))
        .body(
            "workspaces.find { it.workspaceRowId == 58 }.status", equalTo("INTEGRATED"))
        .body(
            "workspaces.find { it.workspaceRowId == 58 }.branch",
            equalTo("ticket/work-done"))
        .body("workspaces.find { it.workspaceRowId == 59 }.status", equalTo("ACTIVE"))
        // resolvedAt is deliberately NOT on the wire: nothing the browser draws needs it, and the
        // DTO the frontend reads stays the minimum it can be. The port carries it for a reader
        // that one day does.
        .body("workspaces.find { it.workspaceRowId == 58 }.resolvedAt", equalTo(null));
  }

  /**
   * A reference naming somebody else's row is dropped rather than drawn on this one. The far side
   * answers only what it was asked about, so this is belt rather than braces — but the alternative
   * is a link on a ticket that opens work on a different one.
   */
  @Test
  public void aReferenceForAnotherRowIsNotDrawnOnThisOne() {
    String projectId = createProject("Referenced elsewhere");
    String ticketId = createTicket(projectId, "Mine");
    dispatch.willReference(onTicket(41L, "somebody-elses-ticket"));

    asAdmin()
        .when()
        .get("/projects/api/work/" + ticketId + "/workspaces")
        .then()
        .statusCode(200)
        .body("workspaces", hasSize(0));
  }
}
