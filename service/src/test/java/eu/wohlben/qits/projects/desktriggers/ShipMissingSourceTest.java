package eu.wohlben.qits.projects.desktriggers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.projects.control.ReleaseRequestNumbers;
import eu.wohlben.qits.projects.control.WorkspaceAgentDispatch;
import eu.wohlben.qits.projects.entity.DeskTriggerEvent;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.testsupport.RecordingWorkspaceAgentDispatch;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * <b>verified-unshipped</b> (qits-1778): a running item VERIFIED for 30 minutes that no open release
 * request carries — on its wrapper branch or a {@code <branch>-<name>} submodule branch — is told to
 * the desk once per move into VERIFIED, measured from when the desk was turned on.
 */
@QuarkusTest
class ShipMissingSourceTest {

  @Inject DeskTriggerSweep sweep;

  @Inject ReleaseRequestNumbers numbers;

  @Inject RecordingWorkspaceAgentDispatch dispatch;

  private final List<String> projects = new ArrayList<>();

  private final List<WorkspaceAgentDispatch.Agent> agents = new ArrayList<>();

  /** The sweep's clock, to the microsecond the database keeps. */
  private final Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

  @AfterEach
  void drop() {
    dispatch.reset();
    agents.clear();
    DeskTriggerFixtures.dropWork(projects);
    DeskTriggerFixtures.drop(projects);
    projects.clear();
  }

  @Test
  void anItemVerifiedLessThanThirtyMinutesRaisesNothing() {
    String projectId = project(true);
    WorkEntity item = running(projectId, 1769);
    DeskTriggerFixtures.audited(item, "VERIFIED", now.minus(Duration.ofMinutes(29)));

    sweep.sweep(now);

    assertTrue(DeskTriggerFixtures.eventsOf(projectId).isEmpty());
  }

  @Test
  void anItemVerifiedThirtyMinutesWithNoOpenRequestIsOneLineAndASecondScanAddsNothing() {
    String projectId = project(true);
    String slug = DeskTriggerFixtures.slugOf(projectId);
    WorkEntity item = running(projectId, 1769);
    Instant verified = now.minus(Duration.ofMinutes(30));
    DeskTriggerFixtures.audited(item, "IMPLEMENTED", now.minus(Duration.ofHours(2)));
    DeskTriggerFixtures.audited(item, "VERIFIED", verified);
    // A later write that keeps the status (an assignee, say) does not move the VERIFIED-since.
    DeskTriggerFixtures.audited(item, "VERIFIED", now.minus(Duration.ofMinutes(5)));

    sweep.sweep(now);
    sweep.sweep(now.plusSeconds(60));

    List<DeskTriggerEvent> lines = DeskTriggerFixtures.eventsOf(projectId);
    assertEquals(1, lines.size(), "once per move into VERIFIED");
    DeskTriggerEvent line = lines.get(0);
    assertEquals(ShipMissingSource.ID, line.source);
    assertEquals(slug + "-1769", line.itemRef);
    assertEquals(item.id, line.itemId);
    assertEquals(
        "VERIFIED 30m; no open release request carries ticket/" + slug + "-1769", line.label);
    assertEquals("verified " + AgentIdleSource.MINUTES.format(verified), line.cause);
    assertEquals(ShipMissingSource.shipKey(item.id, verified), line.eventId);
    assertNull(line.commentId);
  }

  @Test
  void anOpenRequestOnTheWrapperBranchOrASubmoduleBranchRaisesNothing() {
    String projectId = project(true);
    String slug = DeskTriggerFixtures.slugOf(projectId);
    WorkEntity onWrapper = running(projectId, 1);
    WorkEntity onSubmodule = running(projectId, 2);
    for (WorkEntity item : List.of(onWrapper, onSubmodule)) {
      DeskTriggerFixtures.audited(item, "VERIFIED", now.minus(Duration.ofHours(1)));
    }
    DeskTriggerFixtures.request(
        numbers, projectId, slug + "-" + slug, ReleaseRequest.State.PENDING, "ticket/" + slug + "-1");
    DeskTriggerFixtures.request(
        numbers,
        projectId,
        "some-service",
        ReleaseRequest.State.REJECTED,
        "ticket/" + slug + "-2-fix-totals");

    sweep.sweep(now);

    assertTrue(DeskTriggerFixtures.eventsOf(projectId).isEmpty());
  }

  @Test
  void aFinalizedOrWithdrawnRequestOnlyStillRaisesALine() {
    String projectId = project(true);
    String slug = DeskTriggerFixtures.slugOf(projectId);
    WorkEntity item = running(projectId, 3);
    DeskTriggerFixtures.audited(item, "VERIFIED", now.minus(Duration.ofMinutes(45)));
    DeskTriggerFixtures.request(
        numbers, projectId, slug + "-" + slug, ReleaseRequest.State.WITHDRAWN, "ticket/" + slug + "-3");
    DeskTriggerFixtures.request(
        numbers, projectId, "some-service", ReleaseRequest.State.FINALIZED, "ticket/" + slug + "-3-x");
    // ticket/<slug>-30 is another item's branch, not in this one's scope.
    DeskTriggerFixtures.request(
        numbers, projectId, "some-service", ReleaseRequest.State.PENDING, "ticket/" + slug + "-30");

    sweep.sweep(now);

    List<DeskTriggerEvent> lines = DeskTriggerFixtures.eventsOf(projectId);
    assertEquals(1, lines.size());
    assertEquals(
        "VERIFIED 45m; no open release request carries ticket/" + slug + "-3", lines.get(0).label);
  }

  @Test
  void aProjectWithDeliveryOffRaisesNothing() {
    String projectId = project(false);
    WorkEntity item = running(projectId, 4);
    DeskTriggerFixtures.audited(item, "VERIFIED", now.minus(Duration.ofHours(1)));

    sweep.sweep(now);

    assertTrue(DeskTriggerFixtures.windows(projectId).isEmpty());
  }

  @Test
  void itIsMeasuredFromWhenDeliveryCameOnAndAWeekOldMoveIsNotRaised() {
    String projectId = project(true);
    String slug = DeskTriggerFixtures.slugOf(projectId);
    DeskTriggerFixtures.delivery(projectId, 5, now.minus(Duration.ofMinutes(10)));
    WorkEntity item = running(projectId, 5);
    DeskTriggerFixtures.audited(item, "VERIFIED", now.minus(Duration.ofHours(2)));
    String old = project(true);
    WorkEntity stale = running(old, 6);
    DeskTriggerFixtures.audited(stale, "VERIFIED", now.minus(Duration.ofDays(8)));

    sweep.sweep(now);

    assertTrue(DeskTriggerFixtures.eventsOf(projectId).isEmpty(), "ten minutes in the desk's time");

    sweep.sweep(now.plus(Duration.ofMinutes(21)));

    List<DeskTriggerEvent> lines = DeskTriggerFixtures.eventsOf(projectId);
    assertEquals(1, lines.size(), "thirty minutes after delivery came on");
    assertEquals(
        "VERIFIED 2h 21m; no open release request carries ticket/" + slug + "-5",
        lines.get(0).label,
        "the label's duration is the real one");
    assertTrue(DeskTriggerFixtures.eventsOf(old).isEmpty(), "older than seven days: seen");
  }

  @Test
  void aNewMoveIntoVerifiedIsANewLine() {
    String projectId = project(true);
    WorkEntity item = running(projectId, 8);
    DeskTriggerFixtures.audited(item, "VERIFIED", now.minus(Duration.ofHours(3)));
    sweep.sweep(now.minus(Duration.ofHours(2)));
    DeskTriggerFixtures.audited(item, "VERIFYING", now.minus(Duration.ofMinutes(90)));
    DeskTriggerFixtures.audited(item, "VERIFIED", now.minus(Duration.ofMinutes(40)));

    sweep.sweep(now);

    List<DeskTriggerEvent> lines = DeskTriggerFixtures.eventsOf(projectId);
    assertEquals(2, lines.size(), "one per move into VERIFIED");
    assertNotEquals(lines.get(0).eventId, lines.get(1).eventId);
  }

  // -------------------------------------------------------------------------------------------

  private String project(boolean delivery) {
    String id = DeskTriggerFixtures.project(delivery);
    projects.add(id);
    return id;
  }

  /** A VERIFIED ticket with an ACTIVE agent, so it holds a slot. */
  private WorkEntity running(String projectId, long number) {
    WorkEntity item = DeskTriggerFixtures.ticket(projectId, number, "VERIFIED");
    agents.add(DeskTriggerFixtures.activeAgent(item));
    dispatch.willFind(agents.toArray(WorkspaceAgentDispatch.Agent[]::new));
    return item;
  }
}
