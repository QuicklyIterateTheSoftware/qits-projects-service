package eu.wohlben.qits.entities.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.WorkEntity;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The branch of each kind of dispatched work, and the refs an agent on it may push.
 *
 * <p><b>The fixtures are merged {@link WorkEntity} rows now and not one asserted value moved.</b>
 * That is the whole claim of this file after the merge: the four old shapes are gone, a descendant's
 * parent arrives as {@link Nested#parentId} instead of as a column, and every branch name and every
 * ref below is character for character what it was. A branch name that changed would orphan work an
 * agent is standing on.
 */
class WorkBranchesTest {

  private static WorkEntity row(String id, Archetype archetype, String slug) {
    WorkEntity entity = new WorkEntity();
    entity.id = id;
    entity.archetype = archetype;
    entity.slug = slug;
    return entity;
  }

  private static WorkEntity ticket(String slug) {
    return row("t-1", Archetype.TICKET, slug);
  }

  private static WorkEntity epic(String slug) {
    return row("e-1", Archetype.EPIC, slug);
  }

  private static Nested feature(String id, WorkEntity epic, String slug) {
    return new Nested(row(id, Archetype.FEATURE, slug), epic.id);
  }

  private static Nested task(String id, Nested feature, String slug) {
    return new Nested(row(id, Archetype.TASK, slug), feature.entity().id);
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
    WorkEntity epic = epic("planning");
    Nested feature = feature("f-1", epic, "slugs");

    WorkBranches.Scope scope = WorkBranches.task(epic, feature, task("k-1", feature, "mint"));

    assertEquals("task/planning/slugs/mint", scope.branch());
    assertEquals(List.of("refs/heads/task/planning/slugs/mint"), scope.gitRefs());
  }

  @Test
  void anEpicMayPushItsBranchAndEveryFeatureAndTaskBranch() {
    WorkEntity epic = epic("planning");
    Nested lifecycle = feature("f-1", epic, "lifecycle");
    Nested slugs = feature("f-2", epic, "slugs");
    Map<String, List<Nested>> tasks =
        Map.of(
            "f-1", List.of(),
            "f-2", List.of(task("k-1", slugs, "mint"), task("k-2", slugs, "suffix")));

    WorkBranches.Scope scope =
        WorkBranches.epic(epic, List.of(lifecycle, slugs), f -> tasks.get(f.entity().id));

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
    WorkEntity epic = epic("planning");
    WorkEntity other = epic("other");
    other.id = "e-2";
    Nested foreign = feature("f-9", other, "elsewhere");
    Nested own = feature("f-1", epic, "own");

    assertThrows(
        IllegalArgumentException.class,
        () -> WorkBranches.epic(epic, List.of(foreign), f -> List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> WorkBranches.task(epic, own, task("k-9", foreign, "stray")));
  }
}
