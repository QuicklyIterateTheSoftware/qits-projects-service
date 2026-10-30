package eu.wohlben.qits.projects.control;

import java.time.Instant;

/**
 * Tells the rest of the platform that a source was moved out of a release request into the one
 * queued after it (qits-1665) — {@code ReleaseRequestSourceMoved} on the bus.
 *
 * <p>A port, for the reason {@link ReleaseRequestStateAnnouncer} is one: {@code domain} does not know
 * the event bus exists. The one implementation is {@code
 * service/…/bus/ReleaseRequestSourceMovedAnnouncer}. It is called by {@link
 * ReleaseRequests#moveSource} alone, <b>after</b> the transaction that moved the source committed,
 * once per move.
 *
 * <p><b>Absent is a supported configuration</b>, like every port here. <b>Nothing here may
 * throw</b>.
 */
public interface ReleaseRequestSourceMoveAnnouncer {

  /**
   * One move, as it was committed.
   *
   * @param projectId the project the repository belongs to, or null
   * @param repoId the repository both requests belong to
   * @param repoName the repository's public name, or null
   * @param branch the moved source branch
   * @param fromRequestId the request the source left
   * @param fromQualifiedId its qualified id, {@code <repo>-rr-<n>}
   * @param toRequestId the request queued after it, which holds the source now
   * @param toQualifiedId its qualified id
   * @param minted whether the move minted {@code to}, there being no queued request before it
   * @param movedBy the authenticated principal that moved it, or null
   * @param reason why, as the mover said it
   * @param movedAt when
   */
  record SourceMove(
      String projectId,
      String repoId,
      String repoName,
      String branch,
      String fromRequestId,
      String fromQualifiedId,
      String toRequestId,
      String toQualifiedId,
      boolean minted,
      String movedBy,
      String reason,
      Instant movedAt) {}

  void onSourceMoved(SourceMove move);
}
