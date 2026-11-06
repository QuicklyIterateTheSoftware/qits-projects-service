package eu.wohlben.qits.entities.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * When a need is met (qits-1903): {@link NeedRule#reached}, the whole table of the walk and
 * DROPPED.
 *
 * <p><b>Plain JUnit and no Quarkus application</b> — the rule is a pure function of two words.
 */
class NeedRuleTest {

  private static final List<String> WALK =
      List.of(
          "REPORTED",
          "REFINED",
          "READY_FOR_DEV",
          "IMPLEMENTING",
          "IMPLEMENTED",
          "VERIFYING",
          "VERIFIED",
          "DONE");

  @Test
  void aStatusOnTheWalkReachesItselfAndEverythingBeforeItAndNothingAfter() {
    for (int at = 0; at < WALK.size(); at++) {
      for (int want = 0; want < WALK.size(); want++) {
        assertEquals(
            at >= want,
            NeedRule.reached(WALK.get(at), WALK.get(want)),
            WALK.get(at) + " reaching " + WALK.get(want));
      }
    }
  }

  @Test
  void droppedIsPastNothingAndOnlyADroppedTargetIsAtADroppedNeed() {
    for (String wanted : WALK) {
      assertFalse(NeedRule.reached("DROPPED", wanted), "DROPPED reaching " + wanted);
      assertFalse(NeedRule.reached(wanted, "DROPPED"), wanted + " reaching DROPPED");
    }
    assertTrue(NeedRule.reached("DROPPED", "DROPPED"));
  }

  @Test
  void aWordOffTheWalkOrANullReachesNothing() {
    assertFalse(NeedRule.reached(null, "REFINED"));
    assertFalse(NeedRule.reached("REFINED", null));
    assertFalse(NeedRule.reached("RESOLVED", "REPORTED"));
    assertFalse(NeedRule.reached("DONE", "OPEN"));
    assertFalse(NeedRule.reached("NOT_A_STATUS", "REPORTED"));
  }

  @Test
  void theNeedableWordsAreTheWalkThenDropped() {
    assertEquals(
        List.of(
            "REPORTED",
            "REFINED",
            "READY_FOR_DEV",
            "IMPLEMENTING",
            "IMPLEMENTED",
            "VERIFYING",
            "VERIFIED",
            "DONE",
            "DROPPED"),
        NeedRule.needable());
    assertTrue(NeedRule.isNeedable("DROPPED"));
    assertFalse(NeedRule.isNeedable("OPEN"));
    assertFalse(NeedRule.isNeedable("verified"));
    assertFalse(NeedRule.isNeedable(null));
  }

}
