package eu.wohlben.qits.projects.desktriggers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import eu.wohlben.qits.projects.entity.DeskTriggerWindow;
import eu.wohlben.qits.projects.entity.DeskTriggerWindowState;
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
