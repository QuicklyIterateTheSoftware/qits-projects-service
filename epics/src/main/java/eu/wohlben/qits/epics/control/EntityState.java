package eu.wohlben.qits.epics.control;

import eu.wohlben.qits.epics.entity.Archetype;
import eu.wohlben.qits.epics.entity.WorkEntity;
import java.util.EnumSet;
import java.util.Set;

/**
 * <b>A candidate row as the registry judges it</b>: what kind it claims to be, which properties it
 * carries, and — separately — which status word, because that one is checked by value and not only
 * by presence.
 *
 * <p>It is deliberately <em>not</em> a {@link WorkEntity}. The two callers that validate against the
 * registry are the ordinary write and the multi-entity transition, and neither of them has a
 * persisted row in hand at the moment the question is asked: the first is holding a request body and
 * the second is holding an intended post-state. Judging a state rather than an entity is what lets
 * both ask the same question, and {@link #of(WorkEntity)} is there for the third case — checking
 * what is already stored, which is what a backfill and a repair want.
 *
 * @param archetype the kind the candidate claims to be
 * @param status the status word as it would be stored, or null for a row with no status
 * @param present every property the candidate carries. Normalised against {@code status} by the
 *     compact constructor, so {@link EntityProperty#STATUS} cannot be present with a null status or
 *     absent with a non-null one — two facts that say the same thing must not be allowed to
 *     disagree, and a caller assembling this by hand would eventually let them
 */
public record EntityState(Archetype archetype, String status, Set<EntityProperty> present) {

  public EntityState {
    // A blank status is no status, for the reason a blank title is no title — see add() below.
    status = (status == null || status.isBlank()) ? null : status;
    EnumSet<EntityProperty> normalised =
        present.isEmpty() ? EnumSet.noneOf(EntityProperty.class) : EnumSet.copyOf(present);
    if (status == null) {
      normalised.remove(EntityProperty.STATUS);
    } else {
      normalised.add(EntityProperty.STATUS);
    }
    present = Set.copyOf(normalised);
  }

  /** A candidate with no status, spelled without the null. */
  public static EntityState of(Archetype archetype, Set<EntityProperty> present) {
    return new EntityState(archetype, null, present);
  }

  /**
   * The state a stored row is in. A property counts as present when its column is non-null — which
   * is the whole of the mapping, because the merged table gives every property exactly one column
   * and a blank one is an absent property rather than an empty value.
   */
  public static EntityState of(WorkEntity entity) {
    EnumSet<EntityProperty> present = EnumSet.noneOf(EntityProperty.class);
    add(present, EntityProperty.TITLE, entity.title);
    add(present, EntityProperty.SLUG, entity.slug);
    add(present, EntityProperty.DESCRIPTION, entity.description);
    add(present, EntityProperty.TICKET_TYPE, entity.ticketType);
    add(present, EntityProperty.IMPETUS, entity.impetus);
    add(present, EntityProperty.ASSIGNEE, entity.assignee);
    add(present, EntityProperty.CREATED_BY, entity.createdBy);
    add(present, EntityProperty.SUPERSEDED_BY, entity.supersededByEntityId);
    add(present, EntityProperty.REPOSITORY_ID, entity.repositoryId);
    add(present, EntityProperty.IMPLEMENTED_AT, entity.implementedAt);
    add(present, EntityProperty.DEPENDS_ON, entity.dependsOnEntityId);
    return new EntityState(entity.archetype, entity.status, present);
  }

  /**
   * A blank string counts as absent, the reading {@code Validations.requireText} already applies
   * everywhere in this module: a title of spaces is a missing title, and letting it through as
   * "present" would turn the required-property gate into a check that a key was in the JSON.
   */
  private static void add(Set<EntityProperty> into, EntityProperty property, Object value) {
    if (value == null || (value instanceof String text && text.isBlank())) {
      return;
    }
    into.add(property);
  }
}
