package eu.wohlben.qits.projects.control;

import java.time.Instant;
import java.util.List;

/**
 * <b>Tells the platform that an approval requirement changed</b> (epic qits-1038) — the port behind
 * {@code ApprovalRequirementChanged}. An OPEN requirement is an event so that a later feature can
 * send a reviewer to it; a SATISFIED, DECLINED or VOID one says that is no longer needed.
 *
 * <p>Called by {@link ApprovalNotices} after the transaction that recorded the notice has committed,
 * once per (request, fold, requirement, state). Optional, like every port here: the adapter is
 * {@code bus/ApprovalRequirementChangedAnnouncer} ({@code @DefaultBean}), the suite records through a
 * fake of its own. An implementation must not throw; a failure is logged and lost, never retried.
 */
public interface ApprovalRequirementAnnouncer {

  /**
   * One requirement's new state.
   *
   * @param admits the roles it admits, rendered and sorted
   * @param authors the request's authors — the principals that added its branch sources
   * @param state {@code OPEN}, {@code SATISFIED}, {@code DECLINED} or {@code VOID}
   */
  record RequirementChange(
      String releaseRequestId,
      String releaseRequestQualifiedId,
      String projectId,
      String repoId,
      String repoName,
      String mergedSha,
      String requirementId,
      String reason,
      List<String> admits,
      boolean excludesAuthors,
      List<String> authors,
      String state,
      Instant changedAt) {}

  void onRequirementChanged(RequirementChange change);
}
