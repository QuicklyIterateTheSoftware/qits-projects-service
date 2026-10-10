package eu.wohlben.qits.entities.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.WorkEntity;
import org.junit.jupiter.api.Test;

/** The wrapper branch of each kind of dispatched work (qits-1152): prefix plus qualified id. */
class WorkBranchesTest {

  private static WorkEntity row(String id, Archetype archetype) {
    WorkEntity entity = new WorkEntity();
    entity.id = id;
    entity.archetype = archetype;
    entity.slug = "a-slug-nobody-reads";
    return entity;
  }

  @Test
  void aTicketStandsOnTicketSlashItsQualifiedId() {
    assertEquals("ticket/qits-1002", WorkBranches.of(row("t-1", Archetype.TICKET), "qits-1002"));
  }

  @Test
  void anEpicStandsOnEpicSlashItsQualifiedId() {
    assertEquals("epic/qits-1001", WorkBranches.of(row("e-1", Archetype.EPIC), "qits-1001"));
  }

  /** A campaign has no branch (qits-411). */
  @Test
  void aCampaignHasNoBranch() {
    IllegalArgumentException refused =
        assertThrows(
            IllegalArgumentException.class,
            () -> WorkBranches.of(row("c-1", Archetype.CAMPAIGN), "qits-7"));
    assertEquals(
        "campaign c-1 has no branch — a campaign orders work, it is not worked on",
        refused.getMessage());
  }

  /** No feature/* or task/* branches any more (D9): the epic's agent works on them. */
  @Test
  void aFeatureAndATaskHaveNoBranchOfTheirOwn() {
    assertThrows(
        IllegalArgumentException.class,
        () -> WorkBranches.of(row("f-1", Archetype.FEATURE), "qits-8"));
    assertThrows(
        IllegalArgumentException.class, () -> WorkBranches.of(row("k-1", Archetype.TASK), "qits-9"));
  }

  @Test
  void noQualifiedIdNamesNoBranch() {
    assertThrows(
        IllegalArgumentException.class, () -> WorkBranches.of(row("t-1", Archetype.TICKET), null));
    assertThrows(
        IllegalArgumentException.class, () -> WorkBranches.of(row("t-1", Archetype.TICKET), " "));
  }

  @Test
  void aRefIsTheBranchUnderHeads() {
    assertEquals("refs/heads/ticket/qits-1002", WorkBranches.ref("ticket/qits-1002"));
  }
}
