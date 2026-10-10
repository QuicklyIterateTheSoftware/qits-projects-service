package eu.wohlben.qits.projects.bus;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A release request's state changed: this request, this repository, from this state to that one
 * (qits-1153, W7).
 *
 * <p>The states are {@code ReleaseRequest.State}'s words: {@code PENDING}, {@code READY}, {@code
 * RELEASED}, {@code REJECTED}, {@code FAILED}, {@code CONFLICTED}, {@code WITHDRAWN}, {@code
 * FINALIZED} and {@code OBSOLETE}. A request ends in {@code FINALIZED}, {@code REJECTED}, {@code
 * FAILED}, {@code WITHDRAWN} or {@code OBSOLETE} — though {@code REJECTED}, {@code FAILED} and
 * {@code CONFLICTED} can come back to {@code PENDING} or {@code READY} on a new fold or a retry.
 *
 * <p><b>What it is for.</b> An agent that waits on a release request (qits-workspaces' declared
 * waits, {@code qits wait release-request <id>}) is woken by a match on {@code releaseRequestId} and
 * {@code state}. {@link ReleaseRequestChanged} says the fold has a new tip, which is a different
 * fact: a state can move with no new fold (a verdict, a decline, a withdrawal, the finalization),
 * and a new fold can leave the state where it was.
 *
 * <p><b>Only a real change is announced, after its transaction commits</b>, once per request per
 * transaction: {@code domain}'s {@code ReleaseRequestStates} is the one place a state is written,
 * and its javadoc has the rules. A write to the state the row already has publishes nothing, and so
 * does a transaction that rolls back.
 *
 * <p><b>The fields</b>, string ids and declared words as everywhere on the platform:
 *
 * <ul>
 *   <li>{@code releaseRequestId}, {@code repoId} — always set. {@code projectId} and {@code
 *       repoName} may be null, as on {@link ReleaseRequestChanged}.
 *   <li>{@code state} — the state now. {@code previousState} — the state before; <b>absent</b> on
 *       the event for a request that was just created (it moves from nothing to {@code PENDING}).
 *   <li>{@code changedAt} — when the state was written, and the envelope's {@code occurredAt}. It
 *       is not spelled {@code occurredAt}: the canonical mix-in drops a component with that name
 *       from the payload.
 *   <li>{@code mergedSha} — the fold's tip, absent before the first fold. {@code version} — the
 *       released version, set from {@code RELEASED} on. {@code detail} — the sentence for a person
 *       (why it was rejected, failed, withdrawn or made obsolete), absent when there is none.
 *   <li>{@code branches} — the request's named branch sources, {@code main} included, in the order
 *       they were added: what a wait on "my branch's release" can match with {@code branches[]}.
 *       Absent when they could not be read.
 * </ul>
 *
 * <p>{@code CanonicalJson} is {@code NON_NULL}, so every null above is an absent key.
 *
 * <p><b>It lives here rather than in a published vocabulary module</b>, the ruling {@link
 * RepositoryRenamed} states. A consumer decodes it with {@code CanonicalJson.payloadTo} into a local
 * record of its own; {@code ReleaseRequestStateChangedContractTest} pins the field list it builds
 * that record against.
 *
 * <p>Registered for reflection in {@link EventWireReflection}.
 */
public record ReleaseRequestStateChanged(
    UUID eventId,
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
    List<String> branches)
    implements QitsEvent {

  public ReleaseRequestStateChanged {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
    branches = branches == null ? null : List.copyOf(branches);
  }

  /** The constructor a publisher uses: the facts, with the identity taken care of. */
  public ReleaseRequestStateChanged(
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
      List<String> branches) {
    this(
        null,
        releaseRequestId,
        projectId,
        repoId,
        repoName,
        state,
        previousState,
        changedAt,
        mergedSha,
        version,
        detail,
        branches);
  }

  @Override
  public Instant occurredAt() {
    return changedAt;
  }
}
