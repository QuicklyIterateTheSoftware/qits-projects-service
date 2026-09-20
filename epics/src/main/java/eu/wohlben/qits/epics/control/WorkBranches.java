package eu.wohlben.qits.epics.control;

import eu.wohlben.qits.epics.entity.WorkEntity;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * The branch that planned work happens on, and the Git refs an agent on that branch may push.
 *
 * <p>This is the one place that computes both, so a ref never drifts from its branch. The branch
 * names are the ones in AGENTS.md, "Branch naming":
 *
 * <pre>
 *   ticket/&lt;ticket&gt;
 *   epic/&lt;epic&gt;
 *   feature/&lt;epic&gt;/&lt;feature&gt;
 *   task/&lt;epic&gt;/&lt;feature&gt;/&lt;task&gt;
 * </pre>
 *
 * <p>Each segment is the row's {@code slug}. A slug never changes after create, so the refs of a
 * row never change either.
 *
 * <p>The refs are exact refs ({@code refs/heads/<branch>}), never {@code /*} patterns: qits-workspaces
 * removes one exact ref from an epic's list when a sub-workspace takes that branch, and it cannot do
 * that to a pattern. See {@code principal-bound-git-refs-plan.md}, contracts C1 and C4.
 *
 * <p><b>It reads merged {@link WorkEntity} rows now, and not one branch name moved.</b> It used to
 * take the four old shapes; a feature and a task carry their parent <em>beside</em> the row as a
 * {@link Nested} rather than on it, because the parent is an {@code entity_membership} row. That is
 * the only change: the segments are still each row's {@code slug}, the order is still the epic then
 * each feature followed by its tasks, and {@code WorkBranchesTest} asserts every literal it always
 * did — which is what says the branch names an agent is given did not move by one byte.
 *
 * <p>This is still a <b>pure derivation</b> over slugs and parent ids: no repository, no query, no
 * table. It is handed what the caller has already read.
 */
public final class WorkBranches {

  /** Every ref an agent may push is a branch. */
  public static final String HEADS = "refs/heads/";

  private WorkBranches() {}

  /**
   * A branch and the Git refs an agent that works on it may push.
   *
   * @param branch the branch the workspace stands on
   * @param gitRefs exact refs, each {@code refs/heads/…}, with no duplicates, in a stable order
   */
  public record Scope(String branch, List<String> gitRefs) {
    public Scope {
      gitRefs = List.copyOf(gitRefs);
    }
  }

  /**
   * A ticket's agent may push its own branch and nothing else.
   *
   * @param ticket a {@code TICKET} row — a root, so there is no parent to state
   */
  public static Scope ticket(WorkEntity ticket) {
    return own("ticket/" + ticket.slug);
  }

  /**
   * A task's agent may push its own branch and nothing else.
   *
   * @param epic the {@code EPIC} row at the top of the branch name
   * @param feature the task's feature, with the epic its membership edge names
   * @param task the task, with the feature its membership edge names
   */
  public static Scope task(WorkEntity epic, Nested feature, Nested task) {
    return own(taskBranch(epic, feature, task));
  }

  /**
   * An epic's agent may push the epic branch plus every feature branch and every task branch of
   * that epic.
   *
   * <p>The order is: the epic, then each feature followed by its tasks, in the order the lists are
   * given. Call it on a frozen epic (IMPLEMENTATION): then no feature or task can be added, and the
   * list is complete.
   *
   * @param epic the {@code EPIC} row
   * @param features the epic's features, each with the parent its membership edge names
   * @param tasksOf the tasks of one feature, each with its own parent
   */
  public static Scope epic(
      WorkEntity epic, List<Nested> features, Function<Nested, List<Nested>> tasksOf) {
    String branch = epicBranch(epic);
    Set<String> refs = new LinkedHashSet<>();
    refs.add(ref(branch));
    for (Nested feature : features) {
      refs.add(ref(featureBranch(epic, feature)));
      for (Nested task : tasksOf.apply(feature)) {
        refs.add(ref(taskBranch(epic, feature, task)));
      }
    }
    return new Scope(branch, List.copyOf(refs));
  }

  /** The ref of a branch: {@code refs/heads/<branch>}. */
  public static String ref(String branch) {
    return HEADS + branch;
  }

  private static Scope own(String branch) {
    return new Scope(branch, List.of(ref(branch)));
  }

  private static String epicBranch(WorkEntity epic) {
    return "epic/" + epic.slug;
  }

  private static String featureBranch(WorkEntity epic, Nested feature) {
    requireChild(epic.id, feature.parentId(), "feature " + feature.entity().id, "epic " + epic.id);
    return "feature/" + epic.slug + "/" + feature.entity().slug;
  }

  private static String taskBranch(WorkEntity epic, Nested feature, Nested task) {
    requireChild(epic.id, feature.parentId(), "feature " + feature.entity().id, "epic " + epic.id);
    requireChild(
        feature.entity().id,
        task.parentId(),
        "task " + task.entity().id,
        "feature " + feature.entity().id);
    return "task/" + epic.slug + "/" + feature.entity().slug + "/" + task.entity().slug;
  }

  /**
   * A row given with the wrong parent would name a branch of some other work.
   *
   * <p>The child's parent is read off its {@link Nested#parentId} — the {@code entity_membership}
   * edge — where it used to be read off a column. Same question, same refusal, same message.
   */
  private static void requireChild(String parentId, String childsParentId, String child, String parent) {
    if (!Objects.equals(parentId, childsParentId)) {
      throw new IllegalArgumentException(child + " is not part of " + parent);
    }
  }
}
