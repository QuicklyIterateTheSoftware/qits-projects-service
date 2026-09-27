package eu.wohlben.qits.entities.campaign;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@link Conditions#satisfied}: the monotone DNF, and nothing else. Plain JUnit. */
class ConditionsTest {

  private static CampaignCriterion latched() {
    CampaignCriterion criterion = new CampaignCriterion();
    criterion.satisfiedAt = Instant.parse("2026-09-27T10:00:00Z");
    return criterion;
  }

  private static CampaignCriterion open() {
    return new CampaignCriterion();
  }

  @Test
  void noGroupsIsSatisfied() {
    assertTrue(Conditions.satisfied(List.of()));
    assertTrue(Conditions.satisfied(null));
  }

  @Test
  void oneGroupHoldsOnlyWhenEveryCriterionHasLatched() {
    assertTrue(Conditions.satisfied(List.of(List.of(latched()))));
    assertTrue(Conditions.satisfied(List.of(List.of(latched(), latched()))));
    assertFalse(Conditions.satisfied(List.of(List.of(latched(), open()))));
    assertFalse(Conditions.satisfied(List.of(List.of(open()))));
  }

  @Test
  void anyGroupThatHoldsIsEnough() {
    assertTrue(
        Conditions.satisfied(List.of(List.of(open(), latched()), List.of(latched(), latched()))));
    assertFalse(
        Conditions.satisfied(List.of(List.of(open(), latched()), List.of(latched(), open()))));
  }
}
