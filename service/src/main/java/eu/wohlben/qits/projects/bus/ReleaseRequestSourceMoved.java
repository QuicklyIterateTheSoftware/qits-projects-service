package eu.wohlben.qits.projects.bus;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * A source was moved out of a release request into the request queued after it (qits-1665), through
 * {@code POST /repositories/{repoId}/release-requests/{requestId}/sources/move}: it alone held the
 * request back, and the others release without it.
 *
 * <p><b>Published once per move, after its transaction commits</b>, by {@code domain}'s {@code
 * ReleaseRequests.moveSource}. The branch's own step is {@link BranchDeliveryChanged} {@code MOVED},
 * written in the same transaction; this one says who moved it and why, for the request's readers.
 * The request it left refolds without it, which {@link ReleaseRequestChanged} announces as any fold.
 *
 * <p><b>The fields</b>, all top level, string ids as everywhere on the platform:
 *
 * <ul>
 *   <li>{@code projectId}, {@code repoId}, {@code repoName}, {@code branch} — where, and which
 *       source ({@code ticket/qits-1660}).
 *   <li>{@code fromRequestId}, {@code fromQualifiedId} — the request the source left.
 *   <li>{@code toRequestId}, {@code toQualifiedId} — the request queued after it, which holds the
 *       source now; {@code minted} says the move created it, there being none queued before.
 *   <li>{@code movedBy} — the authenticated principal; {@code reason} — why, as it said it.
 *   <li>{@code movedAt} — when, and the envelope's {@code occurredAt}.
 * </ul>
 *
 * <p>{@code CanonicalJson} is {@code NON_NULL}, so every null above is an absent key. It lives here
 * rather than in a published vocabulary module, the ruling {@link RepositoryRenamed} states.
 * Registered for reflection in {@link EventWireReflection}.
 */
public record ReleaseRequestSourceMoved(
    UUID eventId,
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
    Instant movedAt)
    implements QitsEvent {

  public ReleaseRequestSourceMoved {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
  }

  @Override
  public Instant occurredAt() {
    return movedAt;
  }
}
