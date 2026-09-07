package eu.wohlben.qits.projects.bus;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * A project is gone, with every repository that was under it: this id, this slug, as of this moment.
 *
 * <p>The closing half of {@link ProjectCreated}, and it carries the {@code slug} for a reason that
 * is not symmetry: the slug is what a consumer keyed its own derivation on ({@code
 * *.<slug>.<domain>}), and once the row is deleted there is nothing left here to look it up from —
 * so an event carrying only the id would leave the platform edge holding names it cannot retire.
 *
 * <p>There is no {@code name} on this one. A display label is what a human reads on a thing that
 * exists; nothing is derived from it, and a deletion is not the moment to restate it.
 *
 * <p><b>{@code occurredAt} is when the deletion committed</b>, not the moment {@code publish()} was
 * called. {@code eventId} is generated when absent and kept out of the canonical payload by the
 * library's mix-in, so the payload is the three fields below: {@code projectId}, {@code slug},
 * {@code deletedAt}.
 *
 * <p><b>It lives here rather than in a published vocabulary module</b>, for the reason {@link
 * ProjectCreated} states, which makes the field names the contract — see {@code
 * ProjectLifecycleContractTest}.
 *
 * <p>Registered for reflection in {@link EventWireReflection}, without which a native image loses
 * every announcement inside {@code CanonicalJson}.
 */
public record ProjectDeleted(UUID eventId, String projectId, String slug, Instant deletedAt)
    implements QitsEvent {

  public ProjectDeleted {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
  }

  /** The constructor a publisher uses: the facts, with the identity taken care of. */
  public ProjectDeleted(String projectId, String slug, Instant deletedAt) {
    this(null, projectId, slug, deletedAt);
  }

  @Override
  public Instant occurredAt() {
    return deletedAt;
  }
}
