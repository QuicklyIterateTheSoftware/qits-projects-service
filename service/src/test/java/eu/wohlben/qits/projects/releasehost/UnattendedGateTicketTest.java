package eu.wohlben.qits.projects.releasehost;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.projects.bus.BuildStatusListener;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.entity.ReleasedTagPendingMerge;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.entity.RepositoryName;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The half of the loop that puts a red gate in front of a person: a release request <b>nobody is
 * waiting on</b> that a gating verdict rejects files a BUG ticket, once, and says so on that same
 * ticket when it eventually releases.
 *
 * <p>This drives the shipped adapter rather than a recording double, on purpose: what is actually
 * under test is the crossing between {@code domain}'s gate and the {@code epics} ticket store, which
 * a double would replace with the thing that cannot go wrong. The tickets are then read back over
 * the ordinary ticket API, because that is what a person's browser reads.
 *
 * <p>Every request here is created with an explicit {@code requester}, which is the whole subject:
 * {@code qits-platform-maintenance} is the platform's bump robot and nobody watches what it asks
 * for; a person's name is somebody who does.
 */
@QuarkusTest
public class UnattendedGateTicketTest {

  /** The default of {@code qits.projects.release-requests.unattended-requesters}. */
  private static final String ROBOT = "qits-platform-maintenance";

  /** The repository the ticket that started all this was about. */
  private static final String REPO_NAME = "qits-deployments-platform-service";

  @Inject BuildStatusListener listener;

  @Inject eu.wohlben.qits.projects.bus.ReleaseRequestHeadListener headListener;

  @Inject FakeActiveBuilds activeBuilds;

  @Inject RecordingReleaseExecutor executor;

  private String repoId;
  private String projectId;

  @BeforeEach
  void seed() {
    activeBuilds.reset();
    executor.reset();
    repoId = "gate-ticket-repo-" + UUID.randomUUID();
    projectId = "gate-ticket-project-" + UUID.randomUUID();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Project project = new Project();
              project.id = projectId;
              project.name = "gate-tickets";
              project.slug = "gate-tickets-" + UUID.randomUUID();
              project.persist();
              Repository repository = new Repository();
              repository.id = repoId;
              repository.project = project;
              repository.mainBranch = "main";
              repository.persist();
              // The public name lives in the alias table, and the request copies it at create —
              // which is what the ticket's title and body name the repository by.
              RepositoryName name = new RepositoryName();
              name.project = project;
              name.repository = repository;
              name.name = REPO_NAME;
              name.persist();
            });
  }

  /** The discipline {@code ReleaseRequestFlowTest} states: no open request outlives its test. */
  @AfterEach
  void dropTheFixturesRequests() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              ReleaseRequest.delete("projectId = ?1", projectId);
              ReleasedTagPendingMerge.delete("repoId = ?1", repoId);
            });
  }

  @Test
  public void aRedGateOnTheRobotsRequestFilesOneBugTicketNamingTheRunAndTheFold() {
    String id = create("maintenance/dependencies", ROBOT);
    String merged = mergedShaOf(id);

    verdict("BuildFailed", merged, ",\"outcome\":\"FAILED\"");
    awaitState(id, "REJECTED");

    String ticketId = awaitTicketOn(id);
    assertEquals(
        true,
        request(id).getBoolean("unattended"),
        "a machine asked, so nobody is waiting on this");

    var ticket = given().get("/projects/api/tickets/" + ticketId).then().statusCode(200).extract();
    assertEquals("BUG", ticket.path("ticket.type"));
    assertEquals("OPEN", ticket.path("ticket.status"));
    assertNull(ticket.path("ticket.assignee"), "nobody was watching; nobody is assigned either");
    assertEquals("qits-projects", ticket.path("ticket.createdBy"), "this service is what noticed");

    String body = ticket.path("ticket.description");
    assertTrue(body.contains(REPO_NAME), body);
    assertTrue(body.contains("maintenance/dependencies"), body);
    assertTrue(body.contains(merged), "the ticket names the fold that was gated");
    assertTrue(body.contains(id), "and the request that stopped");
    assertTrue(body.contains("FAILED"), body);
    assertEquals(0, executor.calls().size(), "a rejected request must never reach the door");
  }

  /**
   * <b>The part that would bite.</b> A stuck request re-folds and re-gates on every push, so it goes
   * red again and again; a ticket per verdict is a ticket storm on exactly the repository somebody
   * is already trying to fix.
   */
  @Test
  public void aSecondRedGateCommentsOnTheOpenTicketRatherThanFilingAnother() {
    String id = create("maintenance/dependencies", ROBOT);
    verdict("BuildFailed", mergedShaOf(id), ",\"outcome\":\"FAILED\"");
    awaitState(id, "REJECTED");
    String ticketId = awaitTicketOn(id);

    // A push re-arms the request onto a fresh fold, and that fold fails too.
    headMoved("maintenance/dependencies");
    awaitState(id, "PENDING");
    String refolded = mergedShaOf(id);
    verdict("BuildFailed", refolded, ",\"outcome\":\"FAILED\"");
    awaitState(id, "REJECTED");

    assertEquals(ticketId, awaitTicketOn(id), "the same ticket, still");
    assertEquals(1, ticketsOnProject().size(), "and it is the only one on the project");
    List<String> comments = commentBodies(ticketId);
    assertTrue(
        comments.stream().anyMatch(c -> c.contains(refolded)),
        "the further failure is a comment naming the new fold: " + comments);
  }

  /** A person's request is answered by that person; filing them a ticket is noise. */
  @Test
  public void aRedGateOnAPersonsRequestFilesNothing() {
    String id = create("work", "wohlben");
    verdict("BuildFailed", mergedShaOf(id), ",\"outcome\":\"FAILED\"");
    awaitState(id, "REJECTED");

    assertEquals(false, request(id).getBoolean("unattended"), "a person asked for this one");
    assertNull(request(id).getString("gateTicketId"));
    assertEquals(List.of(), ticketsOnProject(), "and nothing was filed");
  }

  /**
   * An <b>unattributed</b> request is not a machine's. This service could not name who called; that
   * is a different fact from "a robot called", and guessing either way would be a guess.
   */
  @Test
  public void aRequestWithNoRequesterAtAllIsNotUnattended() {
    String id = createWithoutRequester("work");
    verdict("BuildFailed", mergedShaOf(id), ",\"outcome\":\"FAILED\"");
    awaitState(id, "REJECTED");

    assertEquals(false, request(id).getBoolean("unattended"));
    assertEquals(List.of(), ticketsOnProject());
  }

  /**
   * Somebody resolved the ticket and the gate went red again. That is a fresh report, not a comment
   * under a thread that reads as finished — the failure has to land somewhere an open list shows it.
   */
  @Test
  public void aFailureAfterTheTicketWasResolvedFilesAFreshOne() {
    String id = create("maintenance/dependencies", ROBOT);
    verdict("BuildFailed", mergedShaOf(id), ",\"outcome\":\"FAILED\"");
    awaitState(id, "REJECTED");
    String first = awaitTicketOn(id);

    given()
        .contentType(ContentType.JSON)
        .body("{\"target\":\"RESOLVED\"}")
        .post("/projects/api/tickets/" + first + "/transition")
        .then()
        .statusCode(200);

    headMoved("maintenance/dependencies");
    awaitState(id, "PENDING");
    verdict("BuildFailed", mergedShaOf(id), ",\"outcome\":\"FAILED\"");
    awaitState(id, "REJECTED");

    String second = awaitDifferentTicketOn(id, first);
    assertNotEquals(first, second);
    assertEquals(2, ticketsOnProject().size());
  }

  /**
   * It healed. The thread is told, and the ticket is <b>left open</b> — a green build says the fold
   * passes now, not that everything said on the thread is handled.
   */
  @Test
  public void aReleaseSaysSoOnTheTicketAndLeavesItOpen() {
    activeBuilds.answer(Optional.of(0));
    String id = create("maintenance/dependencies", ROBOT);
    verdict("BuildFailed", mergedShaOf(id), ",\"outcome\":\"FAILED\"");
    awaitState(id, "REJECTED");
    String ticketId = awaitTicketOn(id);

    headMoved("maintenance/dependencies");
    awaitState(id, "PENDING");
    verdict("BuildSuccessful", mergedShaOf(id), "");
    awaitState(id, "RELEASED");

    awaitComment(ticketId, "released as");
    assertEquals(
        "OPEN",
        given()
            .get("/projects/api/tickets/" + ticketId)
            .then()
            .extract()
            .path("ticket.status"),
        "a machine that files is cheaper to be wrong about than a machine that closes");
    assertFalse(
        commentBodies(ticketId).isEmpty(), "and the healing is on the thread, not just in a log");
  }

  // ---- the harness -----------------------------------------------------------------------------

  private String base() {
    return "/projects/api/repositories/" + repoId + "/release-requests";
  }

  private String create(String branch, String requester) {
    return given()
        .contentType(ContentType.JSON)
        .body(
            "{\"branch\":\""
                + branch
                + "\",\"summary\":\"bump(dependencies)\",\"requester\":\""
                + requester
                + "\"}")
        .post(base())
        .then()
        .statusCode(200)
        .extract()
        .path("request.id");
  }

  /**
   * A create with no {@code requester} in the body. The suite runs as an authenticated dev user, so
   * the controller would fall back to that identity — which is precisely a person — and the case
   * this test wants is the row with a null requester. So the column is blanked directly.
   */
  private String createWithoutRequester(String branch) {
    String id = create(branch, "somebody");
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                ReleaseRequest.<ReleaseRequest>findByIdOptional(id)
                    .ifPresent(row -> row.requester = null));
    return id;
  }

  private io.restassured.path.json.JsonPath request(String id) {
    return given()
        .get(base() + "/" + id)
        .then()
        .statusCode(200)
        .extract()
        .jsonPath()
        .setRootPath("request");
  }

  private String mergedShaOf(String id) {
    return request(id).getString("mergedSha");
  }

  private void verdict(String name, String sha, String extra) {
    listener.onFrame(
        new EventFrame(
            UUID.randomUUID().toString(),
            name,
            Instant.now(),
            "{\"branch\":\"maintenance/dependencies\",\"commitSha\":\""
                + sha
                + "\",\"repoId\":\""
                + repoId
                + "\",\"runId\":\"run-"
                + UUID.randomUUID()
                + "\""
                + extra
                + "}",
            null,
            null,
            null));
  }

  /** A push to a participating branch: the re-fold, which is the re-arm. */
  private void headMoved(String branch) {
    headListener.onFrame(
        new EventFrame(
            UUID.randomUUID().toString(),
            "SCMPublishCommit",
            Instant.now(),
            "{\"branch\":\""
                + branch
                + "\",\"repoId\":\""
                + repoId
                + "\",\"sha\":\""
                + UUID.randomUUID().toString().replace("-", "")
                + "\"}",
            null,
            null,
            null));
  }

  private List<String> ticketsOnProject() {
    return given()
        .get("/projects/api/projects/" + projectId + "/tickets")
        .then()
        .statusCode(200)
        .extract()
        .jsonPath()
        .getList("entries.ticket.id", String.class);
  }

  private List<String> commentBodies(String ticketId) {
    return given()
        .get("/projects/api/tickets/" + ticketId + "/comments")
        .then()
        .statusCode(200)
        .extract()
        .jsonPath()
        .getList("entries.comment.body", String.class);
  }

  /** The filing happens off the gate's transaction, so the link is polled rather than assumed. */
  private String awaitTicketOn(String id) {
    return await(
        () -> request(id).getString("gateTicketId"),
        "release request " + id + " never got a gate ticket");
  }

  private String awaitDifferentTicketOn(String id, String previous) {
    return await(
        () -> {
          String now = request(id).getString("gateTicketId");
          return now == null || now.equals(previous) ? null : now;
        },
        "release request " + id + " never got a ticket other than " + previous);
  }

  private void awaitComment(String ticketId, String contains) {
    await(
        () ->
            commentBodies(ticketId).stream()
                .filter(body -> body.contains(contains))
                .findFirst()
                .orElse(null),
        "ticket " + ticketId + " never got a comment containing " + contains);
  }

  private static String await(java.util.function.Supplier<String> value, String complaint) {
    long deadline = System.currentTimeMillis() + 10_000;
    while (System.currentTimeMillis() < deadline) {
      String seen = value.get();
      if (seen != null) {
        return seen;
      }
      sleep();
    }
    fail(complaint);
    return null;
  }

  private void awaitState(String id, String expected) {
    long deadline = System.currentTimeMillis() + 10_000;
    String last = null;
    while (System.currentTimeMillis() < deadline) {
      last = request(id).getString("state");
      if (expected.equals(last)) {
        return;
      }
      sleep();
    }
    fail("request " + id + " never reached " + expected + "; last seen " + last);
  }

  private static void sleep() {
    try {
      Thread.sleep(50);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      fail("interrupted");
    }
  }
}
