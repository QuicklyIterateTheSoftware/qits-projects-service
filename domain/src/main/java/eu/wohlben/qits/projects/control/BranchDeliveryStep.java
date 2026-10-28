package eu.wohlben.qits.projects.control;

import java.time.Instant;

/**
 * <b>One branch took one delivery step in one repository</b> (qits-1719): the fact {@link
 * BranchDeliveries} writes to the ledger, announces as {@code BranchDeliveryChanged} after the
 * commit, and hands every {@link BranchDeliveryStepListener} inside the transaction.
 *
 * <p>Flat, field for field the event's payload: a wait selection reads top-level paths, so nothing
 * here nests. Ids are strings, as every id this service writes is.
 *
 * @param projectId the project the repository belongs to
 * @param repoId the repository's storage id
 * @param repository the repository's name, {@code qits-projects-service}
 * @param branch the source branch, {@code ticket/qits-1615}
 * @param requestId the request carrying the branch now — the target, on {@code MOVED}
 * @param qualifiedId that request's qualified id, {@code qits-projects-service-rr-368}
 * @param foldSha the request's merged sha at the step, or null before its first fold
 * @param step {@code JOINED}, {@code MOVED}, {@code FAILED}, {@code CONFLICTED}, {@code
 *     WITHDRAWN}, {@code RELEASED} or {@code LIVE} — never {@code PUSHED}, which is ledger-only
 * @param previousRequestId {@code MOVED} only: the request the branch left
 * @param previousQualifiedId {@code MOVED} only: that request's qualified id
 * @param failure {@code FAILED} only: {@code CI}, {@code AUTOMATION} or {@code RELEASE}
 * @param runId {@code FAILED} only: the red CI run, or the newest automation run; null for {@code
 *     RELEASE}
 * @param version {@code RELEASED} and {@code LIVE}: the tag
 * @param everywhere the branch's least advanced step across every repository of the project, after
 *     this one: {@code PUSHED}, {@code JOINED}, {@code RELEASED} or {@code LIVE}
 * @param changedAt when the step was written
 */
public record BranchDeliveryStep(
    String projectId,
    String repoId,
    String repository,
    String branch,
    String requestId,
    String qualifiedId,
    String foldSha,
    String step,
    String previousRequestId,
    String previousQualifiedId,
    String failure,
    String runId,
    String version,
    String everywhere,
    Instant changedAt) {}
