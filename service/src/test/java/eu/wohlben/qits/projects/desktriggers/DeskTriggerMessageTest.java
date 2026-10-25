package eu.wohlben.qits.projects.desktriggers;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.projects.entity.DeskTriggerWindow;
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

  private static final String PREAMBLE =
      "You are the delivery desk of project qits. Read /tmp/qits-delivery-brief.md and follow it,"
          + " then act on what happened below. (desk trigger 6f1c4a52-0000-4000-8000-000000000001,"
          + " 2026-10-25T10:00:00Z to 2026-10-25T10:15:00Z)";

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
        DeskTriggerMessage.render("qits", window(), events));
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
        DeskTriggerMessage.render("qits", window(), events));
  }

  @Test
  void aLineWithNoLabelHasNoBrackets() {
    Map<DeskTriggerSource, List<DeskTriggerEventRow>> events =
        Map.of(new WorkVerifiedSource(), List.of(row("work-verified", "qits-7", null, "dana")));

    assertEquals(
        PREAMBLE + "\n\nMoved to VERIFIED:\n- qits-7",
        DeskTriggerMessage.render("qits", window(), events));
  }
}
