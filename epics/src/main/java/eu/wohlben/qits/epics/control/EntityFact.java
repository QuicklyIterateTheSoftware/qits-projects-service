package eu.wohlben.qits.epics.control;

import eu.wohlben.qits.epics.entity.Archetype;
import eu.wohlben.qits.epics.entity.WorkEntity;

/**
 * <b>Everything {@link Nesting} needs to know about one entity</b>: what kind it is and what it
 * hangs under. Three fields and no more, because the nesting rule reads nothing else — not the
 * title, not the status, not the dependency edge.
 *
 * <p>It is the unit of a <em>post-state</em>. A caller says "after my change, these are the facts",
 * passing one of these per entity it intends to touch, and the validator fills in everything else
 * from the store. That is the shape the multi-entity transition needs and the reason the record
 * exists at all: a promotion changes an entity's archetype <em>and</em> its parent, and neither
 * half is legal alone, so there has to be a way to state both at once and ask one question about the
 * result.
 *
 * @param id the entity
 * @param archetype the kind it is (or is becoming)
 * @param parentId what it hangs under, or <b>null for a root</b>. Null is a statement — "this has no
 *     parent" — and not "unchanged": a post-state fact is the whole fact about that entity, because
 *     a partial one would make detaching an entity impossible to express
 */
public record EntityFact(String id, Archetype archetype, String parentId) {

  /** A root: no parent, and the archetype had better be one that may stand alone. */
  public static EntityFact root(String id, Archetype archetype) {
    return new EntityFact(id, archetype, null);
  }

  /** The fact a stored row plus its stored membership already is. */
  public static EntityFact of(WorkEntity entity, String parentId) {
    return new EntityFact(entity.id, entity.archetype, parentId);
  }

  /** Whether this entity stands alone in the post-state. */
  public boolean isRoot() {
    return parentId == null;
  }
}
