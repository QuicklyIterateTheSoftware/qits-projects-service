package eu.wohlben.qits.projects.control.approval;

import java.util.Optional;

/**
 * <b>The work item a branch names, and where it stands</b> (epic qits-1440) — what {@code
 * DeskApprovalPolicy} asks before the delivery desk may approve {@code manual-review}. A branch
 * {@code ticket/<qualified id>}, {@code epic/<qualified id>} or {@code task/<qualified id>}, or an
 * agent's {@code <that branch>-<anything>} ({@code WorkBranches.isAgentBranch}), names the work item
 * with that qualified id and that archetype; any other branch names none. Since epic qits-1580 it
 * also says what kind of work the item is, for {@code FeatureBranches}' classification.
 *
 * <p>The adapter lives in the service, where the entities module is ({@code
 * entitieshost/EntityBranchWorkItems}). Where no adapter is deployed, every branch names nothing,
 * and the desk's approval is refused: "could not tell" is never the cheap way past a reviewer.
 */
public interface BranchWorkItems {

  /**
   * One work item as the desk's rule and the feature classification read it.
   *
   * @param qualifiedId {@code <project slug>-<number>}
   * @param projectId the project the item belongs to
   * @param status the stored status word ({@code VERIFIED}, {@code DONE}, …)
   * @param archetype {@code TICKET}, {@code EPIC} or {@code TASK} — the archetype the branch's prefix
   *     names, which the item's own always is
   * @param ticketType a ticket's {@code BUG}, {@code IMPROVEMENT} or {@code MAINTENANCE}; null on
   *     every other archetype, and on a ticket that never said
   * @param title the item's title as it reads now, for a release request's items (qits-1673); null
   *     where the adapter did not read it
   */
  record WorkItem(
      String qualifiedId,
      String projectId,
      String status,
      String archetype,
      String ticketType,
      String title) {

    /** A work item read without its title — the desk's rule and the freeze need none. */
    public WorkItem(
        String qualifiedId, String projectId, String status, String archetype, String ticketType) {
      this(qualifiedId, projectId, status, archetype, ticketType, null);
    }
  }

  /** The work item {@code branch} names, or empty where it names none or it cannot be read. */
  Optional<WorkItem> workItemOf(String branch);

  /**
   * The work item a <b>release request's source</b> is about (qits-1673): {@link #workItemOf}, and
   * beside it an {@code external/<project slug>-<n>[-<anything>]} branch — the branch an outside
   * contributor pushes — read for the item it names, whatever its archetype. Kept apart from {@link
   * #workItemOf} on purpose: an external branch is a label here and nothing more, so it neither
   * counts toward the feature freeze nor lets the desk approve.
   *
   * <p>The default answers {@link #workItemOf} alone, for an adapter that knows no external reading.
   */
  default Optional<WorkItem> releaseItemOf(String branch) {
    return workItemOf(branch);
  }
}
