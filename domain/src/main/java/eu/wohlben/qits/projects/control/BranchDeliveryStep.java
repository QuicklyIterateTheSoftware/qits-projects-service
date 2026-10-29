package eu.wohlben.qits.projects.control;

import java.time.Instant;
import java.util.List;

/**
 * <b>One branch took one delivery step in one repository</b> (qits-1719): the fact {@link
 * BranchDeliveries} writes to the ledger, announces as {@code BranchDeliveryChanged} after the
 * commit, and hands every {@link BranchDeliveryStepListener} inside the transaction.
 *
 * <p>Flat, field for field the event's payload — but for {@code conflicts}, which only the
 * in-transaction listeners hear (qits-1725): a wait selection reads top-level paths, so nothing
 * published nests. Ids are strings, as every id this service writes is.
 *
 * @param projectId the project the repository belongs to
 * @param repoId the repository's storage id
 * @param repository the repository's name, {@code qits-projects-service}
 * @param branch the source branch, {@code ticket/qits-1615}
 * @param requestId the request carrying the branch now — the target, on {@code MOVED}
 * @param qualifiedId that request's qualified id, {@code qits-projects-service-rr-368}
 * @param foldSha the request's merged sha at the step, or null before its first fold
 * @param foldAttempt {@code CONFLICTED}, and {@code FAILED} with failure {@code FOLD}: which fold
 *     attempt the step is about ({@link FoldAttempt}, qits-1725) — the conflicting heads' digest,
 *     or {@code missing:<ref>}; null on every other step
 * @param step {@code JOINED}, {@code MOVED}, {@code FAILED}, {@code CONFLICTED}, {@code
 *     WITHDRAWN}, {@code RELEASED} or {@code LIVE} — never {@code PUSHED}, which is ledger-only
 * @param previousRequestId {@code MOVED} only: the request the branch left
 * @param previousQualifiedId {@code MOVED} only: that request's qualified id
 * @param failure {@code FAILED} only: {@code CI}, {@code AUTOMATION}, {@code RELEASE} or {@code
 *     FOLD}
 * @param runId {@code FAILED} only: the red CI run, or the newest automation run; null for {@code
 *     RELEASE} and {@code FOLD}
 * @param version {@code RELEASED} and {@code LIVE}: the tag
 * @param everywhere the branch's least advanced step across the repositories of the project it still
 *     ships to, after this one: {@code PUSHED}, {@code JOINED}, {@code RELEASED} or {@code LIVE};
 *     never counting the agent configuration (qits-1736) or a WITHDRAWN row (qits-1737), and null
 *     when no row counts
 * @param changedAt when the step was written
 * @param conflicts {@code CONFLICTED} only, and <b>in process only</b> (qits-1725): the fold's
 *     conflicts as the git host answered them, so {@link BranchDeliveryNotices} tells only the
 *     branches a conflict names as its {@code head}, of the paths it names. Never on the event
 *     ({@code BranchDeliveryChangedAnnouncer} maps the other components field by field) and never
 *     in the ledger; null on every other step, and on a step built without it
 */
public record BranchDeliveryStep(
    String projectId,
    String repoId,
    String repository,
    String branch,
    String requestId,
    String qualifiedId,
    String foldSha,
    String foldAttempt,
    String step,
    String previousRequestId,
    String previousQualifiedId,
    String failure,
    String runId,
    String version,
    String everywhere,
    Instant changedAt,
    List<BackingBranchMerger.Conflict> conflicts) {

  /** A step with no conflicts: every step but {@code CONFLICTED}, and every reader of the event. */
  public BranchDeliveryStep(
      String projectId,
      String repoId,
      String repository,
      String branch,
      String requestId,
      String qualifiedId,
      String foldSha,
      String foldAttempt,
      String step,
      String previousRequestId,
      String previousQualifiedId,
      String failure,
      String runId,
      String version,
      String everywhere,
      Instant changedAt) {
    this(
        projectId,
        repoId,
        repository,
        branch,
        requestId,
        qualifiedId,
        foldSha,
        foldAttempt,
        step,
        previousRequestId,
        previousQualifiedId,
        failure,
        runId,
        version,
        everywhere,
        changedAt,
        null);
  }
}
