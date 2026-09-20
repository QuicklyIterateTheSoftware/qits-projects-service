package eu.wohlben.qits.projects.bus;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A set of planning entities moved, together: this is what each of them is now, what it is part of,
 * and where among its siblings it sits.
 *
 * <p><b>One event for the batch, and that is the point of the event rather than a detail of it.</b>
 * The operation behind it reaches a state no ordering of single-entity writes could have reached
 * legally — a feature becoming an epic while its tasks are rescoped is the motivating case, and
 * every intermediate shape of it is an illegal tree. Announcing the entities one at a time would
 * therefore describe a sequence of trees that never existed and that a consumer would be right to
 * refuse. The batch is the fact.
 *
 * <p><b>It names things across a boundary the way the platform names them:</b> string ids and
 * declared words, never a reference into this context's tables. Each entry carries its own {@code
 * projectId} because a transition is not obliged to be within one project's worth of entities, even
 * though the write refuses a reparent that would cross projects.
 *
 * <p><b>{@code transitionedAt} is when the transition committed</b>, not the moment {@code publish()}
 * was called, and it is the record's {@link #occurredAt}. The component is named for the fact rather
 * than spelled {@code occurredAt}, because {@code CanonicalJson}'s mix-in matches {@link QitsEvent}'s
 * declared methods <b>by name</b> and a component sharing one of those names would be dropped from
 * every payload with nothing failing anywhere. The same rule is why nothing here is called {@code
 * name} or {@code signature}, and why {@code eventId} appears exactly once, as the standard
 * component every event record in this package carries.
 *
 * <p><b>It lives here rather than in a published vocabulary module</b>, deliberately, for {@link
 * RepositoryRenamed}'s reason: nothing consumes it yet, and a jar this platform's Maven registry
 * does not serve is a build that resolves from a developer's {@code ~/.m2} and fails in a release
 * pipeline's step container. A consumer decodes it with {@code CanonicalJson.payloadTo} into a local
 * record of its own, which is the platform's standing answer.
 *
 * <p>Registered for reflection in {@link EventWireReflection}, <b>nested record included</b> —
 * {@code CanonicalJson} builds its own {@code ObjectMapper}, so nothing else can see that these
 * types cross the wire, and a native image without the registration loses every announcement to a
 * "no serializer found" inside the publish while every JVM test stays green.
 */
public record EntityTransitioned(UUID eventId, List<Entity> entities, Instant transitionedAt)
    implements QitsEvent {

  /**
   * One entity's post-state, as the platform needs to read it.
   *
   * @param entityId the entity — one id space across all four archetypes, which is what makes a
   *     re-archetype a change of one column rather than a move between tables
   * @param projectId the owning project, unchanged by the transition
   * @param archetype what it is now, as the declared word
   * @param parentId what it is part of now, or absent for a root
   * @param position where among its siblings it sits, dense and zero-based, or absent for a root
   * @param slug the git-safe path segment. <b>Unchanged by a move</b> — it names branches already
   *     cut — which is exactly why a consumer holding a branch name needs no repair
   * @param title the label
   * @param status the status word as stored, or absent for a kind with no lifecycle
   */
  public record Entity(
      String entityId,
      String projectId,
      String archetype,
      String parentId,
      Integer position,
      String slug,
      String title,
      String status) {}

  public EntityTransitioned {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
    entities = entities == null ? List.of() : List.copyOf(entities);
  }

  /** The constructor a publisher uses: the facts, with the identity taken care of. */
  public EntityTransitioned(List<Entity> entities, Instant transitionedAt) {
    this(null, entities, transitionedAt);
  }

  @Override
  public Instant occurredAt() {
    return transitionedAt;
  }
}
