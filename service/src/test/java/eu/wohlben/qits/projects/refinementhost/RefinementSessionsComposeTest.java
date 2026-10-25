package eu.wohlben.qits.projects.refinementhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.entity.FrontDeskLifecycle;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.Refinement;
import eu.wohlben.qits.projectsdaemon.protocol.DaemonProtocol;
import eu.wohlben.qits.projectsdaemon.protocol.SessionCredentials;
import eu.wohlben.qits.projectsdaemon.protocol.Sessions;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link RefinementSessions#compose(Project, List, java.util.function.Function, Instant, Duration)}
 * (epic qits-770): pure — the front desk's entry by lifecycle, a session room's state by its idle
 * window, its resume id and entity facts, and a legacy container room left out.
 */
class RefinementSessionsComposeTest {

  private static final Instant NOW = Instant.parse("2026-10-25T12:00:00Z");

  private static final Duration IDLE = Duration.ofHours(4);

  private static Project project(FrontDeskLifecycle lifecycle) {
    Project project = new Project();
    project.id = "p1";
    project.slug = "demo";
    project.frontDeskLifecycle = lifecycle;
    return project;
  }

  private static Refinement room(long id, String entityId, Instant lastDemandAt) {
    Refinement room = new Refinement();
    room.id = id;
    room.entityId = entityId;
    room.projectId = "p1";
    room.lastDemandAt = lastDemandAt;
    return room;
  }

  @Test
  void anAlwaysOnDeskLeadsWithItsOwnRunningSession() {
    Sessions frame =
        RefinementSessions.compose(
            project(FrontDeskLifecycle.ALWAYS_ON), List.of(), id -> null, NOW, IDLE);

    assertEquals(1, frame.sessions().size());
    Sessions.Session desk = frame.sessions().get(0);
    assertEquals(SessionCredentials.FRONT_DESK_KEY, desk.key());
    assertEquals(RefinementSessions.FRONT_DESK_SURFACE, desk.surface());
    assertEquals(DaemonProtocol.DesiredState.RUNNING, desk.state());
    assertNull(desk.entity());
  }

  @Test
  void anOnDemandDeskIsLeftOutForThePanelToLaunch() {
    Sessions frame =
        RefinementSessions.compose(
            project(FrontDeskLifecycle.ON_DEMAND),
            List.of(room(1, "e1", NOW)),
            id -> null,
            NOW,
            IDLE);

    assertTrue(frame.find(SessionCredentials.FRONT_DESK_KEY).isEmpty());
    assertEquals(List.of("e1"), frame.sessions().stream().map(Sessions.Session::key).toList());
  }

  @Test
  void aRoomRunsInsideItsIdleWindowAndStopsOutsideIt() {
    Refinement fresh = room(1, "e1", NOW.minus(Duration.ofHours(3)));
    Refinement stale = room(2, "e2", NOW.minus(Duration.ofHours(4)));
    Refinement stopped = room(3, "e3", null);

    Sessions frame =
        RefinementSessions.compose(
            project(FrontDeskLifecycle.ON_DEMAND),
            List.of(fresh, stale, stopped),
            id -> null,
            NOW,
            IDLE);

    assertEquals(DaemonProtocol.DesiredState.RUNNING, frame.find("e1").orElseThrow().state());
    assertEquals(DaemonProtocol.DesiredState.STOPPED, frame.find("e2").orElseThrow().state());
    assertEquals(DaemonProtocol.DesiredState.STOPPED, frame.find("e3").orElseThrow().state());
    assertTrue(RefinementSessions.wantsRunning(fresh, NOW, IDLE));
    assertFalse(RefinementSessions.wantsRunning(stale, NOW, IDLE));
    assertFalse(RefinementSessions.wantsRunning(stopped, NOW, IDLE));
    // No idle window: once asked for, wanted until stopped.
    assertTrue(RefinementSessions.wantsRunning(stale, NOW, Duration.ZERO));
  }

  @Test
  void aRoomCarriesItsSurfaceResumeIdAndEntityFacts() {
    Refinement room = room(1, "e1", NOW);
    room.sessionId = "sess-7";
    Sessions.Entity facts = new Sessions.Entity("demo-12", "Export", "REPORTED", true, "EXPLICIT");

    Sessions.Session session =
        RefinementSessions.compose(
                project(FrontDeskLifecycle.ON_DEMAND),
                List.of(room),
                Map.of("e1", facts)::get,
                NOW,
                IDLE)
            .find("e1")
            .orElseThrow();

    assertEquals(RefinementSessions.ROOM_SURFACE, session.surface());
    assertEquals("sess-7", session.resumeSessionId());
    assertEquals(facts, session.entity());
  }

  @Test
  void aLegacyContainerRoomIsNeverASession() {
    Refinement legacy = room(1, "e1", NOW);
    legacy.branch = "refining/export";

    Sessions frame =
        RefinementSessions.compose(
            project(FrontDeskLifecycle.ON_DEMAND), List.of(legacy), id -> null, NOW, IDLE);

    assertTrue(frame.sessions().isEmpty());
    assertFalse(RefinementSessions.wantsRunning(legacy, NOW, IDLE));
  }
}
