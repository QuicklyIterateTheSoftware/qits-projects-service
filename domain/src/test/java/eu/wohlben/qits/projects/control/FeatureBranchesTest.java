package eu.wohlben.qits.projects.control;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.control.approval.BranchWorkItems;
import eu.wohlben.qits.projects.control.approval.BranchWorkItems.WorkItem;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The freeze classification of epic qits-1580, over a fake {@link BranchWorkItems}: no CDI, no
 * database. Every branch naming a work item counts, whatever its archetype or ticket type; a branch
 * naming none never does.
 */
class FeatureBranchesTest {

  private static final Map<String, WorkItem> ITEMS =
      Map.of(
          "ticket/qits-1", ticket("qits-1", "BUG"),
          "ticket/qits-2", ticket("qits-2", "MAINTENANCE"),
          "ticket/qits-3", ticket("qits-3", "IMPROVEMENT"),
          "ticket/qits-4", ticket("qits-4", null),
          "epic/qits-5", new WorkItem("qits-5", "p", "IMPLEMENTING", "EPIC", null),
          "task/qits-6", new WorkItem("qits-6", "p", "IMPLEMENTING", "TASK", null));

  private static final BranchWorkItems PORT =
      branch -> Optional.ofNullable(branch == null ? null : ITEMS.get(branch));

  private static WorkItem ticket(String id, String ticketType) {
    return new WorkItem(id, "p", "IMPLEMENTING", "TICKET", ticketType);
  }

  @Test
  void aBugOrMaintenanceTicketCounts() {
    assertTrue(FeatureBranches.isFeature(PORT, "ticket/qits-1"));
    assertTrue(FeatureBranches.isFeature(PORT, "ticket/qits-2"));
  }

  @Test
  void anImprovementOrUntypedTicketCounts() {
    assertTrue(FeatureBranches.isFeature(PORT, "ticket/qits-3"));
    assertTrue(FeatureBranches.isFeature(PORT, "ticket/qits-4"));
  }

  @Test
  void anEpicOrATaskCounts() {
    assertTrue(FeatureBranches.isFeature(PORT, "epic/qits-5"));
    assertTrue(FeatureBranches.isFeature(PORT, "task/qits-6"));
  }

  @Test
  void aBranchNamingNoWorkItemNeverCounts() {
    for (String branch :
        new String[] {
          "main",
          "maintenance/automations/entity-diagram/x",
          "maintenance/java-deps/2026.1024",
          "maintenance/gitlinks/qits-ci-service",
          "release/2026.1024",
          "feature/x",
          "ticket/qits-999",
          null
        }) {
      assertFalse(FeatureBranches.isFeature(PORT, branch), String.valueOf(branch));
    }
  }

  @Test
  void aWorkItemThatCannotBeReadDoesNotCountAndNeverFailsTheAsk() {
    BranchWorkItems failing =
        branch -> {
          throw new IllegalStateException("the epics database is gone");
        };
    assertFalse(FeatureBranches.isFeature(failing, "ticket/qits-3"));
  }
}
