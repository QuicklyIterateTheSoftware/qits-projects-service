package eu.wohlben.qits.entities.control;

import eu.wohlben.qits.entities.entity.Idea;
import eu.wohlben.qits.entities.entity.IdeaStatus;
import eu.wohlben.qits.entities.error.BadRequestException;
import eu.wohlben.qits.entities.error.ConflictException;
import eu.wohlben.qits.entities.error.NotFoundException;
import eu.wohlben.qits.entities.persistence.IdeaRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * <b>The ranking of a project's ideas</b> (qits-1791): the order rule the listing answers in, which
 * ideas take part in it, and the drop door's write — one idea moved directly above or below another
 * of its project, and the ideas at and above it renumbered so the order reads that way, in one
 * transaction ({@link WritePatience}). The arithmetic is {@link SiblingPriorities#drop}'s, reused
 * unchanged, exactly as {@link EntityPriorityService} uses it for work.
 *
 * <h2>The order rule</h2>
 *
 * <p>{@link #ORDER}: {@code priority} descending, a null after every number, then {@code createdAt}
 * ascending, then the id. With no idea ranked it is the oldest-first order the listing always had.
 *
 * <h2>Rankable</h2>
 *
 * <p>An idea that is neither archived nor DONE ({@link #isRankable}): PLANNED and QUEUED. Only a
 * rankable idea is written a priority, by the patch or the drop, and only rankable ideas are moved
 * or counted by a drop.
 *
 * <h2>The drop</h2>
 *
 * <p>The project's rankable ideas are read oldest first ({@code IdeaRepository}'s order) and each
 * becomes a {@link SiblingPriorities.Row} whose {@code number} is its index in that read: the
 * tie-break {@link SiblingPriorities#ORDER} then reads is createdAt-then-id, which is this class's
 * order rule. Unlike work's drop, this one announces: an idea list is kept fresh only by the {@code
 * Idea*} events, so every idea whose priority actually moved is announced {@code UPDATED} once,
 * after the hold ({@link IdeaAnnouncements}).
 */
@ApplicationScoped
public class IdeaPriorityService {

  /** The order rule — see the class javadoc. */
  public static final Comparator<Idea> ORDER =
      Comparator.comparing(
              (Idea idea) -> idea.priority, Comparator.nullsLast(Comparator.reverseOrder()))
          .thenComparing(idea -> idea.createdAt, Comparator.nullsLast(Comparator.naturalOrder()))
          .thenComparing(idea -> idea.id);

  @Inject IdeaRepository ideas;

  @Inject WritePatience writes;

  @Inject IdeaAnnouncements announcements;

  /**
   * One idea the drop renumbered, as written.
   *
   * @param id the idea's id
   * @param priority the priority it now holds
   */
  public record Ranked(String id, int priority) {}

  /** {@code ideas} in {@link #ORDER}, as a new list. */
  public static List<Idea> sorted(List<Idea> ideas) {
    List<Idea> out = new ArrayList<>(ideas);
    out.sort(ORDER);
    return out;
  }

  /** Whether {@code idea} takes part in an order: neither archived nor DONE. */
  public static boolean isRankable(Idea idea) {
    return !idea.archived && idea.status != IdeaStatus.DONE;
  }

  /**
   * The 409 for a priority write on an idea that is not rankable. Shared with {@link IdeaService}'s
   * edit, so the patch and the drop door refuse in one sentence.
   */
  public static void requireRankable(Idea idea) {
    if (!isRankable(idea)) {
      throw new ConflictException(
          "The priority of idea "
              + idea.id
              + " is not written: it is "
              + (idea.archived ? "archived" : idea.status)
              + ", and an archived or DONE idea takes no part in an order");
    }
  }

  /**
   * Moves {@code ideaId} directly {@code placement} {@code anchorId} among its project's rankable
   * ideas and answers every idea at and above it with its new priority, in order. The refusals, in
   * order: the idea names nothing (404), the idea is not rankable (409), the anchor names nothing,
   * is the idea itself, is another project's or is not rankable (400).
   */
  public List<Ranked> reprioritize(
      String ideaId, String anchorId, SiblingPriorities.Placement placement, String changedBy) {
    List<IdeaChange> changes = new ArrayList<>();
    List<Ranked> ranked =
        writes.hold(
            "reprioritize an idea",
            () -> {
              changes.clear();
              return drop(ideaId, anchorId, placement, changedBy, changes);
            });
    announcements.announce(changes);
    return ranked;
  }

  private List<Ranked> drop(
      String ideaId,
      String anchorId,
      SiblingPriorities.Placement placement,
      String changedBy,
      List<IdeaChange> changes) {
    Idea item = ideaId == null ? null : ideas.findById(ideaId);
    if (item == null) {
      throw new NotFoundException("Idea not found: " + ideaId);
    }
    requireRankable(item);

    Idea anchor = anchorId == null ? null : ideas.findById(anchorId);
    if (anchor == null) {
      throw new BadRequestException("The anchor names no idea: " + anchorId);
    }
    if (anchor.id.equals(item.id)) {
      throw new BadRequestException(
          "The anchor is the idea itself: an idea is not moved relative to itself");
    }
    if (!Objects.equals(item.projectId, anchor.projectId) || !isRankable(anchor)) {
      throw new BadRequestException(
          "The anchor "
              + anchor.id
              + " is not a rankable idea beside "
              + item.id
              + ": it must be an idea of the same project, neither archived nor DONE");
    }

    Map<String, Idea> open = new LinkedHashMap<>();
    for (Idea idea : ideas.listByProject(item.projectId, false, null)) {
      if (isRankable(idea)) {
        open.put(idea.id, idea);
      }
    }
    List<SiblingPriorities.Row> rows = new ArrayList<>(open.size());
    SiblingPriorities.Row moved = null;
    long index = 0;
    for (Idea idea : open.values()) {
      SiblingPriorities.Row row = new SiblingPriorities.Row(idea.id, index++, idea.priority);
      rows.add(row);
      if (idea.id.equals(item.id)) {
        moved = row;
      }
    }

    List<Ranked> ranked = new ArrayList<>();
    for (SiblingPriorities.Assigned assigned :
        SiblingPriorities.drop(rows, moved, anchor.id, placement)) {
      Idea idea = open.get(assigned.id());
      boolean changed = !Objects.equals(idea.priority, assigned.priority());
      idea.priority = assigned.priority();
      ranked.add(new Ranked(idea.id, assigned.priority()));
      if (changed) {
        changes.add(IdeaChange.of(IdeaChange.Kind.UPDATED, idea, null, changedBy));
      }
    }
    ideas.getEntityManager().flush();
    return List.copyOf(ranked);
  }
}
