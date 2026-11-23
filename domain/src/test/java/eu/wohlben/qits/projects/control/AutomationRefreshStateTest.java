package eu.wohlben.qits.projects.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * How several kinds' states read together — {@link AutomationRefresh#stateOf} — and when a status
 * read may stand for the note it would replace — {@link AutomationRefresh#covers}.
 */
class AutomationRefreshStateTest {

  @Test
  void deferredHoldsNothingLikeNotApplicable() {
    assertEquals(AutomationLedger.State.FRESH, state("FRESH", "DEFERRED"));
    assertEquals(AutomationLedger.State.FRESH, state("DEFERRED", "NOT_APPLICABLE"));
  }

  @Test
  void deferredDoesNotHideAKindThatMovesOrFails() {
    assertEquals(AutomationLedger.State.PENDING, state("DEFERRED", "RUNNING"));
    assertEquals(AutomationLedger.State.FAILED, state("DEFERRED", "FAILED"));
    assertEquals(AutomationLedger.State.UNKNOWN, state("DEFERRED", "PONDERING"));
  }

  /** qits-2571: a far-side UNKNOWN (a kind still settling) makes an UNKNOWN note. */
  @Test
  void anUnsettledKindBesideARunningOneIsUnknown() {
    assertEquals(AutomationLedger.State.UNKNOWN, state("RUNNING", "UNKNOWN"));
    assertEquals(AutomationLedger.State.FAILED, state("UNKNOWN", "FAILED"));
  }

  /** qits-2571: the status door never lists a NOT_APPLICABLE kind, so its absence is no gap. */
  @Test
  void aReadCoversANoteWithoutItsNotApplicableKinds() {
    AutomationLedger.Note held = note("screenshots", "RUNNING", "diagram", "NOT_APPLICABLE");
    assertTrue(AutomationRefresh.covers(read("screenshots", "RUNNING"), held));
    assertTrue(
        AutomationRefresh.covers(read("screenshots", "FRESH", "diagram", "NOT_APPLICABLE"), held));
  }

  /** A DEFERRED kind is on the read, and a kind fresh by plan has no row: both stay required. */
  @Test
  void aReadMissingAnyOtherKindDoesNotCover() {
    assertFalse(
        AutomationRefresh.covers(
            read("screenshots", "RUNNING"), note("screenshots", "RUNNING", "diagram", "DEFERRED")));
    assertFalse(
        AutomationRefresh.covers(
            read("screenshots", "RUNNING"), note("screenshots", "RUNNING", "diagram", "FRESH")));
  }

  @Test
  void anEmptyReadNeverCovers() {
    assertFalse(AutomationRefresh.covers(read(), note("diagram", "NOT_APPLICABLE")));
    assertFalse(AutomationRefresh.covers(read(), note()));
  }

  /** Kind/state pairs as a held note. */
  private static AutomationLedger.Note note(String... kindsAndStates) {
    List<AutomationLedger.Automation> entries = new ArrayList<>();
    for (int i = 0; i < kindsAndStates.length; i += 2) {
      entries.add(
          new AutomationLedger.Automation(
              kindsAndStates[i], "Kind", kindsAndStates[i + 1], null, List.of(), null, null, null,
              null));
    }
    AutomationLedger.State state = AutomationRefresh.stateOf(entries);
    return new AutomationLedger.Note("fold", state, entries, null, null);
  }

  /** Kind/state pairs as a status read. */
  private static ReleaseRequestAutomations.Answer read(String... kindsAndStates) {
    List<ReleaseRequestAutomations.Automation> entries = new ArrayList<>();
    for (int i = 0; i < kindsAndStates.length; i += 2) {
      entries.add(
          new ReleaseRequestAutomations.Automation(
              kindsAndStates[i], "Kind", kindsAndStates[i + 1], null, null, List.of(), null, null,
              null, null));
    }
    return new ReleaseRequestAutomations.Answer("request", "fold", entries);
  }

  private static AutomationLedger.State state(String... states) {
    List<AutomationLedger.Automation> entries =
        Arrays.stream(states)
            .map(
                state ->
                    new AutomationLedger.Automation(
                        "kind-" + state, "Kind", state, null, List.of(), null, null, null, null))
            .toList();
    return AutomationRefresh.stateOf(entries);
  }
}
