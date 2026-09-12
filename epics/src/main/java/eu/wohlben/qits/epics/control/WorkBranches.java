package eu.wohlben.qits.epics.control;

import eu.wohlben.qits.epics.entity.Epic;
import eu.wohlben.qits.epics.entity.Feature;
import eu.wohlben.qits.epics.entity.Task;
import eu.wohlben.qits.epics.entity.Ticket;
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

  /** A ticket's agent may push its own branch and nothing else. */
  public static Scope ticket(Ticket ticket) {
    return own("ticket/" + ticket.slug);
  }

  /** A task's agent may push its own branch and nothing else. */
  public static Scope task(Epic epic, Feature feature, Task task) {
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
   * @param features the epic's features
   * @param tasksOf the tasks of one feature
   */
  public static Scope epic(
      Epic epic, List<Feature> features, Function<Feature, List<Task>> tasksOf) {
    String branch = epicBranch(epic);
    Set<String> refs = new LinkedHashSet<>();
    refs.add(ref(branch));
    for (Feature feature : features) {
      refs.add(ref(featureBranch(epic, feature)));
      for (Task task : tasksOf.apply(feature)) {
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

  private static String epicBranch(Epic epic) {
    return "epic/" + epic.slug;
  }

  private static String featureBranch(Epic epic, Feature feature) {
    requireChild(epic.id, feature.epicId, "feature " + feature.id, "epic " + epic.id);
    return "feature/" + epic.slug + "/" + feature.slug;
  }

  private static String taskBranch(Epic epic, Feature feature, Task task) {
    requireChild(epic.id, feature.epicId, "feature " + feature.id, "epic " + epic.id);
    requireChild(feature.id, task.featureId, "task " + task.id, "feature " + feature.id);
    return "task/" + epic.slug + "/" + feature.slug + "/" + task.slug;
  }

  /** A row given with the wrong parent would name a branch of some other work. */
  private static void requireChild(String parentId, String childsParentId, String child, String parent) {
    if (!Objects.equals(parentId, childsParentId)) {
      throw new IllegalArgumentException(child + " is not part of " + parent);
    }
  }
}
