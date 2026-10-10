package eu.wohlben.qits.projects.control;

import java.time.Instant;
import java.util.List;

/**
 * Tells the rest of the platform that a release request's <b>state</b> changed (qits-1153, W7) —
 * PENDING, READY, RELEASED, REJECTED, FAILED, CONFLICTED, WITHDRAWN, FINALIZED or OBSOLETE.
 *
 * <p>A port, for the reason {@link ReleaseRequestAnnouncer} is one: {@code domain} does not know the
 * event bus exists. The one implementation is {@code service/…/bus/ReleaseRequestStateChangedAnnouncer}.
 * It is called by {@link ReleaseRequestStates} alone, after the transaction that wrote the state
 * committed, once per request whose state really moved in it.
 *
 * <p><b>What it is for.</b> An agent that waits on a release request (qits-workspaces' declared
 * waits) needs to hear when the request ends. {@code ReleaseRequestChanged} says a fold has a new
 * tip, not how the request stands, and nothing else said that.
 *
 * <p><b>Absent is a supported configuration</b>, like every port here. <b>Nothing here may
 * throw</b>.
 */
public interface ReleaseRequestStateAnnouncer {

  /**
   * One request's state change, as it stood when the transaction committed.
   *
   * @param releaseRequestId the request
   * @param projectId the project the repository belongs to, or null
   * @param repoId the repository's storage id
   * @param repoName the repository's public name, or null
   * @param state the state now
   * @param previousState the state before the transaction, or null for a request it created
   * @param changedAt when the state was written
   * @param mergedSha the tip of the fold, or null before the first fold
   * @param version the released version — set from RELEASED on, else null
   * @param detail the sentence for a person (why it is REJECTED, FAILED, WITHDRAWN …), or null
   * @param branches the request's named branch sources, {@code main} included, in the order they
   *     were added; null when they could not be read
   */
  record StateChange(
      String releaseRequestId,
      String projectId,
      String repoId,
      String repoName,
      String state,
      String previousState,
      Instant changedAt,
      String mergedSha,
      String version,
      String detail,
      List<String> branches) {}

  void onStateChanged(StateChange change);
}
