package eu.wohlben.qits.projects.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.entities.api.WorkRequests;
import eu.wohlben.qits.projects.contracts.ContractBearers;
import eu.wohlben.qits.projects.contracts.ProviderStates;
import eu.wohlben.qits.projects.control.WorkspaceAgentDispatch;
import eu.wohlben.qits.projects.testsupport.RecordingWorkspaceAgentDispatch;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 *
 * <p>Since qits-1815 the board asks for a whole project at once instead — {@code GET
 * /projects/{project}/work/workspaces} ({@link ProjectWorkWorkspacesController}) — and that read
 * pins the batching again: one lookup for the project's tickets and epics, each answer on its own
 * row's entry.
 */
@QuarkusTest
@TestSecurity(user = "dev", roles = "qits:admin")
public class DispatchedWorkspacesReadTest {

  @Inject RecordingWorkspaceAgentDispatch dispatch;

  @Inject ProviderStates states;

  @BeforeEach
  void resetThePort() {
    dispatch.reset();
  }

  private RequestSpecification asAdmin() {
    return given()
        .contentType(ContentType.JSON)
        .header("Authorization", ContractBearers.caller("dana", "qits:admin"));
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

  private static WorkspaceAgentDispatch.Agent onTicket(long rowId, String ticketId) {
    return RecordingWorkspaceAgentDispatch.active(
        "agent-" + rowId, rowId, "repo-1", ticketId, "ticket/contract-1");
  }

  /** The same agent after its branches were released: still named, and no longer working. */
  private static WorkspaceAgentDispatch.Agent obsoleteOnTicket(long rowId, String ticketId) {
    return RecordingWorkspaceAgentDispatch.inState(
        "agent-" + rowId,
        rowId,
        "repo-1",
        ticketId,
        "ticket/contract-2",
        "OBSOLETE",
        Instant.parse("2026-09-20T11:00:00Z"));
  }

  @Test
  public void aTicketsReadCarriesThemAndAWriteDoesNotAsk() {
    String projectId = createProject("Referenced ticket detail");
    String ticketId = createTicket(projectId, "Read me");
    createTicket(projectId, "Nobody is on this");
    dispatch.willFind(onTicket(41L, ticketId), onTicket(42L, ticketId));

    workspacesOf(ticketId)
        // Two workspaces on one ticket is a real answer and the SPA draws two links for it.
        .body("workspaces", hasSize(2))
        .body("workspaces[0].agentId", equalTo("agent-41"))
        .body("workspaces[0].workspaceRowId", equalTo(41))
        .body("workspaces[0].repositoryId", equalTo("repo-1"))
        .body("workspaces[0].branch", equalTo("ticket/contract-1"))
        // The status travels with every row, live ones included — the browser draws the difference,
        // so "this one is still running" has to be a word on the wire and not an absence.
        .body("workspaces[0].status", equalTo("ACTIVE"));

    // One lookup, about this ticket alone, by its work id.
    assertEquals(1, dispatch.lookups().size());
    assertEquals(List.of(ticketId), dispatch.lookups().get(0).workIds());

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
    dispatch.willFind(
        RecordingWorkspaceAgentDispatch.active("agent-77", 77L, "repo-1", epicId, "epic/contract-3"));

    workspacesOf(epicId).body("workspaces", hasSize(1)).body("workspaces[0].workspaceRowId", equalTo(77));

    assertEquals(1, dispatch.lookups().size());
    assertEquals(List.of(epicId), dispatch.lookups().get(0).workIds());
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
   * <b>An obsolete agent reaches the ticket, carrying the word that says so.</b> The agent is where
   * the work happened — the transcripts, the diff, the session — so it stays on the list and says
   * what it is, and every reader decides for itself what that means. Nothing in this service filters
   * it out on the read.
   */
  @Test
  public void anObsoleteAgentStillReachesTheTicketAndSaysSo() {
    String projectId = createProject("Referenced after the fact");
    String ticketId = createTicket(projectId, "The work is done");
    dispatch.willFind(obsoleteOnTicket(58L, ticketId), onTicket(59L, ticketId));

    asAdmin()
        .when()
        .get("/projects/api/work/" + ticketId + "/workspaces")
        .then()
        .statusCode(200)
        // Both of them: one that is over and one that is still running.
        .body("workspaces", hasSize(2))
        .body(
            "workspaces.find { it.workspaceRowId == 58 }.status", equalTo("OBSOLETE"))
        .body(
            "workspaces.find { it.workspaceRowId == 58 }.branch",
            equalTo("ticket/contract-2"))
        .body("workspaces.find { it.workspaceRowId == 59 }.status", equalTo("ACTIVE"))
        // removedAt is deliberately NOT on the wire: nothing the browser draws needs it.
        .body("workspaces.find { it.workspaceRowId == 58 }.removedAt", equalTo(null));
  }

  /**
   * An agent of somebody else's row is dropped rather than drawn on this one. The far side
   * answers only what it was asked about, so this is belt rather than braces — but the alternative
   * is a link on a ticket that opens work on a different one.
   */
  @Test
  public void aReferenceForAnotherRowIsNotDrawnOnThisOne() {
    String projectId = createProject("Referenced elsewhere");
    String ticketId = createTicket(projectId, "Mine");
    dispatch.willFind(onTicket(41L, "somebody-elses-ticket"));

    asAdmin()
        .when()
        .get("/projects/api/work/" + ticketId + "/workspaces")
        .then()
        .statusCode(200)
        .body("workspaces", hasSize(0));
  }

  // --- a whole project at once (qits-1815) -------------------------------------------------------

  /** The GPath naming the one entry of {@code workId} in the answer's list. */
  private static String entry(String workId) {
    return "workspaces.find { it.workId == '" + workId + "' }";
  }

  private String slugOf(String projectId) {
    return asAdmin()
        .when()
        .get("/projects/api/projects/" + projectId)
        .then()
        .statusCode(200)
        .extract()
        .path("project.slug");
  }

  /**
   * <b>One read, one lookup, each row's agents in its own entry.</b> The ticket and the epic carry
   * theirs, in tree order and named both ways, the ticket nobody works on has no entry at all, and the other project's row — asked about
   * by nobody here — is never drawn on this one, though the far side (scripted to answer every
   * lookup with everything) names it.
   */
  @Test
  public void aProjectsTicketsAndEpicsAreAnsweredInOneLookupKeyedByTheirIds() {
    String projectId = createProject("Referenced board");
    String ticketId = createTicket(projectId, "Somebody is on this");
    String idleTicket = createTicket(projectId, "Nobody is on this");
    String epicId = createEpic(projectId, "Somebody is implementing this");
    String featureId = WorkRequests.feature(this::asAdmin, epicId, "A feature");
    String otherProject = createProject("Referenced elsewhere board");
    String otherTicket = createTicket(otherProject, "Somebody else's");
    dispatch.willFind(
        onTicket(41L, ticketId),
        obsoleteOnTicket(42L, ticketId),
        RecordingWorkspaceAgentDispatch.active("agent-77", 77L, "repo-1", epicId, "epic/contract-3"),
        onTicket(99L, otherTicket));

    asAdmin()
        .when()
        .get("/projects/api/projects/" + projectId + "/work/workspaces")
        .then()
        .statusCode(200)
        .body("workspaces", hasSize(2))
        // The listing's tree order: the ticket was filed before the epic.
        .body("workspaces[0].workId", equalTo(ticketId))
        .body("workspaces[0].qualifiedId", equalTo(WorkRequests.qualifiedId(ticketId)))
        .body("workspaces[1].workId", equalTo(epicId))
        .body("workspaces[1].qualifiedId", equalTo(WorkRequests.qualifiedId(epicId)))
        .body(entry(ticketId) + ".workspaces", hasSize(2))
        .body(entry(ticketId) + ".workspaces[0].agentId", equalTo("agent-41"))
        .body(entry(ticketId) + ".workspaces[0].workspaceRowId", equalTo(41))
        .body(entry(ticketId) + ".workspaces[0].repositoryId", equalTo("repo-1"))
        .body(entry(ticketId) + ".workspaces[0].branch", equalTo("ticket/contract-1"))
        .body(entry(ticketId) + ".workspaces[0].status", equalTo("ACTIVE"))
        .body(entry(ticketId) + ".workspaces[1].status", equalTo("OBSOLETE"))
        .body(entry(epicId) + ".workspaces", hasSize(1))
        .body(entry(epicId) + ".workspaces[0].workspaceRowId", equalTo(77))
        .body(entry(idleTicket), equalTo(null))
        .body(entry(otherTicket), equalTo(null));

    // Once for the project, about its tickets and epics alone — never its feature, never the
    // other project's rows.
    assertEquals(1, dispatch.lookups().size(), "a board read asked once per row");
    Set<String> asked = new HashSet<>(dispatch.lookups().get(0).workIds());
    assertEquals(Set.of(ticketId, idleTicket, epicId), asked);
    assertTrue(!asked.contains(featureId) && !asked.contains(otherTicket));
  }

  /** The project is its slug as well as its id, as on the listing beside it. */
  @Test
  public void theProjectIsNamedBySlugToo() {
    String projectId = createProject("Referenced by slug");
    String ticketId = createTicket(projectId, "On it");
    dispatch.willFind(onTicket(43L, ticketId));

    asAdmin()
        .when()
        .get("/projects/api/projects/" + slugOf(projectId) + "/work/workspaces")
        .then()
        .statusCode(200)
        .body(entry(ticketId) + ".workspaces[0].workspaceRowId", equalTo(43));
  }

  /** Nobody working on anything is an empty list, not an absent member. */
  @Test
  public void aProjectNobodyWorksOnAnswersAnEmptyMap() {
    String projectId = createProject("Referenced idle board");
    createTicket(projectId, "Waiting");

    asAdmin()
        .when()
        .get("/projects/api/projects/" + projectId + "/work/workspaces")
        .then()
        .statusCode(200)
        .body("workspaces", hasSize(0));
  }

  /** A project with no tickets or epics asks nobody at all. */
  @Test
  public void aProjectWithNoWorkAsksNobody() {
    String projectId = createProject("Referenced empty board");

    asAdmin()
        .when()
        .get("/projects/api/projects/" + projectId + "/work/workspaces")
        .then()
        .statusCode(200)
        .body("workspaces", hasSize(0));
    assertTrue(dispatch.lookups().isEmpty(), "a project with no work asked qits-workspaces");
  }

  @Test
  public void anUnknownProjectIs404() {
    asAdmin()
        .when()
        .get("/projects/api/projects/no-such-project/work/workspaces")
        .then()
        .statusCode(404);
    assertTrue(dispatch.lookups().isEmpty());
  }

  /** A read, so an agent may make it — the listing beside it admits {@code qits:agent} too. */
  @Test
  public void anAgentMayReadTheBoardsWorkspaces() {
    String projectId = createProject("Referenced for an agent");
    String ticketId = createTicket(projectId, "Read by an agent");
    dispatch.willFind(onTicket(44L, ticketId));

    given()
        .header("Authorization", ContractBearers.caller("agent-test", "qits:agent"))
        .when()
        .get("/projects/api/projects/" + projectId + "/work/workspaces")
        .then()
        .statusCode(200)
        .body(entry(ticketId) + ".workspaces[0].workspaceRowId", equalTo(44));
  }

  /** The longer template does not take the listing's route: {@code /work} still lists. */
  @Test
  public void theProjectsWorkListingStillAnswersBesideIt() {
    String projectId = createProject("Referenced listing");
    createTicket(projectId, "Listed");

    asAdmin()
        .when()
        .get("/projects/api/projects/" + projectId + "/work")
        .then()
        .statusCode(200)
        .body("entities", hasSize(1));
  }

  /**
   * The provider state a consumer's pact of this door names ({@link
   * ProviderStates#A_PROJECT_WHOSE_WORK_HAS_WORKSPACES}) serves what its javadoc promises: two
   * entries, the ticket's two agents and the epic's one, and the idle ticket left out. Its script is
   * gone again after {@link ProviderStates#cleanUp()}.
   */
  @Test
  public void theProviderStateForThisDoorServesTwoKeysAndLeavesTheIdleRowOut() {
    Map<String, String> params;
    try {
      params = states.params(ProviderStates.A_PROJECT_WHOSE_WORK_HAS_WORKSPACES);
      asAdmin()
          .when()
          .get("/projects/api/projects/" + params.get("projectId") + "/work/workspaces")
          .then()
          .statusCode(200)
          .body("workspaces", hasSize(2))
          .body(entry(params.get("ticketId")) + ".workspaces", hasSize(2))
          .body(entry(params.get("ticketId")) + ".workspaces[0].status", equalTo("ACTIVE"))
          .body(entry(params.get("ticketId")) + ".workspaces[1].status", equalTo("OBSOLETE"))
          .body(entry(params.get("epicId")) + ".workspaces", hasSize(1))
          .body(entry(params.get("epicId")) + ".workspaces[0].agentId", equalTo("agent-42"))
          .body(entry(params.get("idleTicketId")), equalTo(null));
    } finally {
      states.cleanUp();
    }
    asAdmin()
        .when()
        .get("/projects/api/projects/" + params.get("projectId") + "/work/workspaces")
        .then()
        .statusCode(200)
        .body("workspaces", hasSize(0));
  }
}
