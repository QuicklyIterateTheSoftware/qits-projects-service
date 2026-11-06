package eu.wohlben.qits.entities.control;

import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.EntityMembership;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.entities.error.BadRequestException;
import eu.wohlben.qits.entities.error.ConflictException;
import eu.wohlben.qits.entities.error.NotFoundException;
import eu.wohlben.qits.entities.persistence.EntityMembershipRepository;
import eu.wohlben.qits.entities.persistence.WorkEntityRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * <b>The drop door's write</b> (qits-753): one entity moved directly above or below one of its
 * siblings, and the siblings at and above it renumbered so the order reads that way — in one
 * transaction ({@link WritePatience}), so a second drop racing this one sees either all of it or
 * none. The arithmetic is {@link SiblingPriorities#drop}'s; this reads the rows, judges the anchor
 * and writes the answer back.
 *
 * <p><b>Siblings</b> are the children of the item's STRUCTURAL parent, or — for a root — every root
 * of the project but its issues (epics and tickets are one backlog). DONE and DROPPED rows are
 * neither moved nor counted.
 *
 * <p>No audit row and no announcement: a ranking is the board's arrangement, not a fact about the
 * work, and a drop writes up to a whole backlog's worth of rows that a person sees moving anyway.
 * The caller fires the change hints after this returns, outside the transaction.
 */
@ApplicationScoped
public class EntityPriorityService {

  @Inject WorkEntityRepository entities;

  @Inject EntityMembershipRepository memberships;

  @Inject WritePatience writes;

  /**
   * One sibling the drop renumbered, as written.
   *
   * @param id the entity's UUID
   * @param projectId its project, for the caller's qualified id and its hints
   * @param number its per-project number
   * @param priority the priority it now holds
   */
  public record Ranked(String id, String projectId, long number, int priority) {}

  /**
   * The 409 for a priority write on a closed row: DONE and DROPPED work is ranked by nobody. Shared
   * with {@link WorkEntityService}'s edit, so the patch and the drop door refuse in one sentence.
   */
  public static void requireRankable(String noun, WorkEntity row) {
    if (!SiblingPriorities.isRankable(row.status)) {
      throw new ConflictException(
          "The priority of "
              + noun.toLowerCase(Locale.ROOT)
              + " "
              + row.id
              + " is not written: it is "
              + row.status
              + ", and closed work takes no part in an order");
    }
  }

  /**
   * Moves {@code itemId} directly {@code placement} {@code anchorId} among its siblings and answers
   * every sibling at and above it with its new priority, in order. Both ids are UUIDs; the caller
   * resolved them. The refusals, in order: the item names nothing (404), the item is an issue
   * (400), the item is closed (409), the anchor names nothing, is the item, or is not an open
   * sibling (400).
   */
  public List<Ranked> reprioritize(
      String itemId, String anchorId, SiblingPriorities.Placement placement) {
    return writes.hold("reprioritize an entity", () -> drop(itemId, anchorId, placement));
  }

  private List<Ranked> drop(
      String itemId, String anchorId, SiblingPriorities.Placement placement) {
    WorkEntity item = itemId == null ? null : entities.findById(itemId);
    if (item == null) {
      throw new NotFoundException("Entity not found: " + itemId);
    }
    String noun = WorkEntityService.nounOf(item.archetype);
    if (item.archetype == Archetype.ISSUE) {
      throw new BadRequestException(
          noun + " " + item.id + " takes no part in an order: issues are not ranked"
              + " among the project's roots");
    }
    requireRankable(noun, item);

    WorkEntity anchor = anchorId == null ? null : entities.findById(anchorId);
    if (anchor == null) {
      throw new BadRequestException("The anchor names no entity: " + anchorId);
    }
    if (anchor.id.equals(item.id)) {
      throw new BadRequestException(
          "The anchor is the item itself: an item is not moved relative to itself");
    }
    String parentId = parentOf(item.id);
    if (!item.projectId.equals(anchor.projectId)
        || anchor.archetype == Archetype.ISSUE
        || !Objects.equals(parentId, parentOf(anchor.id))
        || !SiblingPriorities.isRankable(anchor.status)) {
      throw new BadRequestException(
          "The anchor "
              + anchor.id
              + " is not a reorderable sibling of "
              + item.id
              + ": it must hang under the same parent (or be a root of the same project, not an"
              + " issue) and be neither DONE nor DROPPED");
    }

    Map<String, WorkEntity> open = new LinkedHashMap<>();
    for (WorkEntity row : siblings(item.projectId, parentId)) {
      if (SiblingPriorities.isRankable(row.status)) {
        open.put(row.id, row);
      }
    }
    open.put(item.id, item);
    List<SiblingPriorities.Row> rows = new ArrayList<>(open.size());
    for (WorkEntity row : open.values()) {
      rows.add(new SiblingPriorities.Row(row.id, row.number, row.priority));
    }

    List<Ranked> ranked = new ArrayList<>();
    for (SiblingPriorities.Assigned assigned :
        SiblingPriorities.drop(rows, rowOf(item), anchor.id, placement)) {
      WorkEntity row = open.get(assigned.id());
      row.priority = assigned.priority();
      ranked.add(new Ranked(row.id, row.projectId, row.number, assigned.priority()));
    }
    return List.copyOf(ranked);
  }

  private static SiblingPriorities.Row rowOf(WorkEntity row) {
    return new SiblingPriorities.Row(row.id, row.number, row.priority);
  }

  /** The STRUCTURAL parent's id, or null for a root. */
  private String parentOf(String id) {
    return memberships.structuralMembershipOf(id).map(edge -> edge.parentId).orElse(null);
  }

  /** The managed rows of every sibling, the item included: one parent's children, or the roots. */
  private List<WorkEntity> siblings(String projectId, String parentId) {
    if (parentId == null) {
      return entities.listOrderedRoots(projectId);
    }
    List<String> ids = new ArrayList<>();
    for (EntityMembership edge : memberships.childrenOf(parentId)) {
      ids.add(edge.childId);
    }
    return entities.listByIds(ids);
  }
}
