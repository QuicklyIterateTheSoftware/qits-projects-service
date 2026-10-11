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
        java.util.List.of("refs/heads/ticket/qits-1002", "refs/heads/ticket/qits-1002-*"),
        WorkBranches.agentRefs("ticket/qits-1002"));
    assertTrue(WorkBranches.isAgentBranch("ticket/qits-1002", "ticket/qits-1002"));
    assertTrue(WorkBranches.isAgentBranch("ticket/qits-1002", "ticket/qits-1002-fix-totals"));
    assertFalse(WorkBranches.isAgentBranch("ticket/qits-1002", "ticket/qits-1002-"));
    assertFalse(WorkBranches.isAgentBranch("ticket/qits-1002", "main"));
    assertFalse(WorkBranches.isAgentBranch("ticket/qits-1002", "ticket/qits-1003-x"));
  }

  /**
   * Inside one project every qualified id is {@code <slug>-<number>}, so one agent's scope never
   * reaches another work item's branch there (D28). Across projects the repository check does that.
   */
  @Test
  void insideAProjectOneAgentsScopeNeverCoversAnotherWorkItemsBranch() {
    String first = WorkBranches.of(row("t-1", Archetype.TICKET), "qits-1");
    String second = WorkBranches.of(row("t-2", Archetype.TICKET), "qits-15");
    String epic = WorkBranches.of(row("e-1", Archetype.EPIC), "qits-1");

    assertFalse(WorkBranches.isAgentBranch(first, second));
    assertFalse(WorkBranches.isAgentBranch(first, second + "-fix"));
    assertFalse(WorkBranches.isAgentBranch(second, first));
    assertFalse(WorkBranches.isAgentBranch(first, epic + "-fix"));
  }

  @Test
  void aRefIsTheBranchUnderHeads() {
    assertEquals("refs/heads/ticket/qits-1002", WorkBranches.ref("ticket/qits-1002"));
  }
}
