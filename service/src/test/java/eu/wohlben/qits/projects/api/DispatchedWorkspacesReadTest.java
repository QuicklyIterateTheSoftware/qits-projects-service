package eu.wohlben.qits.projects.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.control.WorkspaceAgentDispatch;
import eu.wohlben.qits.projects.testsupport.RecordingWorkspaceAgentDispatch;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The read back: a ticket and an epic carrying the live workspaces that are working on them, so
 * "Assign agent" and "Start implementation" stop offering a second one after a reload.
 *
 * <p>What is pinned here is this service's half — that the reads ask, that they ask <b>once per
 * listing</b>, and that what comes back lands on the row it names. Which workspaces exist is
 * qits-workspaces' answer and is scripted rather than simulated; {@code HttpWorkspaceAgentDispatchTest}
 * is where the wire is under test.
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
    return asAdmin()
        .body(Map.of("title", title, "type", "BUG"))
        .when()
        .post("/projects/api/projects/" + projectId + "/tickets")
        .then()
        .statusCode(200)
        .extract()
        .path("ticket.id");
  }

  private String createEpic(String projectId, String title) {
    return asAdmin()
        .body(Map.of("title", title))
        .when()
        .post("/projects/api/projects/" + projectId + "/epics")
        .then()
        .statusCode(200)
        .extract()
        .path("epic.id");
  }

  private static WorkspaceAgentDispatch.Reference onTicket(long rowId, String ticketId) {
    return new WorkspaceAgentDispatch.Reference(
        rowId, "repo-1", "ticket-work", "ticket/work", ticketId, null);
  }

  @Test
  public void aTicketListingAsksOnceAndPutsEachWorkspaceOnTheRowItNames() {
    String projectId = createProject("Referenced tickets");
    String worked = createTicket(projectId, "Somebody is on this");
    createTicket(projectId, "Nobody is on this");
    dispatch.willReference(onTicket(41L, worked), onTicket(42L, worked));

    asAdmin()
        .when()
        .get("/projects/api/projects/" + projectId + "/tickets")
        .then()
        .statusCode(200)
        // Two workspaces on one ticket is a real answer and the SPA draws two links for it.
        .body("entries.find { it.ticket.id == '" + worked + "' }.ticket.workspaces", hasSize(2))
        .body(
            "entries.find { it.ticket.id == '" + worked + "' }.ticket.workspaces[0].workspaceRowId",
            equalTo(41))
        .body(
            "entries.find { it.ticket.id == '" + worked + "' }.ticket.workspaces[0].repositoryId",
            equalTo("repo-1"))
        // Empty, never null: "none" must not have to be told apart from "unasked".
        .body("entries.find { it.ticket.title == 'Nobody is on this' }.ticket.workspaces", hasSize(0));

    // One lookup for the whole page, carrying every ticket on it — not one call per row.
    assertEquals(1, dispatch.lookups().size());
    RecordingWorkspaceAgentDispatch.Looked asked = dispatch.lookups().get(0);
    assertEquals(2, asked.ticketIds().size());
    assertTrue(asked.ticketIds().contains(worked));
    assertTrue(asked.epicIds().isEmpty(), "a tickets listing asked about epics");
  }

  @Test
  public void aTicketsDetailReadCarriesThemAndAWriteDoesNot() {
    String projectId = createProject("Referenced ticket detail");
    String ticketId = createTicket(projectId, "Read me");
    dispatch.willReference(onTicket(41L, ticketId));

    asAdmin()
        .when()
        .get("/projects/api/tickets/" + ticketId)
        .then()
        .statusCode(200)
        .body("ticket.workspaces", hasSize(1))
        .body("ticket.workspaces[0].branch", equalTo("ticket/work"));

    // An edit answers the row it changed and asks nobody who is working on it.
    int asked = dispatch.lookups().size();
    asAdmin()
        .body(Map.of("title", "Read me twice"))
        .when()
        .put("/projects/api/tickets/" + ticketId)
        .then()
        .statusCode(200)
        .body("ticket.workspaces", hasSize(0));
    assertEquals(asked, dispatch.lookups().size(), "a write asked a sibling service who is working");
  }

  @Test
  public void anEpicBoardCarriesTheWorkspacesToo() {
    String projectId = createProject("Referenced epics");
    String epicId = createEpic(projectId, "Somebody is implementing this");
    dispatch.willReference(
        new WorkspaceAgentDispatch.Reference(
            77L, "repo-1", "epic-work", "epic/work", null, epicId));

    asAdmin()
        .when()
        .get("/projects/api/projects/" + projectId + "/epics")
        .then()
        .statusCode(200)
        .body("entries.find { it.epic.id == '" + epicId + "' }.epic.workspaces", hasSize(1))
        .body(
            "entries.find { it.epic.id == '" + epicId + "' }.epic.workspaces[0].workspaceRowId",
            equalTo(77));

    assertEquals(1, dispatch.lookups().size());
    assertEquals(List.of(epicId), dispatch.lookups().get(0).epicIds());
    assertTrue(dispatch.lookups().get(0).ticketIds().isEmpty(), "an epics board asked about tickets");

    asAdmin()
        .when()
        .get("/projects/api/epics/" + epicId)
        .then()
        .statusCode(200)
        .body("epic.workspaces", hasSize(1));
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
        .get("/projects/api/tickets/" + ticketId)
        .then()
        .statusCode(200)
        .body("ticket.workspaces", hasSize(0));
  }
}
