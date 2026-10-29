package eu.wohlben.qits.projects.desktriggers;

import static eu.wohlben.qits.projects.desktriggers.DeskTriggerTestFrames.entity;
import static eu.wohlben.qits.projects.desktriggers.DeskTriggerTestFrames.transitioned;
import static eu.wohlben.qits.projects.desktriggers.DeskTriggerTestFrames.waitClosed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.projects.entity.DeskTriggerEvent;
import eu.wohlben.qits.projects.entity.DeskTriggerWindow;
import eu.wohlben.qits.projects.entity.DeskTriggerWindowState;
import eu.wohlben.qits.projectsdaemon.protocol.DeskBrief;
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
 * <b>The {@code wait} source</b> (qits-1618): a {@code DeskWaitClosed} from qits-workspaces is one
 * line for the project's desk, carrying the far side's turn verbatim; it is admitted with delivery
 * off and though the desk caused it; it closes its window at once; and a wait wakes the desk once.
 */
@QuarkusTest
class WaitClosedSourceTest {

  private static final String TURN =
      "Your wait \"approve rr-264\" matched: ReleaseRequestStateChanged at"
          + " 2026-10-26T09:00:00Z (event 1f0e…).\n"
          + "releaseRequestQualifiedId: qits-qits-rr-264\nstate: FINALIZED";

  @Inject DeskTriggerListener listener;

  @Inject DeskTriggerWindows windows;

  @Inject DeskTriggerSweep sweep;

  @Inject WaitClosedSource source;

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
  void itReadsDeskWaitClosedAndAnswersTheDesk() {
    assertEquals("wait", source.id());
    assertEquals(Set.of("DeskWaitClosed"), source.signatures());
    assertTrue(source.requestedByDesk());
    assertFalse(new WorkVerifiedSource().requestedByDesk(), "news is not asked for");
    assertFalse(new ApprovalNeededSource().requestedByDesk(), "news is not asked for");
  }

  @Test
  void aClosedWaitIsOneLineKeyedByTheWaitAndCarryingTheTurn() {
    String projectId = project(true);
    String waitId = UUID.randomUUID().toString();

    listener.onFrame(waitClosed(projectId, waitId, "approve rr-264", "MATCHED", TURN));

    List<DeskTriggerEvent> lines = DeskTriggerFixtures.eventsOf(projectId);
    assertEquals(1, lines.size());
    DeskTriggerEvent line = lines.get(0);
    assertEquals("wait", line.source);
    assertEquals(waitId, line.itemRef);
    assertEquals(waitId, line.itemId);
    assertEquals(TURN, line.label, "the turn, verbatim");
    assertEquals("wait \"approve rr-264\" MATCHED", line.cause);
    assertEquals(Instant.parse("2026-10-26T09:00:00Z"), line.occurredAt);
  }

  @Test
  void itIsAdmittedWithDeliveryOff() {
    String projectId = project(false);

    listener.onFrame(
        waitClosed(projectId, UUID.randomUUID().toString(), "approve rr-264", "MATCHED", TURN));
    listener.onFrame(
        transitioned(entity(projectId, "TICKET", 1, "t", "VERIFYING", "VERIFIED", "dana")));

    List<DeskTriggerEvent> lines = DeskTriggerFixtures.eventsOf(projectId);
    assertEquals(1, lines.size(), "the wait is admitted, the news is not");
    assertEquals("wait", lines.get(0).source);
  }

  /**
   * The bypass is the policy, not the cause phrase: a source that answers the desk is admitted even
   * when its trigger's cause is one of the desk's own subjects, and the same trigger from a news
   * source is dropped.
   */
  @Test
  void aTriggerTheDeskCausedIsAdmittedOnlyWhenTheDeskAskedForIt() {
    String asked = project(true);
    String news = project(true);
    DeskTriggerFixtures.desk(asked, "tok-desk", "tok-delivery", "tok-session");
    DeskTriggerFixtures.desk(news, "tok-desk-2", "tok-delivery-2", "tok-session-2");
    DeskTriggerListener probing =
        new DeskTriggerListener(
            List.of(new CausedByDesk("asked", true), new CausedByDesk("news", false)), windows);

    probing.onFrame(probeFrame("asked", asked, "tok-delivery"));
    probing.onFrame(probeFrame("news", news, "tok-delivery-2"));

    assertEquals(1, DeskTriggerFixtures.eventsOf(asked).size(), "the desk asked: admitted");
    assertTrue(DeskTriggerFixtures.eventsOf(news).isEmpty(), "the desk did it: dropped");
  }

  @Test
  void anUnknownProjectIsStillDropped() {
    String projectId = "desk-trigger-unknown-" + UUID.randomUUID();

    listener.onFrame(waitClosed(projectId, UUID.randomUUID().toString(), "x", "MATCHED", TURN));

    assertTrue(DeskTriggerFixtures.windows(projectId).isEmpty());
  }

  @Test
  void aRedeliveredFrameIsOneLineInTheWindowAndAfterItClosed() {
    String projectId = project(true);
    EventFrame frame =
        waitClosed(projectId, UUID.randomUUID().toString(), "approve rr-264", "MATCHED", TURN);

    listener.onFrame(frame);
    listener.onFrame(frame);
    assertEquals(1, DeskTriggerFixtures.eventsOf(projectId).size(), "a repeat in the window");

    sweep.sweep();
    listener.onFrame(frame);

    assertEquals(1, DeskTriggerFixtures.windows(projectId).size(), "no second window");
    assertEquals(1, DeskTriggerFixtures.eventsOf(projectId).size(), "a wait wakes the desk once");
  }

  @Test
  void aWaitClosesItsWindowAtOnceAndBringsItsNewsAlong() {
    String projectId = project(true);
    String slug = DeskTriggerFixtures.slugOf(projectId);
    listener.onFrame(
        transitioned(entity(projectId, "TICKET", 7, "Ship it", "VERIFYING", "VERIFIED", "dana")));
    DeskTriggerWindow opened = DeskTriggerFixtures.windows(projectId).get(0);
    assertTrue(opened.closesAt.isAfter(Instant.now().plusSeconds(60)), "news waits its window");

    listener.onFrame(
        waitClosed(projectId, UUID.randomUUID().toString(), "approve rr-264", "MATCHED", TURN));

    DeskTriggerWindow pulled = DeskTriggerFixtures.window(opened.id);
    assertFalse(pulled.closesAt.isAfter(Instant.now()), "the wait closes it now");

    sweep.sweep();

    DeskTriggerWindow closed = DeskTriggerFixtures.window(opened.id);
    assertTrue(closed.state != DeskTriggerWindowState.OPEN, "closed by the next sweep");
    assertTrue(
        closed.message.startsWith("You are the delivery desk of project " + slug + "."),
        "a mixed window keeps the delivery preamble: " + closed.message);
    assertTrue(
        closed.message.contains("\n\nWaits you declared:\n" + TURN + "\n\nMoved to VERIFIED:\n"),
        closed.message);
  }

  @Test
  void aWaitOnlyWindowWithDeliveryOffOpensWithTheWaitPreamble() {
    String projectId = project(false);
    listener.onFrame(
        waitClosed(projectId, UUID.randomUUID().toString(), "approve rr-264", "MATCHED", TURN));

    sweep.sweep();

    DeskTriggerWindow window = DeskTriggerFixtures.windows(projectId).get(0);
    assertTrue(window.state != DeskTriggerWindowState.OPEN, "closed by the next sweep");
    assertTrue(
        window.message.startsWith("A wait you declared has closed. " + DeskBrief.INSTRUCTION),
        window.message);
    assertTrue(window.message.contains(" (desk trigger " + window.id), window.message);
    assertTrue(window.message.endsWith("\n\nWaits you declared:\n" + TURN), window.message);
  }

  @Test
  void aTimedOutWaitWithoutATurnStillNamesItself() {
    String projectId = project(true);

    listener.onFrame(
        waitClosed(projectId, UUID.randomUUID().toString(), "rr-264 to land", "TIMED_OUT", null));

    assertEquals(
        "Your wait \"rr-264 to land\" timed out: ReleaseRequestStateChanged.",
        DeskTriggerFixtures.eventsOf(projectId).get(0).label);
  }

  @Test
  void aFrameWithoutAWaitOrAProjectQualifiesNothing() {
    assertTrue(
        QuarkusTransaction.requiringNew()
            .call(() -> source.qualify(waitClosed(null, "w", "x", "MATCHED", TURN)))
            .isEmpty());
    assertTrue(
        QuarkusTransaction.requiringNew()
            .call(() -> source.qualify(waitClosed("p", null, "x", "MATCHED", TURN)))
            .isEmpty());
  }

  // -------------------------------------------------------------------------------------------

  private static EventFrame probeFrame(String probe, String projectId, String cause) {
    return new EventFrame(
        UUID.randomUUID().toString(),
        CausedByDesk.SIGNATURE + "-" + probe,
        Instant.now(),
        "{\"projectId\":\"" + projectId + "\",\"cause\":\"" + cause + "\"}",
        null,
        null,
        null);
  }

  /** A source whose trigger's cause is whatever the frame names — a desk subject, here. */
  private record CausedByDesk(String id, boolean requestedByDesk) implements DeskTriggerSource {

    /** Suffixed with the probe's id, so each probe reads only its own frames. */
    static final String SIGNATURE = "DeskTriggerCausedByDeskProbe";

    @Override
    public int order() {
      return 50;
    }

    @Override
    public Set<String> signatures() {
      return Set.of(SIGNATURE + "-" + id);
    }

    @Override
    public List<DeskTrigger> qualify(EventFrame frame) {
      try {
        var payload = new com.fasterxml.jackson.databind.ObjectMapper().readTree(frame.payload());
        return List.of(
            new DeskTrigger(
                payload.path("projectId").asText(),
                id + "-1",
                null,
                payload.path("cause").asText(),
                null,
                UUID.fromString(frame.id()),
                frame.occurredAt()));
      } catch (Exception e) {
        throw new IllegalArgumentException(e);
      }
    }

    @Override
    public String render(String projectSlug, List<DeskTriggerEventRow> events) {
      return id;
    }
  }
}
