package eu.wohlben.qits.entities.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
  void anAgentMayPushItsWrapperBranchAndTheBranchesItNamesAfterIt() {
    assertEquals(
        java.util.List.of("refs/heads/ticket/qits-1002", "refs/heads/ticket/qits-1002_*"),
        WorkBranches.agentRefs("ticket/qits-1002"));
    assertTrue(WorkBranches.isAgentBranch("ticket/qits-1002", "ticket/qits-1002"));
    assertTrue(WorkBranches.isAgentBranch("ticket/qits-1002", "ticket/qits-1002_fix-totals"));
    assertFalse(WorkBranches.isAgentBranch("ticket/qits-1002", "ticket/qits-1002_"));
    assertFalse(WorkBranches.isAgentBranch("ticket/qits-1002", "main"));
    assertFalse(WorkBranches.isAgentBranch("ticket/qits-1002", "ticket/qits-1003_x"));
  }

  /**
   * A project slug may end in {@code -<digits>}: project {@code a}'s ticket 1 is {@code a-1} and
   * project {@code a-1}'s ticket 5 is {@code a-1-5}. The first agent's scope must not reach the
   * second's wrapper branch, which a {@code -} separator would.
   */
  @Test
  void oneAgentsScopeNeverCoversAnotherWorkItemsBranch() {
    String first = WorkBranches.of(row("t-1", Archetype.TICKET), "a-1");
    String second = WorkBranches.of(row("t-2", Archetype.TICKET), "a-1-5");

    assertFalse(WorkBranches.isAgentBranch(first, second));
    assertFalse(WorkBranches.isAgentBranch(first, second + "_fix"));
    assertFalse(WorkBranches.isAgentBranch(second, first));
  }

  @Test
  void aRefIsTheBranchUnderHeads() {
    assertEquals("refs/heads/ticket/qits-1002", WorkBranches.ref("ticket/qits-1002"));
  }
}
