package eu.wohlben.qits.entities.control;

import eu.wohlben.qits.entities.entity.EntityMembership;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.entities.persistence.EntityMembershipRepository;
import eu.wohlben.qits.entities.persistence.WorkEntityRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>{@link EntityFacts} over the two tables</b> — the store half {@link Nesting} was always going
 * to need, and the one {@code EntityFacts}' javadoc predicted ("a store-backed one over {@code
 * WorkEntityRepository} and {@code EntityMembershipRepository}").
 *
 * <p>An {@link EntityFact} is an archetype plus a parent, and those two live in two tables, so every
 * method here is <b>two queries and never more</b>: the rows, then the edges above or below them,
 * indexed in memory. That is the shape both repositories carry bulk reads for — a tree resolved one
 * node per query is the single performance mistake this model makes easy, and it would be made here
 * first, because this class sits inside the write path of every create.
 *
 * <p><b>A row with no edge is a root, and a null parent is the answer rather than an omission.</b>
 * {@code EntityFact.parentId} being null <em>states</em> rootness — see that record's javadoc — so an
 * epic and a ticket come back as facts, not as absences, and {@code Nesting} can judge them.
 *
 * <p>It opens no transaction and holds no patience of its own: every caller is already inside a
 * {@link WritePatience} body, where a wrap would be a retry on a connection the outer one has
 * already marked.
 */
@ApplicationScoped
public class StoredEntityFacts implements EntityFacts {

  @Inject WorkEntityRepository entities;

  @Inject EntityMembershipRepository memberships;

  @Override
  public Map<String, EntityFact> byIds(Collection<String> ids) {
    List<WorkEntity> rows = entities.listByIds(ids);
    Map<String, String> parents = parentsOf(rows.stream().map(row -> row.id).toList());
    Map<String, EntityFact> facts = new LinkedHashMap<>();
    for (WorkEntity row : rows) {
      facts.put(row.id, new EntityFact(row.id, row.archetype, parents.get(row.id)));
    }
    return facts;
  }

  @Override
  public List<EntityFact> childrenOfAll(Collection<String> parentIds) {
    Map<String, String> parentOf = new HashMap<>();
    for (EntityMembership edge : memberships.childrenOfAll(parentIds)) {
      parentOf.put(edge.childId, edge.parentId);
    }
    List<EntityFact> facts = new ArrayList<>();
    for (WorkEntity row : entities.listByIds(parentOf.keySet())) {
      facts.add(new EntityFact(row.id, row.archetype, parentOf.get(row.id)));
    }
    return facts;
  }

  /** The one edge above each of {@code childIds}, as a map; a child with no edge is simply absent. */
  private Map<String, String> parentsOf(Collection<String> childIds) {
    Map<String, String> parents = new HashMap<>();
    for (EntityMembership edge : memberships.membershipsOfAll(childIds)) {
      parents.put(edge.childId, edge.parentId);
    }
    return parents;
  }
}
