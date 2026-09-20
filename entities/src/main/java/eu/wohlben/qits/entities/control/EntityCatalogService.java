package eu.wohlben.qits.entities.control;

import eu.wohlben.qits.entities.entity.EntityMembership;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.entities.persistence.EntityMembershipRepository;
import eu.wohlben.qits.entities.persistence.WorkEntityRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>The merged model read as itself</b> — every row of a project with its archetype and its place
 * in the tree, in the same vocabulary {@link EntityTransitionService} writes.
 *
 * <h2>Why it exists</h2>
 *
 * <p>The four per-archetype reads answer the four projections, and every one of them drops the
 * columns its kind has no slot for — an {@code Epic} cannot say it is an epic, a {@code Feature}
 * cannot say where it hangs except by the parent id its caller already knew. That is exactly right
 * for the four surfaces that have always existed, and exactly wrong for a caller about to state a
 * post-state: a transition entry is judged against its <em>target</em> archetype and names its
 * parent, so a caller that cannot see the current archetype and the current membership is guessing
 * at both halves of what it is about to restate.
 *
 * <p>So this is the read side of the transition and nothing else. It <b>adds</b> a reading rather
 * than changing one: {@code EpicService}, {@code TicketService}, {@code FeatureService} and {@code
 * TaskService} keep every method, every shape and every caller.
 *
 * <p><b>It answers {@link TransitionedEntity}</b>, which is the transition's own answer shape, for
 * the reason that record exists: it carries every property the merged table has, null where the
 * archetype has no slot for it, so one kind changing into another is describable. A caller reads a
 * row in the shape the write answers in and states the next one in the shape the write takes.
 *
 * <h2>Two queries, never one per row</h2>
 *
 * <p>Both reads are a bulk row read plus a bulk edge read, the rule {@code FeatureService}'s listing
 * already states: the N+1 is the single performance mistake this model makes easy and a whole-tree
 * read is where it would land first.
 *
 * <p>Both are wrapped in {@link ReadPatience}, like every other list read in this module and for its
 * reason: a severed connection answering "this project has no entities" is an answer rather than an
 * outage. Neither runs inside a transaction, which is what makes the wrap legal.
 */
@ApplicationScoped
public class EntityCatalogService {

  @Inject WorkEntityRepository entities;

  @Inject EntityMembershipRepository memberships;

  @Inject ReadPatience reads;

  /**
   * <b>The project's whole planning tree, flat, in tree order</b>: each root oldest-first, and
   * immediately after it its descendants depth-first in position order.
   *
   * <p>Flat rather than nested because the membership is a property of the row here — every entry
   * carries {@code parent} and {@code position}, which is the tree stated in the same vocabulary a
   * transition states it in. A nested answer would have to pick one shape per archetype again, which
   * is the thing the merged model exists not to do.
   */
  public List<TransitionedEntity> listByProject(String projectId) {
    return reads.hold("the entities of a project", () -> tree(projectId));
  }

  /**
   * The named rows, keyed by id, with whatever edge each has. An id that names nothing is simply
   * absent from the answer — this is a read, and deciding what an unknown id means belongs to the
   * caller that supplied it.
   */
  public Map<String, TransitionedEntity> byIds(Collection<String> ids) {
    if (ids == null || ids.isEmpty()) {
      return Map.of();
    }
    return reads.hold("entities by id", () -> index(entities.listByIds(ids)));
  }

  // --- the reads ------------------------------------------------------------

  private Map<String, TransitionedEntity> index(List<WorkEntity> rows) {
    Map<String, EntityMembership> edges = edgesOf(rows);
    Map<String, TransitionedEntity> answered = new LinkedHashMap<>();
    for (WorkEntity row : rows) {
      answered.put(row.id, TransitionedEntity.of(row, edges.get(row.id)));
    }
    return answered;
  }

  private List<TransitionedEntity> tree(String projectId) {
    List<WorkEntity> rows = entities.listByProject(projectId);
    Map<String, EntityMembership> edges = edgesOf(rows);

    Map<String, List<WorkEntity>> children = new LinkedHashMap<>();
    List<WorkEntity> roots = new ArrayList<>();
    for (WorkEntity row : rows) {
      EntityMembership edge = edges.get(row.id);
      if (edge == null) {
        roots.add(row);
      } else {
        children.computeIfAbsent(edge.parentId, parent -> new ArrayList<>()).add(row);
      }
    }
    for (List<WorkEntity> siblings : children.values()) {
      siblings.sort(Comparator.comparingInt(row -> edges.get(row.id).position));
    }

    List<TransitionedEntity> ordered = new ArrayList<>(rows.size());
    for (WorkEntity root : roots) {
      emit(root, children, edges, ordered);
    }
    // A row whose parent is outside this project cannot exist — a transition refuses a cross-project
    // reparent — but emitting from the roots alone would drop such a row silently if one ever did,
    // and a read that quietly omits rows is worse than one that reports an odd shape.
    if (ordered.size() != rows.size()) {
      for (WorkEntity row : rows) {
        if (!containsId(ordered, row.id)) {
          ordered.add(TransitionedEntity.of(row, edges.get(row.id)));
        }
      }
    }
    return ordered;
  }

  private void emit(
      WorkEntity row,
      Map<String, List<WorkEntity>> children,
      Map<String, EntityMembership> edges,
      List<TransitionedEntity> into) {
    into.add(TransitionedEntity.of(row, edges.get(row.id)));
    for (WorkEntity child : children.getOrDefault(row.id, List.of())) {
      emit(child, children, edges, into);
    }
  }

  private Map<String, EntityMembership> edgesOf(List<WorkEntity> rows) {
    List<String> ids = new ArrayList<>(rows.size());
    for (WorkEntity row : rows) {
      ids.add(row.id);
    }
    Map<String, EntityMembership> edges = new LinkedHashMap<>();
    for (EntityMembership edge : memberships.membershipsOfAll(ids)) {
      edges.put(edge.childId, edge);
    }
    return edges;
  }

  private static boolean containsId(List<TransitionedEntity> emitted, String id) {
    for (TransitionedEntity entity : emitted) {
      if (entity.id().equals(id)) {
        return true;
      }
    }
    return false;
  }
}
