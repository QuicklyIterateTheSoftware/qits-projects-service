package eu.wohlben.qits.projects.dto;

import java.time.Instant;

/**
 * One phase of a release pipeline: the newest run of it, and what that run is doing.
 *
 * <p><b>A phase is a unit of work with a state and a rerun, and only three things are one.</b>
 * {@code QA} is the run a {@code ReleaseRequestChanged} causes at {@code release/<id>@mergedSha};
 * {@code PUBLISH} is the run an {@code SCMRelease} causes at {@code <version>@commitSha}; {@code
 * DEPLOY} is the deployment request qits-deployments answers for {@code (repository, version)}.
 * Nothing else is a phase — a <em>step</em> inside one of those runs is not, and neither is the
 * {@code gating: false} half of a pipeline, which is part of the same run and has no rerun of its
 * own. Adding a fourth word here is a statement that a fourth thing can be re-run on its own, and it
 * is a decision rather than a label.
 *
 * <p><b>{@code phase} is the reader's word and not the storage's.</b> qits-ci records {@code
 * RELEASE_REQUEST} and {@code RELEASE} on its own run rows, and this service mirrors those words
 * verbatim in {@code release_pipeline_run} — the translation to {@code QA} and {@code PUBLISH}
 * happens here, at the read, so a vocabulary this service does not own can grow over there without a
 * migration here and a word nobody has taught this service is simply not drawn.
 *
 * <p><b>{@code state} is {@code PENDING}, {@code RUNNING}, {@code SUCCESS}, {@code FAILED}, {@code
 * CANCELLED} or {@code UNKNOWN}</b> — a word rather than a closed set, like every other state word
 * on this surface, because the vocabulary may grow. It is qits-ci's run status folded onto what a
 * pipeline view needs: a queued run is {@code PENDING} (accepted, nothing has happened), and the
 * three ways a run can go red over there — {@code FAILED}, {@code CONFIG_ERROR}, {@code TIMED_OUT} —
 * are one {@code FAILED} here, because what the pipeline is waiting on does not differ between them
 * and the run's own page is where the distinction belongs. {@code CANCELLED} stays its own word: a
 * run somebody stopped is not a run that failed, and a reader offered a rerun needs to know which.
 * {@code UNKNOWN} is a status word this service cannot place, never a gap.
 *
 * <p><b>{@code runId} is null before the phase exists</b>, which is the ordinary state of the
 * publish phase of a release whose tag has not been cut — and such a phase is not in the list at
 * all, so a null here means the phase is known to have begun and its identifier is not (a deployment
 * phase read from a fact that carries no request id). It is qits-ci's run id for the two run phases
 * and the deployment request's id for {@code DEPLOY}: one field, because a caller does one thing
 * with it, which is address the phase's own page.
 *
 * <p><b>{@code startedAt} and {@code finishedAt} are the run's own instants and never this read's.</b>
 * Both may be null and they are null independently: a run still queued has neither, a run in flight
 * has only the first, and a run this service first heard about when it was already green — which is
 * what a catch-up over a disconnect delivers — has only the second. Null is "not known", never
 * "zero".
 */
public record ReleasePhaseDto(
    String phase, String state, String runId, Instant startedAt, Instant finishedAt) {}
