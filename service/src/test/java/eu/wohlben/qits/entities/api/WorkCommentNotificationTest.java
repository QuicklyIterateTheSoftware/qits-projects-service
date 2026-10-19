package eu.wohlben.qits.entities.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.entities.control.EntityCommentService;
import eu.wohlben.qits.entities.control.EntityWrite;
import eu.wohlben.qits.entities.control.WorkEntityService;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.projects.api.CommentTurns;
import eu.wohlben.qits.projects.api.QualifiedEntityIds;
import eu.wohlben.qits.projects.control.WorkspaceAgentTurns;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.testsupport.RecordingWorkspaceAgentLookup;
import eu.wohlben.qits.projects.testsupport.RecordingWorkspaceAgentTurns;
import eu.wohlben.qits.servicemock.idp.MockIdp;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>A comment tells the item's agent</b> (qits-1448): {@code POST /work/{qualifiedId}/comments}
 * hands one turn, {@code (<who> just commented on <qualifiedId>)}, to the agent of the comment's
 * root work item, off the request thread ({@link CommentTurns}), and never fails the comment.
 *
 * <p>An agent's comment on its own root item, and one whose agent could not be resolved, tell
 * nobody: the rule fails closed, so an agent is never turned by its own comment. The in-service
 * thread writers tell nobody either.
 *
 * <p>The tenant is on ({@link PersonGateIdpTenant}, one application with {@link
 * WorkCommentAuthorshipTest}), because the agent claim and the {@code name} claim live on a bearer.
 */
@QuarkusTest
@WithTestResource(PersonGateIdpTenant.class)
class WorkCommentNotificationTest {

  @Inject WorkEntityService entities;
  @Inject EntityCommentService comments;
  @Inject RecordingWorkspaceAgentLookup lookup;
  @Inject RecordingWorkspaceAgentTurns turns;
  @Inject CommentTurns commentTurns;

  private String projectId;
  private WorkEntity ticket;
  private WorkEntity otherTicket;
  private WorkEntity epic;
  private WorkEntity task;

  private final String agentA = UUID.randomUUID().toString();

  @BeforeEach
  void seed() {
    awaitQuiet();
    lookup.reset();
    turns.reset();
    projectId = "notify-" + UUID.randomUUID().toString().substring(0, 8);
    String repositoryId = projectId + "-repo";
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Project project = new Project();
              project.id = projectId;
              project.name = projectId;
              project.slug = projectId;
              project.persist();
              Repository repository = new Repository();
              repository.id = repositoryId;
              repository.project = project;
              repository.mainBranch = "main";
              repository.persist();
            });
    ticket = create(Archetype.TICKET, projectId, EntityWrite.ticket("The thread", "it occurs", null, "BUG", null));
    otherTicket =
        create(Archetype.TICKET, projectId, EntityWrite.ticket("Agent A's own", "it occurs", null, "BUG", null));
    epic = create(Archetype.EPIC, projectId, EntityWrite.epic("The plan", "the pitch"));
    WorkEntity feature = create(Archetype.FEATURE, epic.id, EntityWrite.feature("A part", null, null));
    task =
        create(Archetype.TASK, feature.id, EntityWrite.task(repositoryId, "A step", null, null));
  }

  @AfterEach
  void quiet() {
    turns.willBlockOn(null);
    awaitQuiet();
  }

  private WorkEntity create(Archetype archetype, String under, EntityWrite write) {
    return entities.create(archetype, under, write, "seed").entity();
  }

  /** Every handed-off delivery has finished. */
  private void awaitQuiet() {
    long deadline = System.currentTimeMillis() + 10_000;
    while (commentTurns.pending() > 0 && System.currentTimeMillis() < deadline) {
      Thread.onSpinWait();
    }
    assertEquals(0, commentTurns.pending(), "the comment turns finished");
  }

  /** What the comments said to agents, once every delivery has finished. */
  private List<RecordingWorkspaceAgentTurns.Spoken> told() {
    awaitQuiet();
    return turns.calls().stream().filter(c -> c.text().contains(" just commented on ")).toList();
  }

  // --- callers ---------------------------------------------------------------------------------

  private static RequestSpecification person(String name) {
    return given().header("X-Qits-User", name).header("X-Qits-Roles", "qits:admin");
  }

  private static RequestSpecification bearer(String subject, Map<String, Object> claims, String... groups) {
    MockIdp.TokenBuilder token =
        MockIdp.attach().token().subject(subject).audience("qits-platform").groups(groups);
    for (Map.Entry<String, Object> claim : claims.entrySet()) {
      token = token.claim(claim.getKey(), claim.getValue());
    }
    return given().header("Authorization", "Bearer " + token.mint());
  }

  private RequestSpecification agent(String agentId) {
    return bearer(
        "tok-workspace-agent-" + agentId.substring(0, 8),
        Map.of(
            "credential_type", "cli",
            "context_kind", "agent-container",
            "project", projectId,
            "agent", agentId),
        "qits:agent");
  }

  private String qualified(WorkEntity row) {
    return QualifiedEntityIds.render(projectId, row.number);
  }

  private static ValidatableResponse comment(RequestSpecification caller, WorkEntity on, String body) {
    return caller
        .contentType(ContentType.JSON)
        .body(Map.of("body", body))
        .when()
        .post("/projects/api/work/" + on.id + "/comments")
        .then();
  }

  // --- who is told -----------------------------------------------------------------------------

  @Test
  void aPersonsCommentOnATicketTellsTheTicketsAgentOnce() {
    comment(person("alice"), ticket, "Please look at the totals.").statusCode(200);

    assertEquals(
        List.of(
            new RecordingWorkspaceAgentTurns.Spoken(
                ticket.id, "(alice just commented on " + qualified(ticket) + ")")),
        told());
  }

  @Test
  void aCommentOnATaskTellsTheEpicsAgentNamingTheTask() {
    comment(person("alice"), task, "On the step.").statusCode(200);

    assertEquals(
        List.of(
            new RecordingWorkspaceAgentTurns.Spoken(
                epic.id, "(alice just commented on " + qualified(task) + ")")),
        told());
  }

  @Test
  void anAgentOnAnotherItemIsNamedByItsWorkItem() {
    lookup.stage(agentA, otherTicket.id);

    comment(agent(agentA), ticket, "Could you fix the totals?").statusCode(200);

    assertEquals(
        List.of(
            new RecordingWorkspaceAgentTurns.Spoken(
                ticket.id,
                "(" + qualified(otherTicket) + "'s agent just commented on " + qualified(ticket) + ")")),
        told());
  }

  @Test
  void anAgentOnItsOwnItemTellsNobody() {
    lookup.stage(agentA, ticket.id);
    comment(agent(agentA), ticket, "Progress note.").statusCode(200);

    // Its own epic's task is its own item too: agents are keyed by root.
    lookup.stage(agentA, epic.id);
    comment(agent(agentA), task, "Task note.").statusCode(200);

    assertEquals(List.of(), told());
  }

  @Test
  void anAgentThatCannotBeResolvedTellsNobody() {
    // Nothing staged: the lookup cannot say, so the rule fails closed.
    comment(agent(agentA), ticket, "Who am I?").statusCode(200);

    assertEquals(List.of(), told());
  }

  @Test
  void aPersonsBearerIsNamedByItsNameClaimThenItsUsername() {
    bearer(
            "user-7",
            Map.of("credential_type", "cli", "name", "Ada Lovelace", "preferred_username", "ada"),
            "qits:admin")
        .contentType(ContentType.JSON)
        .body(Map.of("body", "named"))
        .post("/projects/api/work/" + ticket.id + "/comments")
        .then()
        .statusCode(200);
    bearer("user-8", Map.of("credential_type", "cli", "preferred_username", "grace"), "qits:admin")
        .contentType(ContentType.JSON)
        .body(Map.of("body", "username"))
        .post("/projects/api/work/" + ticket.id + "/comments")
        .then()
        .statusCode(200);
    bearer("dev-qits-maintenance", Map.of(), "qits:system", "clients/dev-qits-maintenance")
        .contentType(ContentType.JSON)
        .body(Map.of("body", "a machine"))
        .post("/projects/api/work/" + ticket.id + "/comments")
        .then()
        .statusCode(200);

    String on = " just commented on " + qualified(ticket) + ")";
    assertEquals(
        List.of("(Ada Lovelace" + on, "(grace" + on, "(dev-qits-maintenance" + on),
        told().stream().map(RecordingWorkspaceAgentTurns.Spoken::text).toList());
  }

  // --- the comment never pays for the turn -----------------------------------------------------

  @Test
  void aPortThatThrowsOrCouldNotStillLeavesTheCommentWritten() {
    turns.willThrow(new IllegalStateException("a bug in the port"));
    comment(person("alice"), ticket, "first").statusCode(200).body("comment.body", equalTo("first"));
    awaitQuiet();

    turns.willAnswer(WorkspaceAgentTurns.Outcome.COULD_NOT, "qits-workspaces answered 503");
    comment(person("alice"), ticket, "second").statusCode(200).body("comment.body", equalTo("second"));

    assertEquals(2, told().size(), "both were handed over");
    assertEquals(
        List.of("first", "second"),
        comments.listComments(ticket.id).stream().map(c -> c.body).toList());
  }

  @Test
  void thePostDoesNotWaitForASlowDelivery() {
    CountDownLatch release = new CountDownLatch(1);
    turns.willBlockOn(release);
    try {
      long started = System.nanoTime();
      comment(person("alice"), ticket, "no waiting").statusCode(200);
      long tookMs = (System.nanoTime() - started) / 1_000_000;

      assertEquals(1, commentTurns.pending(), "the delivery is still running");
      assertTrue(tookMs < 10_000, "the POST answered while the delivery was held: " + tookMs);
    } finally {
      release.countDown();
    }
    assertEquals(1, told().size());
  }

  // --- the in-service writers tell nobody ------------------------------------------------------

  @Test
  void theThreadsInServiceWritersTellNobody() {
    // A block reason lands on the thread through EntityBlocks.
    person("alice")
        .contentType(ContentType.JSON)
        .body(Map.of("blocked", true, "reason", "waiting on a decision"))
        .post("/projects/api/work/" + ticket.id + "/blocked")
        .then()
        .statusCode(200);
    // PhaseAdvance, EntityDispatch and the gate tickets write through the service directly.
    comments.addComment(ticket.id, "Started the implement phase.", "dev");

    assertEquals(2, comments.listComments(ticket.id).size(), "both were written");
    assertEquals(List.of(), told());
  }
}
