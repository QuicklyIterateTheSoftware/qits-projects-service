package eu.wohlben.qits.projects.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * The label a {@code maintenance/*} source is stored with (qits-1673): its head commit's subject,
 * less one leading conventional prefix whose scope is a list of work-item ids. Pure, no CDI.
 */
class ReleaseRequestItemsLabelTest {

  @Test
  void aScopeListingWorkItemsIsStripped() {
    assertEquals(
        "bump(qits-112): 1 dependencies",
        ReleaseRequestItems.maintenanceLabelOf(
            "chore(qits-112, qits-142, qits-1708): bump(qits-112): 1 dependencies"));
  }

  @Test
  void aSingleWorkItemScopeIsStrippedToo() {
    assertEquals(
        "bump(qits-112): 6 dependencies",
        ReleaseRequestItems.maintenanceLabelOf("chore(qits-1708): bump(qits-112): 6 dependencies"));
  }

  @Test
  void onlyTheFirstLineCountsAndOnlyOnePrefixGoes() {
    assertEquals(
        "bump(qits-1): x",
        ReleaseRequestItems.maintenanceLabelOf(
            "chore(qits-2,qits-3): bump(qits-1): x\n\nchore(qits-9): body line"));
  }

  @Test
  void aScopeThatIsNotAListOfWorkItemsIsKept() {
    assertEquals(
        "bump(dependencies): 2 dependencies",
        ReleaseRequestItems.maintenanceLabelOf("bump(dependencies): 2 dependencies"));
    assertEquals(
        "chore(qits-1, deps): x", ReleaseRequestItems.maintenanceLabelOf("chore(qits-1, deps): x"));
    assertEquals("chore: tidy", ReleaseRequestItems.maintenanceLabelOf("chore: tidy"));
  }

  @Test
  void nothingIsStrippedWhenNothingWouldRemain() {
    assertEquals("chore(qits-1):", ReleaseRequestItems.maintenanceLabelOf("chore(qits-1):"));
    assertEquals("chore(qits-1):", ReleaseRequestItems.maintenanceLabelOf("chore(qits-1):   \n"));
  }

  @Test
  void aMessageWithNoSubjectHasNoLabel() {
    assertNull(ReleaseRequestItems.maintenanceLabelOf(null));
    assertNull(ReleaseRequestItems.maintenanceLabelOf("  \n "));
  }
}
