package eu.wohlben.qits.projects.desktriggers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.control.ProjectConfig;
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
 * tells the desk once per stall; a new fold is a new stall, and since qits-1694 so is a new arm at
 * the same fold. READY is a stall too, and a stall is measured from when the desk was turned on.
 */
@QuarkusTest
class RequestStalledSourceTest {

  private static final String SHA_A = "aaaaaaaa" + "0".repeat(32);

  private static final String SHA_B = "bbbbbbbb" + "0".repeat(32);

  @Inject DeskTriggerSweep sweep;

  @Inject ReleaseRequestNumbers numbers;

  @Inject DeskTriggerWindows windows;

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
    assertEquals(RequestStalledSource.stallKey(id, "PENDING", SHA_A, rowOf(id).armedAt), line.eventId);

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

  @Test
  void aReopenAtTheSameShaCanStallAgain() {
    String projectId = project(true);
    String id = request(projectId, ReleaseRequest.State.PENDING, SHA_A, hoursAgo(5));
    sweep.sweep(Instant.now());
    assertEquals(1, DeskTriggerFixtures.eventsOf(projectId).size());

    // REJECTED, then reconsider() reopened it at the very same fold and stamped armed_at; it is
    // stuck again past the threshold.
    rearmAtTheSameFold(id, hoursAgo(3));
    sweep.sweep(Instant.now());
    sweep.sweep(Instant.now());

    List<DeskTriggerEvent> lines = DeskTriggerFixtures.eventsOf(projectId);
    assertEquals(2, lines.size(), "a second stall at one fold is its own line, once");
    assertNotEquals(lines.get(0).eventId, lines.get(1).eventId);
    assertTrue(lines.get(1).label.startsWith("PENDING on fold aaaaaaaa for 3h "), lines.get(1).label);
  }

  @Test
  void aStallAlreadyToldUnderTheOldKeyIsNotToldAgain() {
    String projectId = project(true);
    String id = request(projectId, ReleaseRequest.State.PENDING, SHA_A, hoursAgo(3));
    admitUnderTheOldKey(projectId, id, "PENDING", SHA_A);

    sweep.sweep(Instant.now());

    assertEquals(1, DeskTriggerFixtures.eventsOf(projectId).size(), "the change of key adds nothing");
  }

  @Test
  void aReadyRequestPastItsThresholdIsOneLine() {
    String projectId = project(true);
    String fresh = request(projectId, ReleaseRequest.State.READY, SHA_A, Instant.now().minusSeconds(600));
    String id = request(projectId, ReleaseRequest.State.READY, SHA_A, hoursAgo(2));

    sweep.sweep(Instant.now());
    sweep.sweep(Instant.now());

    List<DeskTriggerEvent> lines = DeskTriggerFixtures.eventsOf(projectId);
    assertEquals(1, lines.size(), "the fresh one is not stalled; a second sweep adds nothing");
    DeskTriggerEvent line = lines.get(0);
    assertEquals(id, line.itemId);
    assertTrue(line.label.matches("READY for 2h \\d+m, not RELEASED"), line.label);
    assertEquals(
        RequestStalledSource.stallKey(id, "READY", SHA_A, rowOf(id).updatedAt), line.eventId);
    assertTrue(fresh != null);
  }

  @Test
  void aStallOlderThanMaxAgeAdmitsNothing() {
    String projectId = project(true);
    request(projectId, ReleaseRequest.State.PENDING, SHA_A, Instant.now().minus(Duration.ofDays(8)));
    request(projectId, ReleaseRequest.State.READY, SHA_A, Instant.now().minus(Duration.ofDays(8)));
    String released =
        request(projectId, ReleaseRequest.State.RELEASED, SHA_A, Instant.now().minus(Duration.ofDays(9)));
    tag(released, Instant.now().minus(Duration.ofDays(8)));

    sweep.sweep(Instant.now());

    assertTrue(DeskTriggerFixtures.windows(projectId).isEmpty());
  }

  @Test
  void turningDeliveryOnCountsStallsFromThenRatherThanAdmittingThemAllAtOnce() {
    String projectId = project(false);
    String pending = request(projectId, ReleaseRequest.State.PENDING, SHA_A, Instant.now().minus(Duration.ofDays(2)));
    request(projectId, ReleaseRequest.State.READY, SHA_A, hoursAgo(30));
    String released = request(projectId, ReleaseRequest.State.RELEASED, SHA_A, hoursAgo(30));
    tag(released, hoursAgo(20));
    sweep.sweep(Instant.now());
    assertTrue(DeskTriggerFixtures.windows(projectId).isEmpty(), "delivery off: dropped");

    QuarkusTransaction.requiringNew()
        .run(
            () ->
                new ProjectConfig.DeliverySettings(true, 2, false, java.util.Set.of())
                    .applyTo(Project.<Project>findById(projectId)));
    sweep.sweep(Instant.now());
    assertTrue(DeskTriggerFixtures.windows(projectId).isEmpty(), "no burst when delivery goes on");

    // Delivery has been on for 2h since: each stall has now lasted past its threshold in the
    // desk's own time, and the labels count from then.
    QuarkusTransaction.requiringNew()
        .run(() -> Project.<Project>findById(projectId).deliveryEnabledAt = hoursAgo(2));
    sweep.sweep(Instant.now());

    List<DeskTriggerEvent> lines = DeskTriggerFixtures.eventsOf(projectId);
    assertEquals(3, lines.size(), lines.toString());
    for (DeskTriggerEvent line : lines) {
      assertTrue(line.label.contains(" for 2h "), "measured from delivery_enabled_at: " + line.label);
    }
    assertEquals(
        RequestStalledSource.stallKey(pending, "PENDING", SHA_A, rowOf(pending).armedAt),
        lines.stream().filter(l -> l.itemId.equals(pending)).findFirst().orElseThrow().eventId,
        "the clamp never enters the key");
  }

  // -------------------------------------------------------------------------------------------

  private static ReleaseRequest rowOf(String id) {
    return QuarkusTransaction.requiringNew().call(() -> ReleaseRequest.findById(id));
  }

  /** What reconsider() leaves: PENDING at the same sha, armed at {@code armed}. */
  private static void rearmAtTheSameFold(String id, Instant armed) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              ReleaseRequest row = ReleaseRequest.findById(id);
              row.state = ReleaseRequest.State.PENDING;
              row.armedAt = armed;
              row.updatedAt = armed;
            });
  }

  /** A line as qits-1689 keyed it, with no arm, admitted now. */
  private void admitUnderTheOldKey(String projectId, String id, String state, String sha) {
    windows.admit(
        projectId,
        RequestStalledSource.ID,
        new DeskTrigger(
            projectId,
            qualifiedIdOf(id),
            id,
            "fold aaaaaaaa",
            state + " on fold aaaaaaaa for 2h 1m",
            RequestStalledSource.legacyStallKey(id, state, sha),
            Instant.now(),
            null,
            null),
        Instant.now());
  }

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
