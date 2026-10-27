package eu.wohlben.qits.projects.desktriggers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.control.ReleaseRequestNumbers;
import eu.wohlben.qits.projects.entity.DeskTriggerEvent;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.entity.ReleaseRequestApprovalNotice;
import eu.wohlben.qits.projects.entity.ReleasedTagPendingMerge;
import eu.wohlben.qits.projects.entity.Repository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * <b>request-stalled</b> (qits-1689): the sweep finds a release request stuck past a threshold and
 * tells the desk once per stall; a new fold is a new stall.
 */
@QuarkusTest
class RequestStalledSourceTest {

  private static final String SHA_A = "aaaaaaaa" + "0".repeat(32);

  private static final String SHA_B = "bbbbbbbb" + "0".repeat(32);

  @Inject DeskTriggerSweep sweep;

  @Inject ReleaseRequestNumbers numbers;

  private final List<String> projects = new ArrayList<>();

  private final List<String> requests = new ArrayList<>();

  @AfterEach
  void drop() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              for (String id : requests) {
                ReleasedTagPendingMerge.delete("releaseRequestId", id);
                ReleaseRequestApprovalNotice.delete("requestId", id);
              }
            });
    requests.clear();
    DeskTriggerFixtures.drop(projects);
    projects.clear();
  }

  @Test
  void aPendingStallFiresOnceAndAgainOnlyForANewFold() {
    String projectId = project(true);
    String other = request(projectId, ReleaseRequest.State.PENDING, SHA_A, hoursAgo(3));
    String id = request(projectId, ReleaseRequest.State.PENDING, SHA_A, hoursAgo(3));
    detail(id, "Waiting for automations: dependency-bump");

    sweep.sweep(Instant.now());
    sweep.sweep(Instant.now());

    assertEquals(1, DeskTriggerFixtures.windows(projectId).size(), "two stalls, one window");
    List<DeskTriggerEvent> lines = DeskTriggerFixtures.eventsOf(projectId);
    assertEquals(2, lines.size(), "a second sweep adds nothing");
    DeskTriggerEvent line =
        lines.stream().filter(l -> l.itemId.equals(id)).findFirst().orElseThrow();
    assertEquals("request-stalled", line.source);
    assertEquals(qualifiedIdOf(id), line.itemRef, "the item ref links the request");
    assertTrue(
        line.label.matches("PENDING on fold aaaaaaaa for 3h \\d+m; Waiting for automations: dependency-bump"),
        line.label);
    assertEquals("fold aaaaaaaa", line.cause);
    assertEquals(RequestStalledSource.stallKey(id, "PENDING", SHA_A), line.eventId);

    refold(id, SHA_B, hoursAgo(3));
    sweep.sweep(Instant.now());

    List<DeskTriggerEvent> after =
        DeskTriggerFixtures.eventsOf(projectId).stream().filter(l -> l.itemId.equals(id)).toList();
    assertEquals(2, after.size(), "a new fold is a new stall");
    assertNotEquals(after.get(0).eventId, after.get(1).eventId);
    assertTrue(other != null);
  }

  @Test
  void aReleasedRequestWithItsDeploymentPendingFires() {
    String projectId = project(true);
    String id = request(projectId, ReleaseRequest.State.RELEASED, SHA_A, hoursAgo(5));
    tag(id, hoursAgo(2));

    sweep.sweep(Instant.now());

    List<DeskTriggerEvent> lines = DeskTriggerFixtures.eventsOf(projectId);
    assertEquals(1, lines.size());
    assertTrue(
        lines.get(0).label.matches("RELEASED for 2h \\d+m, not FINALIZED; DEPLOYMENT gate PENDING"),
        lines.get(0).label);
  }

  @Test
  void aFreshRequestOneWaitingOnApprovalAndAProjectWithDeliveryOffFireNothing() {
    String projectId = project(true);
    request(projectId, ReleaseRequest.State.PENDING, SHA_A, Instant.now().minusSeconds(600));
    String approval = request(projectId, ReleaseRequest.State.PENDING, SHA_A, hoursAgo(3));
    openRequirement(approval, SHA_A);
    String released = request(projectId, ReleaseRequest.State.RELEASED, SHA_A, hoursAgo(3));
    tag(released, Instant.now().minusSeconds(600));
    String off = project(false);
    request(off, ReleaseRequest.State.PENDING, SHA_A, hoursAgo(3));

    sweep.sweep(Instant.now());

    assertTrue(DeskTriggerFixtures.windows(projectId).isEmpty());
    assertTrue(DeskTriggerFixtures.windows(off).isEmpty());
  }

  // -------------------------------------------------------------------------------------------

  private static Instant hoursAgo(int hours) {
    return Instant.now().minus(Duration.ofHours(hours)).minusSeconds(60);
  }

  private String project(boolean delivery) {
    String id = DeskTriggerFixtures.project(delivery);
    projects.add(id);
    return id;
  }

  private String request(String projectId, ReleaseRequest.State state, String sha, Instant armed) {
    String id = UUID.randomUUID().toString();
    requests.add(id);
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Repository repository = new Repository();
              repository.id = "desk-trigger-repo-" + UUID.randomUUID();
              repository.project = Project.findById(projectId);
              repository.mainBranch = "main";
              repository.persist();
              ReleaseRequest row = new ReleaseRequest();
              row.id = id;
              row.repoId = repository.id;
              row.projectId = projectId;
              row.repoName = "qits-qits";
              row.state = state;
              row.retryable = false;
              row.armedAt = armed;
              row.summary = "a request";
              row.mergedSha = sha;
              row.createdAt = armed;
              row.updatedAt = armed;
              row.name(numbers.next(row.repoId));
              row.persist();
            });
    return id;
  }

  private static String qualifiedIdOf(String id) {
    return QuarkusTransaction.requiringNew()
        .call(() -> ReleaseRequest.<ReleaseRequest>findById(id).qualifiedId);
  }

  private static void detail(String id, String detail) {
    QuarkusTransaction.requiringNew()
        .run(() -> ReleaseRequest.<ReleaseRequest>findById(id).detail = detail);
  }

  private static void refold(String id, String sha, Instant armed) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              ReleaseRequest row = ReleaseRequest.findById(id);
              row.mergedSha = sha;
              row.armedAt = armed;
            });
  }

  private static void tag(String requestId, Instant releasedAt) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              ReleaseRequest row = ReleaseRequest.findById(requestId);
              ReleasedTagPendingMerge tag = new ReleasedTagPendingMerge();
              tag.id = UUID.randomUUID().toString();
              tag.repoId = row.repoId;
              tag.tagName = "2026.1027." + UUID.randomUUID().toString().substring(0, 6);
              tag.releasedSha = row.mergedSha;
              tag.releaseRequestId = requestId;
              tag.releasedAt = releasedAt;
              tag.publishState = ReleasedTagPendingMerge.PublishState.PASSED;
              tag.persist();
            });
  }

  private static void openRequirement(String requestId, String sha) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              ReleaseRequestApprovalNotice notice = new ReleaseRequestApprovalNotice();
              notice.requestId = requestId;
              notice.mergedSha = sha;
              notice.requirementId = "manual-review";
              notice.state = ReleaseRequestApprovalNotice.State.OPEN.name();
              notice.admits = "person desk";
              notice.announcedAt = Instant.now();
              notice.persist();
            });
  }
}
