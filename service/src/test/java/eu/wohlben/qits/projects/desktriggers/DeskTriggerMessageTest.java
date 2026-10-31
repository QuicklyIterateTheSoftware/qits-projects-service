package eu.wohlben.qits.projects.desktriggers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.desktriggers.VerifiedSlotHolders.Holder;
import eu.wohlben.qits.projects.desktriggers.VerifiedSlotHolders.OpenRequest;
import eu.wohlben.qits.projects.desktriggers.VerifiedSlotHolders.Reading;
import eu.wohlben.qits.projects.entity.DeskTriggerWindow;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projectsdaemon.protocol.DeskBrief;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * <b>The message format, byte for byte</b> (qits-1610): the preamble, then each source's section in
 * order with one blank line between, absent sections left out and nothing after the last. Plain
 * JUnit: the renderer and the two sources' {@code render} are pure.
 */
class DeskTriggerMessageTest {

  private static final UUID WINDOW = UUID.fromString("6f1c4a52-0000-4000-8000-000000000001");

  private static DeskTriggerWindow window() {
    DeskTriggerWindow window = new DeskTriggerWindow();
    window.id = WINDOW;
    window.openedAt = Instant.parse("2026-10-25T10:00:00.123456Z");
    window.closesAt = Instant.parse("2026-10-25T10:15:00.123456Z");
    return window;
  }

  private static DeskTriggerEventRow row(String source, String ref, String label, String cause) {
    Instant at = Instant.parse("2026-10-25T10:01:00Z");
    return new DeskTriggerEventRow(source, ref, null, label, cause, UUID.randomUUID(), at, at);
  }

  /** The desk variant's guide-hash the window closed at. */
  private static final String HASH = "0123456789abcdef";

  private static final String BOUNDS =
      " (desk trigger 6f1c4a52-0000-4000-8000-000000000001,"
          + " 2026-10-25T10:00:00Z to 2026-10-25T10:15:00Z)";

  /** qits-1734: the sentence is DeskBrief's; the test composes it rather than spelling it. */
  private static final String PREAMBLE =
      "You are the delivery desk of project qits. "
          + DeskBrief.pointer(HASH)
          + " Then act on what happened below."
          + BOUNDS;

  /** qits-1734: both preambles point at the guide and state the hash the window closed at. */
  @Test
  void everyPreamblePointsAtTheGuideAndStatesTheHash() {
    String news =
        DeskTriggerMessage.render(
            "qits",
            HASH,
            window(),
            Map.of(new WorkVerifiedSource(), List.of(row("work-verified", "qits-7", null, "d"))));
    assertTrue(news.startsWith("You are the delivery desk of project qits. "), news);
    String waits =
        DeskTriggerMessage.render(
            "qits",
            HASH,
            window(),
            Map.of(new WaitClosedSource(), List.of(row("wait", "w-1", "Your wait timed out.", "x"))));
    assertTrue(waits.startsWith("A wait you declared has closed. "), waits);
    for (String message : List.of(news, waits)) {
      String preamble = message.substring(0, message.indexOf("(desk trigger"));
      assertTrue(preamble.contains(DeskBrief.INSTRUCTION), preamble);
      assertTrue(preamble.contains(HASH), preamble);
      assertTrue(preamble.contains(DeskBrief.hashNote(HASH)), preamble);
    }
  }

  @Test
  void bothSectionsInSourceOrderWhateverTheMapOrder() {
    Map<DeskTriggerSource, List<DeskTriggerEventRow>> events = new LinkedHashMap<>();
    events.put(
        new ApprovalNeededSource(),
        List.of(
            row(
                "approval-needed",
                "qits-qits-rr-201",
                "Release qits-1597 wrapper; waits on manual-review: configured by manual-review",
                "fold 412911eb")));
    events.put(
        new WorkVerifiedSource(),
        List.of(
            row("work-verified", "qits-1580", "Title of 1580", "dana"),
            row("work-verified", "qits-1597", "Title of 1597", "dana")));

    assertEquals(
        PREAMBLE
            + "\n\nMoved to VERIFIED:\n"
            + "- qits-1580 (Title of 1580)\n"
            + "- qits-1597 (Title of 1597)\n"
            + "\n"
            + "Needs your approval:\n"
            + "- qits-qits-rr-201 (Release qits-1597 wrapper; waits on manual-review: configured by"
            + " manual-review), fold 412911eb",
        DeskTriggerMessage.render("qits", HASH, window(), events));
  }

  @Test
  void aSourceWithNothingInTheWindowHasNoSection() {
    Map<DeskTriggerSource, List<DeskTriggerEventRow>> events = new LinkedHashMap<>();
    events.put(new WorkVerifiedSource(), List.of());
    events.put(
        new ApprovalNeededSource(),
        List.of(row("approval-needed", "p-rr-1", "waits on no-changes", "fold 0123abcd")));

    assertEquals(
        PREAMBLE
            + "\n\nNeeds your approval:\n- p-rr-1 (waits on no-changes), fold 0123abcd",
        DeskTriggerMessage.render("qits", HASH, window(), events));
  }

  @Test
  void aLineWithNoLabelHasNoBrackets() {
    Map<DeskTriggerSource, List<DeskTriggerEventRow>> events =
        Map.of(new WorkVerifiedSource(), List.of(row("work-verified", "qits-7", null, "dana")));

    assertEquals(
        PREAMBLE + "\n\nMoved to VERIFIED:\n- qits-7",
        DeskTriggerMessage.render("qits", HASH, window(), events));
  }

  private static final String TURN_A =
      "Your wait \"approve rr-264\" matched: ReleaseRequestStateChanged at 2026-10-25T10:01:00Z"
          + " (event e1).\nstate: FINALIZED";

  private static final String TURN_B = "Your wait \"qits-7 verified\" timed out.";

  /** qits-1618: a window of waits only opens with the wait preamble and quotes each turn. */
  @Test
  void aWindowOfWaitsOnlyHasTheWaitPreambleAndEveryTurnVerbatim() {
    Map<DeskTriggerSource, List<DeskTriggerEventRow>> events = new LinkedHashMap<>();
    events.put(new WorkVerifiedSource(), List.of());
    events.put(
        new WaitClosedSource(),
        List.of(row("wait", "w-1", TURN_A, "x"), row("wait", "w-2", TURN_B, "y")));

    assertEquals(
        "A wait you declared has closed. "
            + DeskBrief.pointer(HASH)
            + BOUNDS
            + "\n\nWaits you declared:\n"
            + TURN_A
            + "\n\n"
            + TURN_B,
        DeskTriggerMessage.render("qits", HASH, window(), events));
  }

  /** qits-1618: a window mixing waits and news keeps the delivery preamble; waits come first. */
  @Test
  void aWindowMixingWaitsAndNewsKeepsTheDeliveryPreamble() {
    Map<DeskTriggerSource, List<DeskTriggerEventRow>> events = new LinkedHashMap<>();
    events.put(new WorkVerifiedSource(), List.of(row("work-verified", "qits-7", "T", "dana")));
    events.put(new WaitClosedSource(), List.of(row("wait", "w-1", TURN_B, "x")));

    assertEquals(
        PREAMBLE + "\n\nWaits you declared:\n" + TURN_B + "\n\nMoved to VERIFIED:\n- qits-7 (T)",
        DeskTriggerMessage.render("qits", HASH, window(), events));
  }

  /** qits-1756: idle agents have their own section, between the stalled requests and a slot. */
  @Test
  void idleAgentsHaveTheirSectionBetweenStalledRequestsAndAnOpenedSlot() {
    Map<DeskTriggerSource, List<DeskTriggerEventRow>> events = new LinkedHashMap<>();
    events.put(
        new CapacityOpenedSource(),
        List.of(row("capacity-opened", "qits-9", "its agent is done; running 1 of 2", "agent a")));
    events.put(
        new AgentIdleSource(),
        List.of(
            row(
                "agent-idle",
                "qits-1625",
                "Some title; IMPLEMENTING; idle 2h 19m since 2026-10-30T07:03Z; waits on:"
                    + " qits-1625 live in qits-projects-service (JOINED, expires"
                    + " 2026-10-30T12:54Z)",
                "agent 6f1c2a8e")));
    events.put(
        new RequestStalledSource(),
        List.of(row("request-stalled", "p-rr-1", "READY for 2h 1m, not RELEASED", "fold a")));

    assertEquals(
        PREAMBLE
            + "\n\nRelease requests that stopped moving:\n- p-rr-1 (READY for 2h 1m, not RELEASED)"
            + "\n\nAgents idle for 2h or more:\n"
            + "- qits-1625 (Some title; IMPLEMENTING; idle 2h 19m since 2026-10-30T07:03Z;"
            + " waits on: qits-1625 live in qits-projects-service (JOINED, expires 2026-10-30T12:54Z))"
            + "\n\nA slot opened; dispatch the next item:\n"
            + "- qits-9 (its agent is done; running 1 of 2)",
        DeskTriggerMessage.render("qits", HASH, window(), events));
  }

  private static final String SLOTS =
      "Slots: running 5 of 5, 2 VERIFIED waiting to ship\n"
          + "- qits-1774 (VERIFIED 1h 5m; qits-qits-rr-264 PENDING)\n"
          + "- qits-1769 (VERIFIED 43m; no open release request)";

  /** qits-1778: the Slots section is last, one blank line after the last source's section. */
  @Test
  void theSlotsSectionIsLastAfterTheSourceSections() {
    Map<DeskTriggerSource, List<DeskTriggerEventRow>> events = new LinkedHashMap<>();
    events.put(
        new RequestStalledSource(),
        List.of(row("request-stalled", "p-rr-1", "READY for 2h 1m, not RELEASED", "fold a")));
    events.put(new WorkVerifiedSource(), List.of(row("work-verified", "qits-7", "T", "dana")));

    assertEquals(
        PREAMBLE
            + "\n\nMoved to VERIFIED:\n- qits-7 (T)"
            + "\n\nRelease requests that stopped moving:\n- p-rr-1 (READY for 2h 1m, not RELEASED)"
            + "\n\n"
            + SLOTS,
        DeskTriggerMessage.render("qits", HASH, window(), events, SLOTS));
  }

  /** qits-1778: no reading, no section; the message is exactly the one without the argument. */
  @Test
  void noSlotsLeavesTheSectionOut() {
    Map<DeskTriggerSource, List<DeskTriggerEventRow>> events =
        Map.of(new WorkVerifiedSource(), List.of(row("work-verified", "qits-7", null, "dana")));

    String message = DeskTriggerMessage.render("qits", HASH, window(), events, null);

    assertEquals(PREAMBLE + "\n\nMoved to VERIFIED:\n- qits-7", message);
    assertEquals(DeskTriggerMessage.render("qits", HASH, window(), events), message);
  }

  /** qits-1778: a window of the desk's own waits is not delivery news and shows no slots. */
  @Test
  void aWindowOfWaitsOnlyHasNoSlotsSection() {
    Map<DeskTriggerSource, List<DeskTriggerEventRow>> events =
        Map.of(new WaitClosedSource(), List.of(row("wait", "w-1", TURN_B, "x")));

    String message = DeskTriggerMessage.render("qits", HASH, window(), events, SLOTS);

    assertTrue(message.startsWith("A wait you declared has closed. "), message);
    assertFalse(message.contains("Slots:"), message);
    assertTrue(message.endsWith(TURN_B), message);
  }

  /** qits-1778: with nothing VERIFIED the heading is the whole section, with no VERIFIED clause. */
  @Test
  void theSectionHasNoVerifiedClauseWhenNothingIsVerified() {
    assertEquals(
        "Slots: running 3 of 5",
        VerifiedSlotHolders.section(new Reading(3, List.of()), 5, Instant.now()));
  }

  /** qits-1778: one line per VERIFIED item as handed in, its open requests comma-joined. */
  @Test
  void theSectionNamesEachVerifiedItemAndItsOpenRequests() {
    Instant now = Instant.parse("2026-10-31T14:09:00Z");
    Reading reading =
        new Reading(
            5,
            List.of(
                new Holder(
                    "i-1774",
                    "qits-1774",
                    "ticket/qits-1774",
                    now.minusSeconds(65 * 60),
                    List.of(
                        new OpenRequest(
                            "qits-projects-service-rr-9", ReleaseRequest.State.REJECTED, false),
                        new OpenRequest("qits-qits-rr-264", ReleaseRequest.State.PENDING, true))),
                new Holder(
                    "i-1769",
                    "qits-1769",
                    "ticket/qits-1769",
                    now.minusSeconds(43 * 60 + 30),
                    List.of())));

    assertEquals(
        "Slots: running 5 of 5, 2 VERIFIED waiting to ship\n"
            + "- qits-1774 (VERIFIED 1h 5m; qits-projects-service-rr-9 REJECTED,"
            + " qits-qits-rr-264 PENDING)\n"
            + "- qits-1769 (VERIFIED 43m; no open release request)",
        VerifiedSlotHolders.section(reading, 5, now));
  }
}
