package eu.wohlben.qits.projects.control.approval;

import java.util.Optional;

/**
 * <b>The work item a branch names, and where it stands</b> (epic qits-1440) — what {@code
 * DeskApprovalPolicy} asks before the delivery desk may approve {@code manual-review}. A branch
 * {@code ticket/<qualified id>}, {@code epic/<qualified id>} or {@code task/<qualified id>}, or an
 * agent's {@code <that branch>-<anything>} ({@code WorkBranches.isAgentBranch}), names the work item
 * with that qualified id and that archetype; any other branch names none.
 *
 * <p>The adapter lives in the service, where the entities module is ({@code
 * entitieshost/EntityBranchWorkItems}). Where no adapter is deployed, every branch names nothing,
 * and the desk's approval is refused: "could not tell" is never the cheap way past a reviewer.
 */
public interface BranchWorkItems {

  /**
   * One work item as the desk's rule reads it.
   *
   * @param qualifiedId {@code <project slug>-<number>}
   * @param projectId the project the item belongs to
   * @param status the stored status word ({@code VERIFIED}, {@code DONE}, …)
   */
  record WorkItem(String qualifiedId, String projectId, String status) {}

  /** The work item {@code branch} names, or empty where it names none or it cannot be read. */
  Optional<WorkItem> workItemOf(String branch);
}
