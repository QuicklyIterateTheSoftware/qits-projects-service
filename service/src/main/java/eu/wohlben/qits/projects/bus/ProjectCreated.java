package eu.wohlben.qits.projects.bus;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * A project exists: this id, this slug, this display name, as of this moment.
 *
 * <p><b>{@code slug} is the load-bearing field.</b> Every host the platform serves a project under
 * is {@code *.<slug>.<domain>}, and the platform edge terminates TLS for all of them — so the edge
 * derives a certificate's SANs from this one value. {@code projectId} is the opaque handle a
 * consumer keys its own row by; {@code projectName} is a display label that may change and that
 * nothing may be derived from.
 *
 * <p><b>The display name is {@code projectName} and it may never be called {@code name}</b> — a
 * component of that name would be <em>silently dropped from every payload</em>. {@code
 * QitsEvent.name()} is a default method, {@code CanonicalJson}'s mix-in carries a {@code @JsonIgnore}
 * for it, and Jackson matches the mix-in to a record's accessor <b>by name</b>: the component's
 * getter is {@code name()}, so it is ignored along with {@code eventId} and {@code occurredAt}.
 * Measured here — the first draft of this record used {@code name} and published three keys where it
 * meant four, with nothing failing anywhere. The rule generalises to every event on this platform:
 * {@code signature}, {@code name}, {@code eventId} and {@code occurredAt} are the envelope's words
 * and a payload field may not spell any of them.
 *
 * <p><b>Nothing outside this context can derive this.</b> A project is created here and nowhere
 * else, and until it is announced no other service knows the slug exists — which is the same reason
 * {@link RepositoryRenamed} is published: the fact is only visible from inside.
 *
 * <p><b>{@code occurredAt} is when the creation committed</b>, not the moment {@code publish()} was
 * called — the two differ by however long the announcement took to be made, and the one that belongs
 * in an event log is when the thing happened.
 *
 * <p><b>{@code eventId} is a component, and that is safe.</b> It is generated when absent and final
 * once set, which gives the stability the idempotent {@code PUT} rests on, and the library keeps
 * everything {@link QitsEvent} declares out of the canonical payload — so identity travels in the
 * envelope and the payload is the four fields below: {@code projectId}, {@code slug}, {@code
 * projectName}, {@code createdAt}.
 *
 * <p><b>It lives here rather than in a published vocabulary module</b>, deliberately, the standing
 * answer this service gives for every event it publishes: a jar this platform's Maven registry does
 * not serve is a build that resolves from a developer's {@code ~/.m2} and fails in a release
 * pipeline's step container. A consumer decodes it with {@code CanonicalJson.payloadTo} into a local
 * record of its own. Publishing this as a jar is a decision to make when a second repo needs the
 * type, not before — which makes the field names above the contract, and {@code
 * ProjectLifecycleContractTest} is where they are pinned.
 *
 * <p>Registered for reflection in {@link EventWireReflection} — {@code CanonicalJson} builds its own
 * {@code ObjectMapper}, so nothing else can see that this record crosses the wire, and a native
 * image without the registration loses every announcement to a "no serializer found" inside the
 * publish.
 */
public record ProjectCreated(
    UUID eventId, String projectId, String slug, String projectName, Instant createdAt)
    implements QitsEvent {

  public ProjectCreated {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
  }

  /** The constructor a publisher uses: the facts, with the identity taken care of. */
  public ProjectCreated(String projectId, String slug, String projectName, Instant createdAt) {
    this(null, projectId, slug, projectName, createdAt);
  }

  @Override
  public Instant occurredAt() {
    return createdAt;
  }
}
