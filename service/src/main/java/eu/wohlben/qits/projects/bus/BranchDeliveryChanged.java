package eu.wohlben.qits.projects.bus;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * A branch took a delivery step in one repository: joined a request, moved to another, failed,
 * conflicted, was withdrawn, was released, went live (qits-1719).
 *
 * <p><b>What it is for.</b> An agent works on a branch. The request carrying it is not a stable key
 * — a release hands a late branch on to the next request, a successor supersedes a red release and
 * carries its branches, and a ticket's branch rides one request per repository of an estate — so
 * {@link ReleaseRequestStateChanged} cannot answer "where is my branch". This can: a wait on {@code
 * branch} and {@code step}, or on {@code everywhere}, needs no request id at all. qits-workspaces'
 * {@code AgentWaitListener} subscribes to {@code *} and receives it with no change.
 *
 * <p><b>Only a step that moved the ledger is announced, after its transaction commits</b>: {@code
 * domain}'s {@code BranchDeliveries} is the one writer of {@code branch_delivery}, and its javadoc
 * has the rules. A step the row already held (the same step, request, fold and fold attempt)
 * publishes nothing, and neither does a transaction that rolls back. The ledger-only {@code
 * PUSHED} is never published.
 *
 * <p><b>The fields</b>, all top level because a wait selection reads top-level paths, string ids as
 * everywhere on the platform:
 *
 * <ul>
 *   <li>{@code projectId}, {@code repoId}, {@code repository} (the name, {@code
 *       qits-projects-service}), {@code branch} ({@code ticket/qits-1615}) — always set.
 *   <li>{@code requestId}, {@code qualifiedId} — the request carrying the branch now: the target on
 *       {@code MOVED}. Always set.
 *   <li>{@code foldSha} — the request's merged sha at the step; absent before its first fold.
 *       A conflict or a missing source lands no fold, so it leaves this where the last fold that
 *       landed put it.
 *   <li>{@code foldAttempt} — {@code CONFLICTED}, and {@code FAILED} with failure {@code FOLD}:
 *       which attempt the step is about (qits-1725) — 16 hex characters keyed on the conflicting
 *       heads at their tips, or {@code missing:<ref>}. A second conflict at the same {@code
 *       foldSha} with another key is announced; the same key again is not. Absent on every other
 *       step.
 *   <li>{@code step} — {@code JOINED}, {@code MOVED}, {@code FAILED}, {@code CONFLICTED}, {@code
 *       WITHDRAWN}, {@code RELEASED} or {@code LIVE}.
 *   <li>{@code previousRequestId}, {@code previousQualifiedId} — {@code MOVED} only: the request
 *       the branch left.
 *   <li>{@code failure} — {@code FAILED} only: {@code CI}, {@code AUTOMATION}, {@code RELEASE} or
 *       {@code FOLD} (a source ref missing on the git host, qits-1725); {@code runId} — the red CI
 *       run or the newest automation run, absent for {@code RELEASE} and {@code FOLD}.
 *   <li>{@code version} — {@code RELEASED} and {@code LIVE}: the tag.
 *   <li>{@code everywhere} — the branch's least advanced step across the repositories of the
 *       project it still ships to, after this one: {@code PUSHED}, {@code JOINED}, {@code
 *       RELEASED} or {@code LIVE}. The agent configuration (qits-1736) and a WITHDRAWN row
 *       (qits-1737) never count; absent when no row does. A wait for "my branch is live
 *       everywhere" matches {@code everywhere = LIVE}.
 *   <li>{@code changedAt} — when the step was written, and the envelope's {@code occurredAt}; not
 *       spelled {@code occurredAt}, which the canonical mix-in drops from the payload.
 * </ul>
 *
 * <p>{@code CanonicalJson} is {@code NON_NULL}, so every null above is an absent key.
 *
 * <p><b>It lives here rather than in a published vocabulary module</b>, the ruling {@link
 * RepositoryRenamed} states; {@code BranchDeliveryChangedContractTest} pins the field list a
 * consumer builds its local record against. Registered for reflection in {@link
 * EventWireReflection}.
 */
public record BranchDeliveryChanged(
    UUID eventId,
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
    Instant changedAt)
    implements QitsEvent {

  public BranchDeliveryChanged {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
  }

  @Override
  public Instant occurredAt() {
    return changedAt;
  }
}
