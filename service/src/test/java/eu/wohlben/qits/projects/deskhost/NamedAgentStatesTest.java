package eu.wohlben.qits.projects.deskhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.control.NamedAgentStateAnnouncer.NamedAgentStateChange;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The dedup behind {@code NamedAgentStateChanged} (qits-2157): one announcement per change of an
 * agent's (state, commandId), the first observation announcing, the desk never.
 */
public class NamedAgentStatesTest {

  private static final Instant AT = Instant.parse("2026-11-14T10:00:00Z");

  private final List<NamedAgentStateChange> announced = new ArrayList<>();

  private final NamedAgentStates states = new NamedAgentStates(announced::add);

  private static NamedAgentHost.AgentState ui(String state, String commandId) {
    return new NamedAgentHost.AgentState(
        "ui-builder", "project.ui-builder", state, "sess-ui", commandId);
  }

  @Test
  public void theSameStateTwiceIsOneEvent() {
    assertTrue(states.observed("p1", ui("IDLE", "cmd-ui"), true, AT), "the first one announces");
    assertFalse(states.observed("p1", ui("IDLE", "cmd-ui"), true, AT));
    assertEquals(1, announced.size());
    NamedAgentStateChange change = announced.get(0);
    assertEquals("p1", change.projectId());
    assertEquals("ui-builder", change.agentName());
    assertEquals("IDLE", change.state());
    assertEquals("cmd-ui", change.commandId());
    assertEquals("sess-ui", change.sessionId());
    assertEquals(AT, change.changedAt());
  }

  @Test
  public void idleBusyIdleIsThreeEvents() {
    states.observed("p1", ui("IDLE", "cmd-ui"), true, AT);
    states.observed("p1", ui("BUSY", "cmd-ui"), true, AT);
    states.observed("p1", ui("BUSY", "cmd-ui"), true, AT);
    states.observed("p1", ui("IDLE", "cmd-ui"), true, AT);
    assertEquals(
        List.of("IDLE", "BUSY", "IDLE"),
        announced.stream().map(NamedAgentStateChange::state).toList());
  }

  @Test
  public void aNewCommandInTheSameStateIsAChange() {
    states.observed("p1", ui("IDLE", "cmd-1"), true, AT);
    states.observed("p1", ui("IDLE", "cmd-2"), true, AT);
    assertEquals(2, announced.size());
  }

  @Test
  public void projectsAreKeptApart() {
    states.observed("p1", ui("IDLE", "cmd-ui"), true, AT);
    states.observed("p2", ui("IDLE", "cmd-ui"), true, AT);
    assertEquals(2, announced.size());
  }

  @Test
  public void theDeskIsNeverAnnounced() {
    NamedAgentHost.AgentState desk =
        new NamedAgentHost.AgentState("desk", "project.work", "BUSY", "s", "c");
    assertFalse(states.observed("p1", desk, true, AT));
    assertFalse(states.observed("p1", desk, true, AT));
    assertTrue(announced.isEmpty());
  }

  @Test
  public void anAgentNobodyPromptedIsNotAnnouncedUntilItIsThenItsStopIs() {
    assertFalse(states.observed("p1", ui("STOPPED", null), false, AT), "never prompted: silence");
    assertTrue(states.observed("p1", ui("STARTING", null), true, AT));
    assertTrue(states.observed("p1", ui("STOPPED", null), false, AT), "its row went: STOPPED");
    assertEquals(
        List.of("STARTING", "STOPPED"),
        announced.stream().map(NamedAgentStateChange::state).toList());
  }

  @Test
  public void aFailedAnnouncementIsTriedAgain() {
    List<String> seen = new ArrayList<>();
    boolean[] fail = {true};
    NamedAgentStates flaky =
        new NamedAgentStates(
            change -> {
              if (fail[0]) {
                throw new IllegalStateException("bus down");
              }
              seen.add(change.state());
            });
    assertFalse(flaky.observed("p1", ui("BUSY", "cmd-ui"), true, AT), "never throws");
    fail[0] = false;
    assertTrue(flaky.observed("p1", ui("BUSY", "cmd-ui"), true, AT));
    assertEquals(List.of("BUSY"), seen);
  }
}
