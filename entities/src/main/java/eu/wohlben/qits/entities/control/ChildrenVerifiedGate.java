package eu.wohlben.qits.entities.control;

import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.EntityMembership;
import eu.wohlben.qits.entities.entity.EntityStatus;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.entities.persistence.EntityMembershipRepository;
import eu.wohlben.qits.entities.persistence.WorkEntityRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * <b>{@code CHILDREN_VERIFIED}: an epic or a feature is verified only once every piece under it
 * is</b> (qits-2267). It judges an epic's or a feature's move INTO VERIFIED from the walk —
 * VERIFYING → VERIFIED (FORWARD) and IMPLEMENTED → VERIFIED (SKIP) — and nothing else, so the bulk
 * door ({@code EntityTransitionService.refuseGatedStatus}) shuts exactly those status changes too.
 * Before it, an epic could stand VERIFIED over features and tasks still IMPLEMENTED: nothing related
 * a parent's VERIFIED to its pieces.
 *
 * <p><b>Which pieces count</b>: every STRUCTURAL descendant — an epic's features and their tasks, a
 * feature's tasks — read a level at a time. Each must be VERIFIED, DONE or DROPPED. A DONE or
 * DROPPED piece is not looked beneath: a DONE one has no open descendant (qits-1685's cascade closed
 * them), and a DROPPED one was decided against with whatever it held. A VERIFIED feature is, since
 * its tasks may predate this gate. The open ones are named deepest first — a feature's tasks before
 * the feature — which is the order they have to be verified in. <b>An item with no children
 * passes</b>: nothing under it is open.
 *
 * <p>No mover rule: whoever may verify the item may, once its pieces are verified.
 *
 * <p>Nothing is derived here: verifying the last task moves no feature, and this gate moves no
 * piece — a piece is verified on its own (qits-763). It only says whether a VERIFIED somebody asked
 * for is true.
 */
@ApplicationScoped
public class ChildrenVerifiedGate implements TransitionGate {

  public static final String NAME = "CHILDREN_VERIFIED";

  /** The archetypes whose VERIFIED this gate judges: the ones that hold structural children. */
  static final Set<Archetype> ARCHETYPES = EnumSet.of(Archetype.EPIC, Archetype.FEATURE);

  @Inject EntityMembershipRepository memberships;

  @Inject WorkEntityRepository entities;

  /** The pieces' qualified ids for the sentence; {@code #<number>} without it. */
  @Inject Instance<EntityQualifier> qualifier;

  @Override
  public String name() {
    return NAME;
  }

  /** An epic's or a feature's move into VERIFIED from an open status on the walk before it. */
  @Override
  public boolean appliesTo(Archetype archetype, EntityStatus from, EntityStatus to) {
    return ARCHETYPES.contains(archetype)
        && to == EntityStatus.VERIFIED
        && from != null
        && !EntityStateMachine.isOffWalk(from)
        && !EntityStateMachine.isAtOrPast(from, EntityStatus.VERIFIED);
  }

  @Override
  public Optional<String> refusal(WorkEntity row, Mover mover) {
    List<String> open = new ArrayList<>();
    collectOpen(row.id, tree(row.id), open);
    if (open.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        (open.size() == 1 ? "a piece is" : open.size() + " pieces are")
            + " not verified yet: "
            + String.join(", ", open)
            + " — verify or drop "
            + (open.size() == 1 ? "it" : "them")
            + " first, tasks before features");
  }

  /** Each piece under {@code rootId} by its parent, in listing order, read a level at a time. */
  private Map<String, List<WorkEntity>> tree(String rootId) {
    Map<String, List<WorkEntity>> children = new HashMap<>();
    Set<String> seen = new HashSet<>(Set.of(rootId));
    List<String> level = List.of(rootId);
    while (!level.isEmpty()) {
      List<EntityMembership> edges = memberships.childrenOfAll(level);
      Map<String, WorkEntity> rows =
          entities.listByIds(edges.stream().map(edge -> edge.childId).toList()).stream()
              .collect(Collectors.toMap(entity -> entity.id, Function.identity()));
      List<String> next = new ArrayList<>();
      for (EntityMembership edge : edges) {
        WorkEntity child = rows.get(edge.childId);
        if (child == null || !seen.add(child.id)) {
          continue;
        }
        children.computeIfAbsent(edge.parentId, parent -> new ArrayList<>()).add(child);
        if (!isClosed(child.status)) {
          next.add(child.id);
        }
      }
      level = next;
    }
    return children;
  }

  /** The open pieces under {@code parentId}, post-order: a piece's own pieces before it. */
  private void collectOpen(String parentId, Map<String, List<WorkEntity>> children, List<String> open) {
    for (WorkEntity child : children.getOrDefault(parentId, List.of())) {
      collectOpen(child.id, children, open);
      if (!isSettled(child.status)) {
        open.add(EntityQualifier.render(qualifier, child) + " (" + child.status + ")");
      }
    }
  }

  /** VERIFIED, DONE or DROPPED: a piece that no longer stands in its parent's VERIFIED. */
  static boolean isSettled(String statusWord) {
    return EntityStatus.VERIFIED.name().equals(statusWord) || isClosed(statusWord);
  }

  /** DONE or DROPPED: nothing beneath it is read. */
  private static boolean isClosed(String statusWord) {
    return ChildrenClosedGate.isClosed(statusWord);
  }
}
