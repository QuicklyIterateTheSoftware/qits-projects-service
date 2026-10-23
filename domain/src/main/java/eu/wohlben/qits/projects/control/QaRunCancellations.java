package eu.wohlben.qits.projects.control;

/**
 * Cancels the QA runs of a release request whose fold has been superseded — qits-ci's
 * run-cancellation API, seen from this side of the boundary.
 *
 * <h2>Why it exists</h2>
 *
 * A release request's gate is correlated by sha: only a verdict naming the request's <b>current</b>
 * {@code mergedSha} can flip it, so a run still grinding away on a fold that has since been
 * superseded settles nothing and its verdict is ignored on arrival. The gate is therefore already
 * correct without this call. What this saves is a build agent: a busy branch re-folds several times
 * in a minute and every re-fold starts a fresh run, so without a cancellation the queue fills with
 * runs whose answers are known to be worthless before they finish.
 *
 * <p>That is exactly why it is <b>best effort and never fatal</b>. It buys throughput, not
 * correctness, so an unreachable qits-ci, a refusal, an unconfigured address and a service that has
 * never heard of the route are all one answer here: log it and carry on. <b>An implementation must
 * not throw</b>, and a caller must not make a fold conditional on it.
 *
 * <h2>Scoped to one request, always</h2>
 *
 * The far side is told the repository <b>and</b> the release request, and it cancels the runs of
 * that request alone. A sibling request of the same repository is folding its own sources onto its
 * own backing branch and its runs are none of this request's business — cancelling by repository
 * would take a neighbour's green build away seconds before it settled them. The correlation qits-ci
 * has to key on is the one it was given when the run was asked for: {@code ReleaseRequestChanged}
 * carries {@code releaseRequestId} beside the {@code mergedSha} it builds.
 *
 * <p>A port in the house shape: {@code Instance}-resolved, absent supported — with no implementation
 * a superseded run simply runs to the end and its verdict is ignored, which is the behaviour the
 * gate had before this existed.
 */
public interface QaRunCancellations {

  /**
   * Cancel every in-flight run of one release request, whichever CI phase it belongs to — including
   * the tag's own {@code RELEASE}-phase publish run.
   *
   * <p><b>{@code ReleaseRequests} does not call this (qits-733)</b>: a publish run belongs to a
   * released tag, and no request lifecycle may stop it. It stays on the port as the wire call it
   * always was and as {@link #cancelQaRunsOf}'s default.
   *
   * @param repoId the repository's storage id — the git host's key, and qits-ci's
   * @param releaseRequestId the request whose runs are stale. <b>Never null and never widened to
   *     "everything in this repository"</b>: see the class javadoc.
   */
  void cancelRunsOf(String repoId, String releaseRequestId);

  /**
   * Cancel only this request's QA runs — qits-ci's {@code RELEASE_REQUEST} phase — and leave any
   * other run of the request untouched.
   *
   * <p><b>What {@code ReleaseRequests} calls at every cancellation.</b> A publish run carries the
   * <em>same</em> {@code releaseRequestId} as the request's QA runs, since qits-ci correlates both
   * phases by it, so "every run of this request" reaches a tag's publish. Two places meet one in
   * flight: a request that just reached RELEASED, whose tag push may have queued it first
   * (qits-1264), and a RELEASED request superseded by a new one before its publish reported
   * (qits-733). Elsewhere there is no publish run, so QA-only is the same set.
   *
   * <p>The default widens to {@link #cancelRunsOf}: an implementation that does not itself
   * distinguish the phase — including no implementation at all, and an older qits-ci, which simply
   * ignores an unknown {@code phase} field — cancelling everything is the behaviour this call has
   * always had. That fallback is the port's, for an implementation that does not draw the
   * distinction, and this port is best effort there exactly as it is everywhere else.
   *
   * @param repoId the repository's storage id — the git host's key, and qits-ci's
   * @param releaseRequestId the request whose QA runs are stale. <b>Never null and never widened to
   *     "everything in this repository"</b>: see the class javadoc.
   */
  default void cancelQaRunsOf(String repoId, String releaseRequestId) {
    cancelRunsOf(repoId, releaseRequestId);
  }

  /**
   * The cancellation reason qits-ci records when gate 3 interrupts a QA run (epic qits-897,
   * qits-909): an upstream release started building after this request's QA was announced, so the
   * run is stopped and QA is asked for again once the upstream stops building. Not a verdict, and
   * not the request going away either — which is why it is not qits-ci's default, {@code
   * RELEASE_REQUEST_CANCELLED}.
   */
  String HELD_BY_PRE_RUN_GATE = "HELD_BY_PRE_RUN_GATE";

  /**
   * {@link #cancelQaRunsOf(String, String)} saying why: qits-ci records {@code reason} on every run
   * it cancels. qits-ci allowlists it ({@code RELEASE_REQUEST_CANCELLED}, {@link
   * #HELD_BY_PRE_RUN_GATE}); null is the two-argument call, which sends no reason and gets qits-ci's
   * default.
   *
   * <p>The default drops the reason: an implementation that cannot carry it still cancels, which is
   * the part that saves the build agent. Best effort, exactly as the two-argument form.
   */
  default void cancelQaRunsOf(String repoId, String releaseRequestId, String reason) {
    cancelQaRunsOf(repoId, releaseRequestId);
  }
}
