package eu.wohlben.qits.projects.desktriggers;

import static eu.wohlben.qits.projects.desktriggers.DeskTriggerTestFrames.oomKilled;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.projects.entity.DeskTriggerEvent;
import eu.wohlben.qits.projects.entity.DeskTriggerWindow;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * <b>The {@code target-oom-killed} source</b> (qits-1818): a {@code TargetOomKilled} from
 * qits-system is one line for the desk of each project it names, reading the node counter (or the
 * kernel's) before and after; a project with delivery off is dropped; an event naming no project is
 * nothing; and a replay adds nothing, in the window or after it closed.
 */
@QuarkusTest
class TargetOomKilledSourceTest {

  private static final String TARGET = "0a1b2c3d-4e5f-4061-8728-394a5b6c7d8e";

  private static final String LINE =
      "the out-of-memory killer killed 3 processes (node counter 292 → 295)";

  @Inject DeskTriggerListener listener;

  @Inject DeskTriggerSweep sweep;

  @Inject TargetOomKilledSource source;

  private final List<String> projects = new ArrayList<>();

  @AfterEach
  void drop() {
    DeskTriggerFixtures.drop(projects);
    projects.clear();
  }

  private String project(boolean deliveryEnabled) {
    String id = DeskTriggerFixtures.project(deliveryEnabled);
    projects.add(id);
    return id;
  }

  @Test
  void itReadsTargetOomKilledAsNews() {
    assertEquals("target-oom-killed", source.id());
    assertEquals(Set.of("TargetOomKilled"), source.signatures());
    assertFalse(source.requestedByDesk(), "news, not an answer");
  }

  @Test
  void oneEventIsOneLinePerProjectItNames() {
    String first = project(true);
    String second = project(true);

    EventFrame frame = oomKilled(TARGET, "ws-proof", 295L, 1200L, 3, first, second);
    listener.onFrame(frame);

    for (String projectId : List.of(first, second)) {
      List<DeskTriggerEvent> lines = DeskTriggerFixtures.eventsOf(projectId);
      assertEquals(1, lines.size(), "one line for " + projectId);
      DeskTriggerEvent line = lines.get(0);
      assertEquals("target-oom-killed", line.source);
      assertEquals("target ws-proof", line.itemRef);
      assertEquals(TARGET, line.itemId);
      assertEquals(LINE, line.label, "the node counter wins over the kernel's");
      assertEquals("oom 0a1b2c3d", line.cause);
      assertEquals(Instant.parse("2026-11-02T09:00:00Z"), line.occurredAt);
      assertEquals(
          TargetOomKilledSource.oomKey(UUID.fromString(frame.id()), projectId), line.eventId);
    }
    assertNotEquals(
        DeskTriggerFixtures.eventsOf(first).get(0).eventId,
        DeskTriggerFixtures.eventsOf(second).get(0).eventId,
        "each project's line has its own key, so V65's index admits both");
  }

  @Test
  void aProjectWithDeliveryOffIsDropped() {
    String on = project(true);
    String off = project(false);

    listener.onFrame(oomKilled(TARGET, "ws-proof", 295L, null, 3, on, off));

    assertEquals(1, DeskTriggerFixtures.eventsOf(on).size());
    assertTrue(DeskTriggerFixtures.windows(off).isEmpty(), "delivery off: nothing admitted");
  }

  @Test
  void anEventNamingNoProjectIsNothing() {
    assertTrue(
        QuarkusTransaction.requiringNew()
            .call(() -> source.qualify(oomKilled(TARGET, "ws-proof", 295L, null, 3)))
            .isEmpty());
  }

  @Test
  void aRedeliveredFrameAddsNothingInTheWindowOrAfterItClosed() {
    String projectId = project(true);
    EventFrame frame = oomKilled(TARGET, "ws-proof", 295L, null, 3, projectId);

    listener.onFrame(frame);
    listener.onFrame(frame);
    assertEquals(1, DeskTriggerFixtures.eventsOf(projectId).size(), "a repeat in the window");

    QuarkusTransaction.requiringNew()
        .run(
            () ->
                DeskTriggerWindow.update(
                    "closesAt = ?1 where projectId = ?2",
                    Instant.now().minusSeconds(1),
                    projectId));
    sweep.sweep();
    listener.onFrame(frame);

    assertEquals(1, DeskTriggerFixtures.windows(projectId).size(), "no second window");
    assertEquals(1, DeskTriggerFixtures.eventsOf(projectId).size(), "the replay added nothing");
  }

  @Test
  void theKernelCounterIsNamedWhenTheNodeReportsNone() {
    assertEquals(
        "the out-of-memory killer killed 2 processes (kernel counter 40 → 42)",
        TargetOomKilledSource.label(payload(null, 42L, 2)));
    assertEquals(LINE, TargetOomKilledSource.label(payload(295L, 42L, 3)));
    assertEquals(
        "the out-of-memory killer killed 1 process (node counter 7 → 8)",
        TargetOomKilledSource.label(payload(8L, null, 1)));
    assertEquals(
        "the out-of-memory killer killed 1 process",
        TargetOomKilledSource.label(payload(null, null, 1)),
        "no counter on the event: no bracket");
  }

  @Test
  void aNamelessTargetIsNamedByItsIdAndNoRiseIsNothing() {
    String projectId = project(true);

    listener.onFrame(oomKilled(TARGET, null, 295L, null, 3, projectId));
    listener.onFrame(oomKilled(TARGET, "ws-proof", 295L, null, 0, projectId));

    List<DeskTriggerEvent> lines = DeskTriggerFixtures.eventsOf(projectId);
    assertEquals(1, lines.size(), "a delta of 0 is no kill");
    assertEquals("target " + TARGET, lines.get(0).itemRef);
  }

  @Test
  void itsSectionReadsAsSentences() {
    DeskTriggerEventRow row =
        new DeskTriggerEventRow(
            "target-oom-killed",
            "target ws-proof",
            TARGET,
            LINE,
            "oom 0a1b2c3d",
            UUID.randomUUID(),
            Instant.now(),
            Instant.now());

    assertEquals(
        "Out-of-memory kills on targets running your work:\n- target ws-proof: " + LINE,
        source.render("qits", List.of(row)));
  }

  @Test
  void itHasNoPrincipal() {
    String projectId = project(true);

    List<DeskTrigger> triggers =
        QuarkusTransaction.requiringNew()
            .call(() -> source.qualify(oomKilled(TARGET, "ws-proof", 295L, null, 3, projectId)));

    assertEquals(1, triggers.size());
    assertNull(triggers.get(0).principal(), "nothing the desk did causes an OOM kill");
  }

  private static TargetOomKilledSource.TargetOomKilledPayload payload(
      Long node, Long kernel, long delta) {
    return new TargetOomKilledSource.TargetOomKilledPayload(
        TARGET, "ws-proof", node, kernel, delta, List.of("p"), null);
  }
}
