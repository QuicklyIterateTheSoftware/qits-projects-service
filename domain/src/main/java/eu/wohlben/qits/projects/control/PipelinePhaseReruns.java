package eu.wohlben.qits.projects.control;

/**
 * Asking qits-ci to run one phase of a release again — the rerun of phase 1 (QA) and of phase 2
 * (publish).
 *
 * <p><b>Both phases are one door over there, and the phase word is the parameter.</b> {@code POST
 * /ci/api/runs/rerun} takes {@code {repoId, releaseRequestId, phase}} and answers {@code {runId}};
 * the phase words are qits-ci's own storage vocabulary, {@link #CI_PHASE_QA} and {@link
 * #CI_PHASE_PUBLISH}, which this service translates from the reader's {@code QA} and {@code PUBLISH}
 * at the door. That translation lives in exactly one place for the same reason the read's does: the
 * vocabulary is qits-ci's, it may grow, and a second copy of the mapping would be free to drift.
 *
 * <p><b>The refusals are the point of this port, not its error handling.</b> qits-ci answers 409
 * with a sentence saying <em>why</em> a phase cannot be asked again — the newest run of that phase
 * succeeded and its verdict was already spent (on cutting the tag, or on publishing what the release
 * names, and in the QA case the fold it built no longer exists); or that phase has never run, so
 * there is nothing to ask again; or it is running right now and the question is still being
 * answered. Every one of those is a fact about this release that the person pressing the button does
 * not have, and <b>the message must reach them intact</b>. So the implementation propagates the far
 * side's status and its message verbatim rather than composing a sentence of its own: a rewritten
 * refusal is a refusal with the reason filed off.
 *
 * <p>A port in the house shape: the implementation is {@code service/…/releasehost} (one HTTP POST
 * on the same {@code qits.projects.release-requests.ci-url} the three existing qits-ci hops use, with
 * the same {@code IdpCiBearer} credential and the same forwarded-pair fallback), resolved through
 * {@code Instance} with absent supported.
 *
 * <p><b>Unlike its three neighbours on that address, this one throws.</b> {@code ActiveBuilds},
 * {@code PublishRuns} and {@code QaRunCancellations} are all read or fire-and-forget, so a hop that
 * did not happen degrades a gate or costs a build agent; this one is a button, and a rerun that
 * silently did not happen is worse than a refusal. 503 where there is no address and no credential,
 * 502 for the exchange, and the far side's own status and sentence for anything it answered itself.
 *
 * <p><b>Nothing about the pipeline is changed by asking.</b> The new run reports on the bus exactly
 * as the first one did, {@code ReleasePipelineRunListener} mirrors it onto the same phase row, and
 * the gates are settled by what they were always settled by. A rerun re-asks a question.
 */
public interface PipelinePhaseReruns {

  /** qits-ci's word for the QA phase — the run at {@code release/<id>@mergedSha}. */
  String CI_PHASE_QA = "RELEASE_REQUEST";

  /** qits-ci's word for the publish phase — the run at {@code <version>@commitSha}. */
  String CI_PHASE_PUBLISH = "RELEASE";

  /**
   * Ask for one phase of one release request to run again.
   *
   * @param repoId the repository the release belongs to
   * @param releaseRequestId the request whose phase to re-fire
   * @param ciPhase {@link #CI_PHASE_QA} or {@link #CI_PHASE_PUBLISH}, qits-ci's own word
   * @return the id of the run qits-ci queued
   * @throws eu.wohlben.qits.projects.error.DomainException the far side's own status and message
   *     where it answered one — <b>409 and 404 reach the caller unchanged</b> — 503 with no address
   *     and no credential, 502 for the exchange itself
   */
  String rerun(String repoId, String releaseRequestId, String ciPhase);
}
