package eu.wohlben.qits.projects.releasehost;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.projects.security.PersonCheck;
import eu.wohlben.qits.projects.security.FakeSessionIntrospection;
import eu.wohlben.qits.entities.api.TestCriteria;
import eu.wohlben.qits.entities.api.WorkEntityDoors;
import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.projects.bus.BuildStatusListener;
import eu.wohlben.qits.projects.control.BuildStatusLedger;
import eu.wohlben.qits.projects.control.ReleaseExecutor;
import eu.wohlben.qits.projects.control.ReleaseGates;
import eu.wohlben.qits.projects.control.ReleaseRequests;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The half of the loop that puts a red gate in front of a person: a release request <b>nobody is
 * waiting on</b> that a red verdict rejects files a MAINTENANCE ticket, once, says so on that same
 * ticket when it eventually releases, and <b>drops</b> it when the request ends — FINALIZED,
 * WITHDRAWN or OBSOLETE — unless a person has retyped it and so taken it over.
 *
 * <p>This drives the shipped adapter rather than a recording double, on purpose: what is actually
 * under test is the crossing between {@code domain}'s gate and the {@code entities} ticket store, which
 * a double would replace with the thing that cannot go wrong. The tickets are then read back over
 * the ordinary {@code /work} API, because that is what a person's browser reads.
 *
 * <p>Every request here is created with an explicit {@code requester}, which is the whole subject:
 * {@code dev-qits-maintenance} is the platform's bump robot and nobody watches what it asks
 * for; a person's name is somebody who does.
 */
@QuarkusTest
public class UnattendedGateTicketTest {

  /** The default of {@code qits.projects.release-requests.unattended-requesters}. */
  private static final String ROBOT = "dev-qits-maintenance";

  /**
   * qits-maintenance's retired client id (qits-162 cut it over to {@link #ROBOT}). No environment
   * signs in with it any more, and it is deliberately not in the shipped list: a name gone from
   * every deployment must not keep matching here either, or the narrowing never shows up as having
   * happened.
   */
  private static final String RETIRED_CLIENT_ID = "qits-platform-maintenance";

  /** The repository the ticket that started all this was about. */
  private static final String REPO_NAME = "qits-deployments-platform-service";

  @Inject BuildStatusListener listener;

  @Inject eu.wohlben.qits.projects.bus.ReleaseRequestHeadListener headListener;

  @Inject FakeActiveBuilds activeBuilds;

  @Inject RecordingReleaseExecutor executor;

  @Inject RecordingReleaseGitHost gitHost;

  @Inject RecordingBackingBranchMerger merger;

  @Inject ReleaseGates gates;

  @Inject BuildStatusLedger ledger;

  @Inject ReleaseRequests releaseRequests;

  private String repoId;
  private String projectId;

  @BeforeEach
  void seed() {
    activeBuilds.reset();
    executor.reset();
    gitHost.reset();
    merger.reset();
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
  public void aRedGateOnTheRobotsRequestFilesOneMaintenanceTicketNamingTheRunAndTheFold() {
    String id = create("maintenance/dependencies", ROBOT);
    String merged = mergedShaOf(id);

    verdict("BuildFailed", merged, ",\"outcome\":\"FAILED\"");
    awaitState(id, "REJECTED");

    String ticketId = awaitTicketOn(id);
    assertEquals(
        true,
        request(id).getBoolean("unattended"),
        "a machine asked, so nobody is waiting on this");

    var ticket = given().get("/projects/api/work/" + ticketId).then().statusCode(200).extract();
    assertEquals(
        "MAINTENANCE",
        ticket.path("ticketType"),
        "the platform filed it, and the type is what lets the platform close it again");
    assertEquals("REPORTED", ticket.path("status"));
    assertTrue(
        ((String) ticket.path("impetus")).contains(REPO_NAME),
        "the impetus says in one sentence what occurs");
    assertNull(ticket.path("assignee"), "nobody was watching; nobody is assigned either");
    assertEquals("qits-projects", ticket.path("createdBy"), "this service is what noticed");

    String body = ticket.path("description");
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

  /**
   * qits-maintenance's retired client id is not in the shipped list any more (qits-162's cutover is
   * finished, and no environment signs in with it) — a red gate on a bump filed under that old name
   * is treated like any other named requester's, not like the robot's.
   */
  @Test
  public void aRedGateOnTheRobotsRetiredClientIdIsNoLongerUnattended() {
    String id = create("maintenance/dependencies", RETIRED_CLIENT_ID);
    verdict("BuildFailed", mergedShaOf(id), ",\"outcome\":\"FAILED\"");
    awaitState(id, "REJECTED");

    assertEquals(
        false,
        request(id).getBoolean("unattended"),
        "qits-platform-maintenance is gone from every environment and is no longer matched");
    assertEquals(List.of(), ticketsOnProject(), "so nothing was filed");
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
   * Somebody walked the ticket all the way to DONE and the gate went red again. That is a fresh
   * report, not a comment under a thread that reads as finished. DONE is the only status this
   * probe treats that way: every earlier one still claims somebody is on it.
   */
  @Test
  public void aFailureAfterTheTicketReachedDoneFilesAFreshOne() {
    String id = create("maintenance/dependencies", ROBOT);
    verdict("BuildFailed", mergedShaOf(id), ",\"outcome\":\"FAILED\"");
    awaitState(id, "REJECTED");
    String first = TestCriteria.give(awaitTicketOn(id));

    for (String target : List.of("REFINED", "READY_FOR_DEV", "IMPLEMENTED", "VERIFIED", "DONE")) {
      given().cookie(PersonCheck.SESSION_COOKIE, FakeSessionIntrospection.admin("dev"))
          .contentType(ContentType.JSON)
          .body("{\"target\":\"" + target + "\"}")
          .post("/projects/api/work/" + first + "/status")
          .then()
          .statusCode(200);
    }

    headMoved("maintenance/dependencies");
    awaitState(id, "PENDING");
    verdict("BuildFailed", mergedShaOf(id), ",\"outcome\":\"FAILED\"");
    awaitState(id, "REJECTED");

    String second = awaitDifferentTicketOn(id, first);
    assertNotEquals(first, second);
    assertEquals(2, ticketsOnProject().size());
  }

  /**
   * Somebody decided the work was not going to be done, and the gate went red again. DROPPED sits
   * beside DONE here and nowhere else in this service's reading of a status: the two disagree about
   * everything — one says the fix shipped, the other says nothing was ever built — except the one
   * fact this probe asks about, which is that a person is finished with the thread. Commenting a
   * fresh red gate onto either would be arguing with that decision in a place nobody is reading, so
   * the further failure is a report of its own.
   *
   * <p>It walks the ticket through the real transition door rather than writing the word onto the
   * row, because the persisted DROPPED status is half of what is under test: the check constraint
   * on the status column refused it until the migration that widened it, which is why this case
   * could not be written beside its DONE twin at the time.
   */
  @Test
  public void aFailureAfterTheTicketWasDroppedFilesAFreshOne() {
    String id = create("maintenance/dependencies", ROBOT);
    verdict("BuildFailed", mergedShaOf(id), ",\"outcome\":\"FAILED\"");
    awaitState(id, "REJECTED");
    String first = awaitTicketOn(id);

    // DROPPED is reachable from every open status, so the ticket goes there from the REPORTED it
    // was filed at — which is also the likeliest way a real one gets there: somebody reads the
    // report and rules the work out before any of it is refined.
    given().cookie(PersonCheck.SESSION_COOKIE, FakeSessionIntrospection.admin("dev"))
        .contentType(ContentType.JSON)
        .body("{\"target\":\"DROPPED\"}")
        .post("/projects/api/work/" + first + "/status")
        .then()
        .statusCode(200);
    assertEquals(
        "DROPPED",
        given().get("/projects/api/work/" + first).then().extract().path("status"),
        "the drop has to have been stored, or the probe below is answering about an open ticket");

    headMoved("maintenance/dependencies");
    awaitState(id, "PENDING");
    verdict("BuildFailed", mergedShaOf(id), ",\"outcome\":\"FAILED\"");
    awaitState(id, "REJECTED");

    String second = awaitDifferentTicketOn(id, first);
    assertNotEquals(first, second, "a dropped thread is finished with, so the red gate is new");
    assertEquals(2, ticketsOnProject().size());
    assertTrue(
        commentBodies(first).isEmpty(),
        "and nothing was said under the dropped ticket: " + commentBodies(first));
  }

  /**
   * It healed. The thread is told, and the ticket is <b>left where it is</b> for now: a RELEASED
   * request has not ended — its tag has still to reach main — so there is still a request for the
   * ticket to be about. The comment says the close comes with the finalization.
   */
  @Test
  public void aReleaseSaysSoOnTheTicketAndLeavesItWhereItIs() {
    activeBuilds.answer(Optional.of(0));
    String id = create("maintenance/dependencies", ROBOT);
    verdict("BuildFailed", mergedShaOf(id), ",\"outcome\":\"FAILED\"");
    awaitState(id, "REJECTED");
    String ticketId = awaitTicketOn(id);

    headMoved("maintenance/dependencies");
    awaitState(id, "PENDING");
    verdict("BuildSuccessful", mergedShaOf(id), "");
    awaitState(id, "RELEASED");

    String said = awaitComment(ticketId, "released as");
    assertTrue(
        said.contains("when the request is finalized"),
        "the thread is told that the close is coming, and when: " + said);
    assertEquals(
        "REPORTED",
        statusOf(ticketId),
        "released is not ended: the tag has not reached main, so the ticket stays open");
    assertNull(closedAtOf(id), "and nothing has been closed for this request yet");
    assertFalse(
        commentBodies(ticketId).isEmpty(), "and the healing is on the thread, not just in a log");
  }

  // ---- the request ending closes the ticket (qits-578) ----------------------------------------

  /** A person withdrew the stuck request: nothing is left for the ticket to be about. */
  @Test
  public void withdrawingTheRequestDropsItsTicketAndSaysWithdrawn() {
    String id = create("maintenance/dependencies", ROBOT);
    verdict("BuildFailed", mergedShaOf(id), ",\"outcome\":\"FAILED\"");
    awaitState(id, "REJECTED");
    String ticketId = awaitTicketOn(id);

    withdraw(id, "the bump is not wanted after all");

    awaitStatus(ticketId, "DROPPED");
    String said = awaitComment(ticketId, "WITHDRAWN");
    assertTrue(said.contains(id), "the comment names the request: " + said);
    assertTrue(said.contains("the bump is not wanted after all"), "and why: " + said);
    assertTrue(awaitClosedAt(id) != null, "and the request records that its ticket is dealt with");
  }

  /**
   * The ordinary happy end: the release's tag reaches main in a push, the request is FINALIZED, and
   * the ticket goes DROPPED with the version on its thread.
   */
  @Test
  public void aPushToMainThatFinalizesTheRequestDropsItsTicketNamingTheVersion() {
    String version = uniqueVersion();
    String releasedSha = RecordingBackingBranchMerger.freshSha();
    Released released = releasedWithTicket(version, releasedSha);
    awaitComment(released.ticketId(), "released as");
    assertEquals("REPORTED", statusOf(released.ticketId()), "open while the request is");

    String mainSha = RecordingBackingBranchMerger.freshSha();
    gitHost.containsCommit(repoId, releasedSha, mainSha);
    headMovedTo("main", mainSha);

    awaitState(released.requestId(), "FINALIZED");
    awaitStatus(released.ticketId(), "DROPPED");
    String said = awaitComment(released.ticketId(), "FINALIZED");
    assertTrue(said.contains(version), "the comment names the version that reached main: " + said);
    assertTrue(awaitClosedAt(released.requestId()) != null);
  }

  /**
   * <b>The live failure of 2026-09-29, reproduced.</b> Every bus consumer runs inside the eventstream
   * funnel's own {@code requiringNew} transaction, so the push to main that finalizes a request is
   * handled with a projects-database transaction open on the thread. The close made from there used
   * to run inside it, and the transition's preview answered "Ticket not found" — the comment landed
   * and the ticket stayed REPORTED. Delivered here exactly that way: the real listener, inside a
   * transaction of the test's.
   */
  @Test
  public void aFinalizationHeardInsideTheConsumersTransactionStillDropsTheTicket() {
    String version = uniqueVersion();
    String releasedSha = RecordingBackingBranchMerger.freshSha();
    Released released = releasedWithTicket(version, releasedSha);
    awaitComment(released.ticketId(), "released as");

    String mainSha = RecordingBackingBranchMerger.freshSha();
    gitHost.containsCommit(repoId, releasedSha, mainSha);
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              // The funnel claims the frame in the projects database BEFORE the handler runs, so
              // that datasource is already enlisted in the transaction the handler inherits. An
              // empty transaction reproduces nothing — it is the enlisted connection the ticket
              // store must not be made to join. A read enlists it just as the claim's insert does,
              // without holding a lock anything below would wait on.
              ReleaseRequest.count("projectId", projectId);
              headMovedTo("main", mainSha);
            });

    awaitState(released.requestId(), "FINALIZED");
    awaitStatus(released.ticketId(), "DROPPED");
    assertTrue(awaitClosedAt(released.requestId()) != null, "and the request is stamped");
    List<String> finalized =
        commentBodies(released.ticketId()).stream().filter(c -> c.contains("FINALIZED")).toList();
    assertEquals(1, finalized.size(), "said once: " + finalized);
    assertTrue(finalized.get(0).contains(version), finalized.get(0));
  }

  /**
   * <b>The ordering a repository with nothing to deploy produces, pinned.</b> Its tag goes to main
   * inside the release itself ({@code ReleaseFinalization.onReleased}), so FINALIZED — and with it
   * the ticket's close — is written BEFORE the "released as" comment would be. That comment is then
   * skipped, because the ticket it would go on is already closed; the FINALIZED comment names the
   * version instead, and that is the whole of what the thread needs.
   */
  @Test
  public void aReleaseWithNothingToDeployFinalizesAtOnceAndTheReleasedCommentIsSkipped() {
    String version = uniqueVersion();
    // No release.yml and no deployments.yml at the tag: nothing publishes, nothing deploys.
    gitHost.tree("refs/tags/" + version, Map.of("pom.xml", "irrelevant"));
    Released released = releasedWithTicket(version, RecordingBackingBranchMerger.freshSha());

    awaitState(released.requestId(), "FINALIZED");
    awaitStatus(released.ticketId(), "DROPPED");
    String said = awaitComment(released.ticketId(), "FINALIZED");
    assertTrue(said.contains(version), said);
    assertTrue(awaitClosedAt(released.requestId()) != null);
    // sayItHealed runs after the finalization on the same worker; give it the moment it needs.
    sleep(500);
    assertTrue(
        commentBodies(released.ticketId()).stream().noneMatch(c -> c.contains("released as")),
        "the released comment found a closed thread and said nothing: "
            + commentBodies(released.ticketId()));
  }

  /**
   * A fresh ask overtakes a RELEASED request that has not finalized; the earlier one goes OBSOLETE
   * and its ticket is dropped naming the request that superseded it.
   */
  @Test
  public void anObsoletingRequestDropsTheTicketNamingItsSuccessor() {
    Released released =
        releasedWithTicket(uniqueVersion(), RecordingBackingBranchMerger.freshSha());
    awaitComment(released.ticketId(), "released as");

    String successor = create("maintenance/dependencies", ROBOT);
    assertNotEquals(released.requestId(), successor, "a released request is never converged onto");

    awaitState(released.requestId(), "OBSOLETE");
    awaitStatus(released.ticketId(), "DROPPED");
    String said = awaitComment(released.ticketId(), "OBSOLETE");
    assertTrue(said.contains(successor), "the comment names the successor: " + said);
    assertTrue(awaitClosedAt(released.requestId()) != null);
  }

  /** A person retyped the ticket: it is theirs now, so the ending is said and nothing is moved. */
  @Test
  public void aTicketRetypedToBugIsToldAndLeftOpen() {
    String id = create("maintenance/dependencies", ROBOT);
    verdict("BuildFailed", mergedShaOf(id), ",\"outcome\":\"FAILED\"");
    awaitState(id, "REJECTED");
    String ticketId = awaitTicketOn(id);
    given()
        .contentType(WorkEntityDoors.MERGE_PATCH_JSON)
        .body(Map.of("ticketType", "BUG"))
        .patch("/projects/api/work/" + ticketId)
        .then()
        .statusCode(200);

    withdraw(id, null);

    String said = awaitComment(ticketId, "no longer MAINTENANCE");
    assertTrue(said.contains("WITHDRAWN"), said);
    assertEquals("REPORTED", statusOf(ticketId), "a person's ticket is not the platform's to close");
    assertTrue(awaitClosedAt(id) != null, "and there is nothing more to retry");
  }

  /**
   * A thread a person already closed is not reopened, commented on or moved — whichever of the two
   * closing words closed it — and the request is stamped all the same, so the sweep never asks.
   */
  @Test
  public void anAlreadyClosedTicketIsLeftAloneAndTheStampIsWritten() {
    List<List<String>> walks =
        List.of(List.of("REFINED", "READY_FOR_DEV", "IMPLEMENTED", "VERIFIED", "DONE"), List.of("DROPPED"));
    for (List<String> walk : walks) {
      String id = create("maintenance/dependencies", ROBOT);
      verdict("BuildFailed", mergedShaOf(id), ",\"outcome\":\"FAILED\"");
      awaitState(id, "REJECTED");
      String ticketId = TestCriteria.give(awaitTicketOn(id));
      for (String target : walk) {
        given().cookie(PersonCheck.SESSION_COOKIE, FakeSessionIntrospection.admin("dev"))
            .contentType(ContentType.JSON)
            .body("{\"target\":\"" + target + "\"}")
            .post("/projects/api/work/" + ticketId + "/status")
            .then()
            .statusCode(200);
      }
      String closed = walk.get(walk.size() - 1);
      List<String> before = commentBodies(ticketId);

      withdraw(id, null);

      assertTrue(awaitClosedAt(id) != null, "stamped although nothing was done: " + closed);
      assertEquals(closed, statusOf(ticketId));
      assertEquals(before, commentBodies(ticketId), "and nothing was said under a closed thread");
    }
  }

  /**
   * <b>The floor.</b> An ending whose inline close never ran — a process that died between the two,
   * or every request that ended before this feature existed — is an ended row with a ticket and no
   * stamp, and the sweep closes it.
   */
  @Test
  public void theSweepClosesATicketWhoseInlineCloseWasSkipped() {
    String id = create("maintenance/dependencies", ROBOT);
    verdict("BuildFailed", mergedShaOf(id), ",\"outcome\":\"FAILED\"");
    awaitState(id, "REJECTED");
    String ticketId = awaitTicketOn(id);
    // FINALIZED behind the service's back: exactly the row a skipped inline call leaves.
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                ReleaseRequest.<ReleaseRequest>findByIdOptional(id)
                    .ifPresent(
                        row -> {
                          row.state = ReleaseRequest.State.FINALIZED;
                          row.version = "2026.929.235959";
                          row.gateTicketClosedAt = null;
                        }));
    assertEquals("REPORTED", statusOf(ticketId), "nothing has closed it yet");

    releaseRequests.sweepGateTickets();

    assertEquals("DROPPED", statusOf(ticketId));
    String said = awaitComment(ticketId, "FINALIZED");
    assertTrue(said.contains("2026.929.235959"), said);
    assertTrue(closedAtOf(id) != null, "and the sweep will not ask about it again");
  }

  /**
   * <b>The double filing.</b> One red verdict is heard twice — the bus and the sweep, or two
   * deliveries — and both evaluations used to read PENDING, both reject and both file. The first
   * evaluation is held open inside its gate transaction (the gate set's tree read is parked) while
   * the second arrives; with the row lock the second waits, then reads REJECTED and files nothing.
   */
  @Test
  public void twoConcurrentEvaluationsOfOneRedVerdictFileOneTicket() throws Exception {
    String id = create("maintenance/dependencies", ROBOT);
    String merged = mergedShaOf(id);
    ledger.record(
        new BuildStatusLedger.Verdict(
            "run-" + UUID.randomUUID(),
            repoId,
            projectId,
            REPO_NAME,
            "maintenance/dependencies",
            merged,
            "FAILED",
            Instant.now(),
            null,
            null));
    gates.forget(repoId);
    RecordingReleaseGitHost.Hold hold = gitHost.holdNextTreeRead(repoId);

    List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
    Thread first = evaluation(merged, failures);
    first.start();
    assertTrue(hold.awaitEntered(), "the first evaluation never reached its gate read");
    Thread second = evaluation(merged, failures);
    second.start();
    // Long enough for the second to reach the row (and, without the lock, to get all the way
    // through); it cannot be observed waiting on a database lock, only given the time to.
    Thread.sleep(1_000);
    hold.release();
    first.join(20_000);
    second.join(20_000);

    assertEquals(List.of(), failures, "neither evaluation may throw");
    assertEquals("REJECTED", request(id).getString("state"));
    assertEquals(1, ticketsOnProject().size(), "one red verdict, one ticket: " + ticketsOnProject());
  }

  // ---- the harness -----------------------------------------------------------------------------

  /** A request and its ticket, carried out of a staging helper. */
  private record Released(String requestId, String ticketId) {}

  /**
   * A robot's request that went red, got a ticket, was re-armed by a push and released as {@code
   * version} at {@code releasedSha} — the RELEASED request every ending below starts from.
   */
  private Released releasedWithTicket(String version, String releasedSha) {
    activeBuilds.answer(Optional.of(0));
    executor.answer(ReleaseExecutor.Outcome.released(version, releasedSha));
    String id = create("maintenance/dependencies", ROBOT);
    verdict("BuildFailed", mergedShaOf(id), ",\"outcome\":\"FAILED\"");
    awaitState(id, "REJECTED");
    String ticketId = awaitTicketOn(id);
    headMoved("maintenance/dependencies");
    awaitState(id, "PENDING");
    verdict("BuildSuccessful", mergedShaOf(id), "");
    return new Released(id, ticketId);
  }

  /** One evaluation of the request gating {@code sha}, on a thread of its own. */
  private Thread evaluation(String sha, List<Throwable> failures) {
    return new Thread(
        () -> {
          try {
            releaseRequests.onVerdict(repoId, sha, Set.of());
          } catch (Throwable t) {
            failures.add(t);
          }
        });
  }

  /** A calver no other test in the suite releases, so no staged tag tree of theirs can match it. */
  private static String uniqueVersion() {
    return "2026.929." + (100000 + (int) (Math.random() * 800000));
  }

  private void withdraw(String id, String reason) {
    given()
        .contentType(ContentType.JSON)
        .body(reason == null ? Map.of() : Map.of("reason", reason))
        .post(base() + "/" + id + "/withdraw")
        .then()
        .statusCode(200);
  }

  private String statusOf(String ticketId) {
    return given().get("/projects/api/work/" + ticketId).then().extract().path("status");
  }

  private void awaitStatus(String ticketId, String expected) {
    await(
        () -> expected.equals(statusOf(ticketId)) ? expected : null,
        "ticket " + ticketId + " never reached " + expected + "; last seen " + statusOf(ticketId));
  }

  private Instant closedAtOf(String id) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                ReleaseRequest.<ReleaseRequest>findByIdOptional(id)
                    .map(row -> row.gateTicketClosedAt)
                    .orElse(null));
  }

  private Instant awaitClosedAt(String id) {
    String seen =
        await(
            () -> {
              Instant at = closedAtOf(id);
              return at == null ? null : at.toString();
            },
            "release request " + id + " never recorded its ticket as closed");
    return seen == null ? null : Instant.parse(seen);
  }

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
    headMovedTo(branch, UUID.randomUUID().toString().replace("-", ""));
  }

  /** A push that moves {@code branch} to a sha the test chose — main, where lineage is asked. */
  private void headMovedTo(String branch, String sha) {
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
                + sha
                + "\"}",
            null,
            null,
            null));
  }

  private List<String> ticketsOnProject() {
    return given()
        .get("/projects/api/projects/" + projectId + "/work?archetype=TICKET")
        .then()
        .statusCode(200)
        .extract()
        .jsonPath()
        .getList("entities.id", String.class);
  }

  private List<String> commentBodies(String ticketId) {
    return given()
        .get("/projects/api/work/" + ticketId + "/comments")
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

  private String awaitComment(String ticketId, String contains) {
    return await(
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
    sleep(50);
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      fail("interrupted");
    }
  }
}
