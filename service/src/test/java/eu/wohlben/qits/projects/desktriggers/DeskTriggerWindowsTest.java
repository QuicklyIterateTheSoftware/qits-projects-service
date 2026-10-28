package eu.wohlben.qits.projects.desktriggers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import eu.wohlben.qits.projects.entity.DeskTriggerWindow;
import eu.wohlben.qits.projects.entity.DeskTriggerWindowState;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * <b>The window store</b> (qits-1608): the first trigger opens a window on the configured clock, a
 * later one joins it without moving that clock, a repeat is one line, and two projects never share
 * a window. Against the real schema, because every one of those is a constraint in V57 and not
 * code.
 */
@QuarkusTest
class DeskTriggerWindowsTest {

  private static final Instant T0 = Instant.parse("2026-10-25T10:00:00Z");

  @Inject DeskTriggerWindows windows;

  private final List<String> projects = new ArrayList<>();

  @AfterEach
  void drop() {
    DeskTriggerFixtures.drop(projects);
    projects.clear();
  }

  private String project() {
    String id = DeskTriggerFixtures.project(true);
    projects.add(id);
    return id;
  }

  /** What the sweep's close does to the row, enough that the next admit opens a new window. */
  private static void close(UUID windowId) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              DeskTriggerWindow window = DeskTriggerWindow.findById(windowId);
              window.state = DeskTriggerWindowState.OWED;
              window.closedAt = T0.plus(Duration.ofMinutes(15));
            });
  }

  private static DeskTrigger trigger(String projectId, String ref, String label) {
    return new DeskTrigger(projectId, ref, null, "someone", label, UUID.randomUUID(), T0);
  }

  @Test
  void theFirstTriggerOpensAWindowClosingFifteenMinutesLater() {
    String projectId = project();

    UUID windowId = windows.admit(projectId, "work-verified", trigger(projectId, "p-1", "one"), T0);

    DeskTriggerWindow window = DeskTriggerFixtures.window(windowId);
    assertEquals(DeskTriggerWindowState.OPEN, window.state);
    assertEquals(T0, window.openedAt);
    assertEquals(T0.plus(Duration.ofMinutes(15)), window.closesAt);
    assertEquals(0, window.attempts);
    assertEquals(1, DeskTriggerFixtures.events(windowId).size());
  }

  @Test
  void aLaterTriggerJoinsTheOpenWindowAndLeavesItsCloseAlone() {
    String projectId = project();

    UUID first = windows.admit(projectId, "work-verified", trigger(projectId, "p-1", "one"), T0);
    UUID second =
        windows.admit(
            projectId,
            "work-verified",
            trigger(projectId, "p-2", "two"),
            T0.plus(Duration.ofMinutes(10)));

    assertEquals(first, second, "one open window per project");
    assertEquals(1, DeskTriggerFixtures.windows(projectId).size());
    assertEquals(
        T0.plus(Duration.ofMinutes(15)),
        DeskTriggerFixtures.window(first).closesAt,
        "a join never moves closes_at");
    assertEquals(2, DeskTriggerFixtures.events(first).size());
  }

  @Test
  void theSameItemTwiceIsOneLine() {
    String projectId = project();

    UUID windowId =
        windows.admit(projectId, "work-verified", trigger(projectId, "p-1", "one"), T0);
    windows.admit(
        projectId, "work-verified", trigger(projectId, "p-1", "one"), T0.plusSeconds(30));

    assertEquals(1, DeskTriggerFixtures.events(windowId).size());
  }

  /**
   * V65 (qits-1694): an event is one line per item across windows, whatever its label says now — a
   * stall's label carries its duration, so a second admitter racing the first would otherwise land
   * the same stall in the next window.
   */
  @Test
  void aDuplicateEventAndItemInALaterWindowInsertsNothing() {
    String projectId = project();
    UUID eventId = UUID.randomUUID();
    UUID first =
        windows.admit(
            projectId,
            "request-stalled",
            new DeskTrigger(projectId, "p-rr-1", null, "fold aaaaaaaa", "for 2h 1m", eventId, T0),
            T0);
    close(first);

    UUID none =
        windows.admit(
            projectId,
            "request-stalled",
            new DeskTrigger(projectId, "p-rr-1", null, "fold aaaaaaaa", "for 2h 31m", eventId, T0),
            T0.plus(Duration.ofMinutes(30)));

    assertNull(none, "nothing admitted, and no window left open for it");
    assertEquals(1, DeskTriggerFixtures.windows(projectId).size(), "no empty window is left behind");
    assertEquals(1, DeskTriggerFixtures.eventsOf(projectId).size());

    UUID open =
        windows.admit(
            projectId, "work-verified", trigger(projectId, "p-2", "two"), T0.plus(Duration.ofMinutes(31)));
    windows.admit(
        projectId,
        "request-stalled",
        new DeskTrigger(projectId, "p-rr-1", null, "fold aaaaaaaa", "for 2h 32m", eventId, T0),
        T0.plus(Duration.ofMinutes(32)));
    assertEquals(1, DeskTriggerFixtures.events(open).size(), "nor into an open window that exists");
  }

  /** V65 (qits-1694): capacity-opened's one line per agent is the schema's rule, too. */
  @Test
  void aSecondCapacityLineForOneAgentInsertsNothing() {
    String projectId = project();
    UUID first =
        windows.admit(
            projectId,
            "capacity-opened",
            new DeskTrigger(projectId, "p-1", "agent-1", "agent agent-1", "done", UUID.randomUUID(), T0),
            T0);
    windows.admit(
        projectId,
        "capacity-opened",
        new DeskTrigger(projectId, "p-1", "agent-1", "agent agent-1", "removed", UUID.randomUUID(), T0),
        T0.plusSeconds(30));

    assertEquals(1, DeskTriggerFixtures.events(first).size());
  }

  @Test
  void twoProjectsGetTwoWindows() {
    String a = project();
    String b = project();

    UUID windowA = windows.admit(a, "work-verified", trigger(a, "a-1", "one"), T0);
    UUID windowB = windows.admit(b, "work-verified", trigger(b, "b-1", "one"), T0);

    assertNotEquals(windowA, windowB);
    assertEquals(a, DeskTriggerFixtures.window(windowA).projectId);
    assertEquals(b, DeskTriggerFixtures.window(windowB).projectId);
  }

  @Test
  void aNanosecondNowIsStoredAsTheMicrosecondPostgresKeeps() {
    String projectId = project();
    Instant now = T0.plusNanos(123_456_789);

    UUID windowId = windows.admit(projectId, "work-verified", trigger(projectId, "p-1", "x"), now);

    assertEquals(now.truncatedTo(ChronoUnit.MICROS), DeskTriggerFixtures.window(windowId).openedAt);
  }
}
