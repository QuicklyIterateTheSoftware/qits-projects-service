package eu.wohlben.qits.projects.desktriggers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.projects.control.WorkspaceAgentLookup;
import eu.wohlben.qits.projects.control.WorkspaceAgentLookup.ActiveAgent;
import eu.wohlben.qits.projects.control.WorkspaceAgentLookup.AgentWait;
import eu.wohlben.qits.projects.entity.DeskTriggerEvent;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.testsupport.RecordingWorkspaceAgentLookup;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>agent-idle</b> (qits-1756): the sweep finds an ACTIVE agent that has had no turn for 2h and
 * tells the desk once per idle stretch — a new {@code lastActivityAt} is a new stretch, a BUSY agent
 * is never idle, and a stretch is measured from when the desk was turned on.
 */
@QuarkusTest
class AgentIdleSourceTest {

  @Inject DeskTriggerSweep sweep;

  @Inject DeskTriggerListener listener;

  @Inject RecordingWorkspaceAgentLookup agents;

  @Inject Instance<WorkspaceAgentLookup> lookup;

  private final List<String> projects = new ArrayList<>();

  private long numbers = 1;

  @BeforeEach
  void resetThePort() {
    agents.reset();
  }

  @AfterEach
  void drop() {
    agents.reset();
    QuarkusTransaction.requiringNew()
        .run(() -> projects.forEach(id -> WorkEntity.delete("projectId", id)));
    DeskTriggerFixtures.drop(projects);
    projects.clear();
  }

  @Test
  void anAgentQuietForTwoHoursIsOneLineWithItsWaitsAndItsBlock() {
    String projectId = project(true);
    WorkEntity item = ticket(projectId, "Some title");
    block(item, "waits on   the owner\nfor a decision");
    WorkEntity fresh = ticket(projectId, "Not yet");
    Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    Instant last = now.minus(Duration.ofMinutes(121));
    agents.stage(
        agent(
            "agent-idle-" + item.id,
            item,
            "WAITING",
            last,
            List.of(
                new AgentWait(
                    "qits-1625 live in qits-projects-service",
                    "OPEN",
                    "BRANCH",
                    "ticket/qits-1625",
                    null,
                    "JOINED",
                    Instant.parse("2026-10-30T12:54:00Z")),
                new AgentWait("an old wait", "MATCHED", null, null, null, "LIVE", null),
                new AgentWait("rr-9 to end", "OPEN", null, null, null, null, null))));
    agents.stage(
        agent(
            "agent-fresh-" + fresh.id,
            fresh,
            "IDLE",
            now.minus(Duration.ofMinutes(119)),
            List.of()));

    sweep.sweep(now);

    List<DeskTriggerEvent> lines = DeskTriggerFixtures.eventsOf(projectId);
    assertEquals(1, lines.size(), "1h59m is not idle yet: " + lines);
    DeskTriggerEvent line = lines.get(0);
    assertEquals("agent-idle", line.source);
    assertEquals(DeskTriggerFixtures.slugOf(projectId) + "-" + item.number, line.itemRef);
    assertEquals("agent-idle-" + item.id, line.itemId);
    assertEquals("agent agent-id", line.cause);
    assertEquals(AgentIdleSource.idleKey("agent-idle-" + item.id, last), line.eventId);
    assertEquals(
        "Some title; IMPLEMENTING; idle 2h 1m since "
            + minutes(last)
            + "; waits on: qits-1625 live in qits-projects-service (JOINED, expires"
            + " 2026-10-30T12:54Z); rr-9 to end (OPEN, expires never); blocked: waits on the owner"
            + " for a decision",
        line.label);
  }

  @Test
  void oneStretchIsToldOnceHoweverOftenItIsScanned() {
    String projectId = project(true);
    WorkEntity item = ticket(projectId, "Quiet");
    Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    Instant last = now.minus(Duration.ofMinutes(121));
    agents.stage(agent("agent-" + item.id, item, null, last, List.of()));

    sweep.sweep(now);
    sweep.sweep(last.plus(Duration.ofMinutes(135)));
    sweep.sweep(last.plus(Duration.ofHours(4)));

    List<DeskTriggerEvent> lines = DeskTriggerFixtures.eventsOf(projectId);
    assertEquals(1, lines.size(), "2h15m and 4h into one stretch add nothing: " + lines);
    assertTrue(lines.get(0).label.endsWith("; no open wait"), lines.get(0).label);
  }

  @Test
  void aNewTurnStartsANewStretchToldOnlyAfterAnotherTwoHours() {
    String projectId = project(true);
    WorkEntity item = ticket(projectId, "Comes back");
    String agentId = "agent-" + item.id;
    Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    agents.stage(agent(agentId, item, "WAITING", now.minus(Duration.ofHours(3)), List.of()));
    sweep.sweep(now);
    assertEquals(1, DeskTriggerFixtures.eventsOf(projectId).size());

    // A turn an hour ago moved lastActivityAt: the new stretch is too young.
    Instant turn = now.minus(Duration.ofHours(1));
    agents.stage(agent(agentId, item, "IDLE", turn, List.of()));
    sweep.sweep(now);
    assertEquals(1, DeskTriggerFixtures.eventsOf(projectId).size(), "no line inside 2h of a turn");

    sweep.sweep(turn.plus(Duration.ofMinutes(121)));

    List<DeskTriggerEvent> lines = DeskTriggerFixtures.eventsOf(projectId);
    assertEquals(2, lines.size(), "the new stretch is its own line: " + lines);
    assertNotEquals(lines.get(0).eventId, lines.get(1).eventId);
    assertEquals(AgentIdleSource.idleKey(agentId, turn), lines.get(1).eventId);
  }

  @Test
  void aBusyAgentIsNeverIdleAndOneWithNoActivityIs() {
    String projectId = project(true);
    WorkEntity busy = ticket(projectId, "Long turn");
    WorkEntity yielded = ticket(projectId, "Yielded");
    Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    agents.stage(agent("agent-busy", busy, "BUSY", now.minus(Duration.ofHours(3)), List.of()));
    agents.stage(agent("agent-none", yielded, null, now.minus(Duration.ofHours(3)), List.of()));

    sweep.sweep(now);

    List<DeskTriggerEvent> lines = DeskTriggerFixtures.eventsOf(projectId);
    assertEquals(1, lines.size(), lines.toString());
    assertEquals(
        "agent-none", lines.get(0).itemId, "a BUSY turn's stale lastActivityAt is not idle");
  }

  @Test
  void anIdleAgentsDeskCommentKeepsItsOwnLine() {
    String projectId = project(true);
    WorkEntity item = ticket(projectId, "Asks the desk");
    Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    agents.stage(
        agent("agent-" + item.id, item, "WAITING", now.minus(Duration.ofHours(3)), List.of()));

    listener.onFrame(
        DeskTriggerTestFrames.commented(
            projectId, item.number, item.title, "dana", null, "dana", "desk"));
    sweep.sweep(now);

    assertEquals(1, DeskTriggerFixtures.windows(projectId).size(), "both in one window");
    Set<String> sources =
        DeskTriggerFixtures.eventsOf(projectId).stream()
            .map(line -> line.source)
            .collect(Collectors.toSet());
    assertEquals(Set.of("desk-mentioned", "agent-idle"), sources);
  }

  @Test
  void theMarkIsTheStoredRowSoAFreshSourceAddsNothing() {
    String projectId = project(true);
    WorkEntity item = ticket(projectId, "Across a restart");
    Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    agents.stage(agent("agent-" + item.id, item, null, now.minus(Duration.ofHours(3)), List.of()));
    sweep.sweep(now);
    assertEquals(1, DeskTriggerFixtures.eventsOf(projectId).size());

    // What a restarted process has: a new bean, nothing in memory, the table as it was.
    AgentIdleSource restarted = new AgentIdleSource();
    restarted.lookup = lookup;
    List<DeskTrigger> again =
        QuarkusTransaction.requiringNew()
            .call(() -> restarted.scan(now.plus(Duration.ofMinutes(30))));

    assertTrue(
        again.stream().noneMatch(trigger -> projectId.equals(trigger.projectId())),
        again.toString());
  }

  @Test
  void aStretchIsMeasuredFromTheDesksArrivalAndAgesOut() {
    String projectId = project(true);
    WorkEntity item = ticket(projectId, "Quiet before the desk");
    String aged = project(true);
    WorkEntity ancient = ticket(aged, "Quiet for a week");
    Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    Instant last = now.minus(Duration.ofHours(5));
    agents.stage(agent("agent-" + item.id, item, "WAITING", last, List.of()));
    agents.stage(
        agent("agent-" + ancient.id, ancient, "IDLE", now.minus(Duration.ofDays(8)), List.of()));
    enabledAt(projectId, now.minus(Duration.ofMinutes(30)));
    enabledAt(aged, now.minus(Duration.ofDays(9)));

    sweep.sweep(now);
    assertTrue(
        DeskTriggerFixtures.windows(projectId).isEmpty(), "the desk has been on for 30m only");

    enabledAt(projectId, now.minus(Duration.ofMinutes(121)));
    sweep.sweep(now);

    List<DeskTriggerEvent> lines = DeskTriggerFixtures.eventsOf(projectId);
    assertEquals(1, lines.size(), "2h after the desk arrived: " + lines);
    assertEquals("agent-" + item.id, lines.get(0).itemId);
    assertTrue(
        lines.get(0).label.contains("; idle 5h 0m since "),
        "the label counts the real idle time: " + lines.get(0).label);
    assertEquals(
        AgentIdleSource.idleKey("agent-" + item.id, last),
        lines.get(0).eventId,
        "the clamp never enters the key");
    assertTrue(
        DeskTriggerFixtures.windows(aged).isEmpty(), "8 days from its clamp is past the max age");
  }

  @Test
  void aProjectWithDeliveryOffIsDroppedAndNothingStored() {
    String projectId = project(false);
    WorkEntity item = ticket(projectId, "Nobody listens");
    Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    Instant last = now.minus(Duration.ofHours(3));
    agents.stage(agent("agent-" + item.id, item, null, last, List.of()));

    sweep.sweep(now);

    assertTrue(DeskTriggerFixtures.windows(projectId).isEmpty());
    long stored =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    DeskTriggerEvent.count(
                        "source = ?1 and eventId = ?2",
                        AgentIdleSource.ID,
                        AgentIdleSource.idleKey("agent-" + item.id, last)));
    assertEquals(0, stored);
  }

  // -------------------------------------------------------------------------------------------

  private static ActiveAgent agent(
      String agentId, WorkEntity item, String activity, Instant last, List<AgentWait> waits) {
    return new ActiveAgent(agentId, item.id, null, null, activity, last, waits);
  }

  /** {@code 2026-10-30T07:03Z}. */
  private static String minutes(Instant at) {
    return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm'Z'")
        .withZone(java.time.ZoneOffset.UTC)
        .format(at);
  }

  private String project(boolean delivery) {
    String id = DeskTriggerFixtures.project(delivery);
    projects.add(id);
    return id;
  }

  private static void enabledAt(String projectId, Instant at) {
    QuarkusTransaction.requiringNew()
        .run(() -> Project.<Project>findById(projectId).deliveryEnabledAt = at);
  }

  private static void block(WorkEntity item, String reason) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              WorkEntity row = WorkEntity.findById(item.id);
              row.blocked = true;
              row.blockedReason = reason;
            });
  }

  /** A ticket of the project, IMPLEMENTING. */
  private WorkEntity ticket(String projectId, String title) {
    long number = numbers++;
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              WorkEntity row = new WorkEntity();
              row.id = UUID.randomUUID().toString();
              row.projectId = projectId;
              row.archetype = Archetype.TICKET;
              row.number = number;
              row.title = title;
              row.slug = "slug-" + row.id;
              row.slugScope = projectId;
              row.status = "IMPLEMENTING";
              row.assignee = "ws-agent-" + number;
              row.createdAt = Instant.now();
              row.updatedAt = Instant.now();
              row.persist();
              return row;
            });
  }
}
