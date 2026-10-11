package eu.wohlben.qits.projects.control;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** How several kinds' states read together — {@link AutomationRefresh#stateOf}. */
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
