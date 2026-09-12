package eu.wohlben.qits.epics.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import eu.wohlben.qits.epics.entity.Epic;
import eu.wohlben.qits.epics.entity.Feature;
import eu.wohlben.qits.epics.entity.Task;
import eu.wohlben.qits.epics.entity.Ticket;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The branch of each kind of dispatched work, and the refs an agent on it may push. */
class WorkBranchesTest {

  private static Ticket ticket(String slug) {
    Ticket ticket = new Ticket();
    ticket.id = "t-1";
    ticket.slug = slug;
    return ticket;
  }

  private static Epic epic(String slug) {
    Epic epic = new Epic();
    epic.id = "e-1";
    epic.slug = slug;
    return epic;
  }

  private static Feature feature(String id, Epic epic, String slug) {
    Feature feature = new Feature();
    feature.id = id;
    feature.epicId = epic.id;
    feature.slug = slug;
    return feature;
  }

  private static Task task(String id, Feature feature, String slug) {
    Task task = new Task();
    task.id = id;
    task.featureId = feature.id;
    task.slug = slug;
    return task;
  }

  @Test
  void aTicketMayPushItsOwnBranchOnly() {
    WorkBranches.Scope scope = WorkBranches.ticket(ticket("puce-button"));

    assertEquals("ticket/puce-button", scope.branch());
    assertEquals(List.of("refs/heads/ticket/puce-button"), scope.gitRefs());
  }

  /** A title with no letters or digits gets the fallback slug; the rule does not change. */
  @Test
  void aTicketWithTheFallbackSlugFollowsTheSameRule() {
    WorkBranches.Scope scope = WorkBranches.ticket(ticket("ticket-1a2b3c4d"));

    assertEquals("ticket/ticket-1a2b3c4d", scope.branch());
    assertEquals(List.of("refs/heads/ticket/ticket-1a2b3c4d"), scope.gitRefs());
  }

  @Test
  void aTaskMayPushItsOwnBranchOnly() {
    Epic epic = epic("planning");
    Feature feature = feature("f-1", epic, "slugs");

    WorkBranches.Scope scope = WorkBranches.task(epic, feature, task("k-1", feature, "mint"));

    assertEquals("task/planning/slugs/mint", scope.branch());
    assertEquals(List.of("refs/heads/task/planning/slugs/mint"), scope.gitRefs());
  }

  @Test
  void anEpicMayPushItsBranchAndEveryFeatureAndTaskBranch() {
    Epic epic = epic("planning");
    Feature lifecycle = feature("f-1", epic, "lifecycle");
    Feature slugs = feature("f-2", epic, "slugs");
    Map<String, List<Task>> tasks =
        Map.of(
            "f-1", List.of(),
            "f-2", List.of(task("k-1", slugs, "mint"), task("k-2", slugs, "suffix")));

    WorkBranches.Scope scope =
        WorkBranches.epic(epic, List.of(lifecycle, slugs), f -> tasks.get(f.id));

    assertEquals("epic/planning", scope.branch());
    assertEquals(
        List.of(
            "refs/heads/epic/planning",
            "refs/heads/feature/planning/lifecycle",
            "refs/heads/feature/planning/slugs",
            "refs/heads/task/planning/slugs/mint",
            "refs/heads/task/planning/slugs/suffix"),
        scope.gitRefs());
  }

  @Test
  void anEpicWithoutFeaturesMayPushItsOwnBranchOnly() {
    WorkBranches.Scope scope = WorkBranches.epic(epic("bare"), List.of(), f -> List.of());

    assertEquals("epic/bare", scope.branch());
    assertEquals(List.of("refs/heads/epic/bare"), scope.gitRefs());
  }

  /** A feature or task from other work would put that work's branch on this list. */
  @Test
  void aRowFromOtherWorkIsRefused() {
    Epic epic = epic("planning");
    Epic other = epic("other");
    other.id = "e-2";
    Feature foreign = feature("f-9", other, "elsewhere");
    Feature own = feature("f-1", epic, "own");

    assertThrows(
        IllegalArgumentException.class,
        () -> WorkBranches.epic(epic, List.of(foreign), f -> List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> WorkBranches.task(epic, own, task("k-9", foreign, "stray")));
  }
}
