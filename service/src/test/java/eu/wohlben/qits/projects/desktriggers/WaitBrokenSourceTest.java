package eu.wohlben.qits.projects.desktriggers;

import static eu.wohlben.qits.projects.desktriggers.DeskTriggerTestFrames.agentWaitClosed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.entities.entity.WorkEntity;
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
 * <b>The {@code wait-broken} source</b> (qits-1961): an {@code AgentWaitClosed} naming {@code
 * BROKEN} is one line for the desk of the project it names, naming the agent's work item when it
 * resolves cheaply and the agent otherwise; {@code MATCHED}, {@code TIMED_OUT} and {@code
 * CANCELLED} are ignored; a replay adds nothing, in the window or after it closed; and nothing the
 * desk did breaks an agent's wait, so there is no principal.
 */
@QuarkusTest
class WaitBrokenSourceTest {

  private static final String AGENT = "11112222-3333-4444-5555-666677778888";

  private static final String WAIT = "99990000-aaaa-bbbb-cccc-ddddeeeeffff";

  @Inject DeskTriggerListener listener;

  @Inject DeskTriggerSweep sweep;

  @Inject WaitBrokenSource source;

  private final List<String> projects = new ArrayList<>();

  @AfterEach
  void drop() {
    DeskTriggerFixtures.dropWork(projects);
    DeskTriggerFixtures.drop(projects);
    projects.clear();
  }

  private String project(boolean deliveryEnabled) {
    String id = DeskTriggerFixtures.project(deliveryEnabled);
    projects.add(id);
    return id;
  }

  @Test
  void itReadsAgentWaitClosedAsNews() {
    assertEquals("wait-broken", source.id());
    assertEquals(Set.of("AgentWaitClosed"), source.signatures());
    assertFalse(source.requestedByDesk(), "news, not an answer");
  }

  @Test
  void aBrokenWaitNamesTheAgentsWorkItem() {
    String projectId = project(true);
    WorkEntity ticket = DeskTriggerFixtures.ticket(projectId, 7, "IMPLEMENTING");
    String slug = DeskTriggerFixtures.slugOf(projectId);

    EventFrame frame =
        agentWaitClosed(
            AGENT, ticket.id, projectId, WAIT, "BROKEN", "ci run ws-proof", "the runner is gone");
    listener.onFrame(frame);

    List<DeskTriggerEvent> lines = DeskTriggerFixtures.eventsOf(projectId);
    assertEquals(1, lines.size());
    DeskTriggerEvent line = lines.get(0);
    assertEquals("wait-broken", line.source);
    assertEquals(slug + "-7", line.itemRef);
    assertEquals(WAIT, line.itemId);
    assertEquals("broken wait \"ci run ws-proof\": the runner is gone", line.label);
    assertEquals("agent 11112222", line.cause);
    assertEquals(UUID.fromString(frame.id()), line.eventId);
  }

  @Test
  void aWorkIdThatDoesNotResolveFallsBackToTheAgentId() {
    String projectId = project(true);

    listener.onFrame(
        agentWaitClosed(
            AGENT, "no-such-work-item", projectId, WAIT, "BROKEN", "label", "reason"));

    List<DeskTriggerEvent> lines = DeskTriggerFixtures.eventsOf(projectId);
    assertEquals(1, lines.size());
    assertEquals("agent 11112222", lines.get(0).itemRef);
  }

  @Test
  void aBlankWorkIdFallsBackToTheAgentId() {
    String projectId = project(true);

    listener.onFrame(agentWaitClosed(AGENT, null, projectId, WAIT, "BROKEN", "label", "reason"));

    List<DeskTriggerEvent> lines = DeskTriggerFixtures.eventsOf(projectId);
    assertEquals(1, lines.size());
    assertEquals("agent 11112222", lines.get(0).itemRef);
  }

  @Test
  void matchedTimedOutAndCancelledAreIgnored() {
    String projectId = project(true);

    for (String state : List.of("MATCHED", "TIMED_OUT", "CANCELLED")) {
      assertTrue(
          QuarkusTransaction.requiringNew()
              .call(
                  () ->
                      source.qualify(
                          agentWaitClosed(
                              AGENT, null, projectId, WAIT, state, "label", "reason")))
              .isEmpty(),
          state + " is not this source's concern");
    }
  }

  @Test
  void anEventMissingProjectOrWaitOrAgentIsNothing() {
    assertTrue(
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    source.qualify(
                        agentWaitClosed(AGENT, null, null, WAIT, "BROKEN", "label", "reason")))
            .isEmpty());
    assertTrue(
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    source.qualify(
                        agentWaitClosed(AGENT, null, "p-1", null, "BROKEN", "label", "reason")))
            .isEmpty());
    assertTrue(
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    source.qualify(
                        agentWaitClosed(null, null, "p-1", WAIT, "BROKEN", "label", "reason")))
            .isEmpty());
  }

  @Test
  void aProjectWithDeliveryOffIsDropped() {
    String off = project(false);

    listener.onFrame(agentWaitClosed(AGENT, null, off, WAIT, "BROKEN", "label", "reason"));

    assertTrue(DeskTriggerFixtures.windows(off).isEmpty(), "delivery off: nothing admitted");
  }

  @Test
  void aRedeliveredFrameAddsNothingInTheWindowOrAfterItClosed() {
    String projectId = project(true);
    EventFrame frame =
        agentWaitClosed(AGENT, null, projectId, WAIT, "BROKEN", "label", "reason");

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
  void itsSectionReadsAsSentences() {
    DeskTriggerEventRow row =
        new DeskTriggerEventRow(
            "wait-broken",
            "qits-7",
            WAIT,
            "broken wait \"ci run ws-proof\": the runner is gone",
            "agent 11112222",
            UUID.randomUUID(),
            Instant.now(),
            Instant.now());

    assertEquals(
        "Agents' waits that broke:\n"
            + "- qits-7: broken wait \"ci run ws-proof\": the runner is gone",
        source.render("qits", List.of(row)));
  }

  @Test
  void itHasNoPrincipal() {
    String projectId = project(true);

    List<DeskTrigger> triggers =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    source.qualify(
                        agentWaitClosed(
                            AGENT, null, projectId, WAIT, "BROKEN", "label", "reason")));

    assertEquals(1, triggers.size());
    assertNull(triggers.get(0).principal(), "nothing the desk did breaks an agent's wait");
  }
}
