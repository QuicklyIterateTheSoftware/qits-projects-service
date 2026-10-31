package eu.wohlben.qits.entities.control;

import eu.wohlben.qits.entities.entity.EntityStatus;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * <b>The order of siblings and the drop that rearranges it</b> (qits-753), as pure functions over
 * {@link Row}s — no database, no CDI, so every case is a plain unit test ({@code
 * SiblingPrioritiesTest}). {@link EntityPriorityService} reads the rows and writes the answer back.
 *
 * <h2>The order rule</h2>
 *
 * <p>{@link #ORDER}: {@code priority} descending, a null after every number, then the qualified id's
 * {@code number} ascending, then the id. Higher is sooner; an unranked row keeps the order it was
 * filed in, which is the order every board showed before the priority existed. The server's listing
 * does not sort by it — a client does, over the siblings it draws.
 *
 * <h2>The drop</h2>
 *
 * <p>{@link #drop} takes the siblings S that are still open (neither DONE nor DROPPED), sorts them,
 * takes the moved item out and puts it back directly above or directly below the anchor, at index
 * {@code i}. The floor is the highest priority among S[i+1..] (0 when none is ranked), and S[0..i]
 * are renumbered {@code floor+i+1, floor+i, …, floor+1}: strictly descending, every one above every
 * ranked row below, so the order afterwards is exactly the order the drop drew. <b>Nothing below
 * {@code i} is touched</b> — a drop near the top of a long backlog writes a handful of rows, and an
 * unranked tail stays unranked.
 *
 * <p>The answer is every row of S[0..i] with its new priority, whether or not the value moved: one
 * rule, the same answer for the same input, and a caller that redraws the rows it is handed.
 */
public final class SiblingPriorities {

  /**
   * One sibling, as much of it as the order reads.
   *
   * @param id the entity's UUID — the last tie-break
   * @param number the per-project number its qualified id carries
   * @param priority its ranking, or null for none
   */
  public record Row(String id, long number, Integer priority) {}

  /** One row of a drop's answer: the sibling and the priority it now holds. */
  public record Assigned(String id, int priority) {}

  /** Where the moved item lands relative to its anchor. */
  public enum Placement {
    /** Directly above the anchor: the anchor is the row that now follows it. */
    ABOVE,
    /** Directly below the anchor: the anchor is the row that now precedes it. */
    BELOW
  }

  /** The order rule — see the class javadoc. */
  public static final Comparator<Row> ORDER =
      Comparator.comparing(Row::priority, Comparator.nullsLast(Comparator.reverseOrder()))
          .thenComparingLong(Row::number)
          .thenComparing(Row::id);

  private SiblingPriorities() {}

  /** {@code rows} in {@link #ORDER}, as a new list. */
  public static List<Row> sorted(List<Row> rows) {
    List<Row> out = new ArrayList<>(rows);
    out.sort(ORDER);
    return out;
  }

  /**
   * Whether a row in {@code status} takes part in an order at all: every status but DONE and
   * DROPPED. Closed work is neither moved nor moved around, and a patch of its priority is a 409.
   */
  public static boolean isRankable(String status) {
    return !EntityStatus.DONE.name().equals(status) && !EntityStatus.DROPPED.name().equals(status);
  }

  /**
   * The drop — see the class javadoc.
   *
   * @param siblings the open siblings, in any order, the moved item among them or not
   * @param item the moved row; where it stood before is irrelevant, it goes where the drop says
   * @param anchorId the sibling the item now sits directly {@code placement} relative to
   * @param placement above or below the anchor
   * @return S[0..i] in their new order, each with its new priority
   * @throws IllegalArgumentException when the anchor is the item or is not among {@code siblings};
   *     the caller refuses both in its own words before it gets here
   */
  public static List<Assigned> drop(
      List<Row> siblings, Row item, String anchorId, Placement placement) {
    Objects.requireNonNull(item, "item");
    Objects.requireNonNull(placement, "placement");
    if (item.id().equals(anchorId)) {
      throw new IllegalArgumentException("the anchor is the item itself: " + anchorId);
    }
    List<Row> order = new ArrayList<>();
    for (Row row : sorted(siblings)) {
      if (!row.id().equals(item.id())) {
        order.add(row);
      }
    }
    int anchor = indexOf(order, anchorId);
    if (anchor < 0) {
      throw new IllegalArgumentException("the anchor is not among the siblings: " + anchorId);
    }
    int at = placement == Placement.ABOVE ? anchor : anchor + 1;
    order.add(at, item);

    Integer highest = null;
    for (int below = at + 1; below < order.size(); below++) {
      Integer priority = order.get(below).priority();
      if (priority != null && (highest == null || priority > highest)) {
        highest = priority;
      }
    }
    int floor = highest == null ? 0 : highest;
    List<Assigned> assigned = new ArrayList<>(at + 1);
    for (int j = 0; j <= at; j++) {
      assigned.add(new Assigned(order.get(j).id(), floor + at + 1 - j));
    }
    return List.copyOf(assigned);
  }

  private static int indexOf(List<Row> rows, String id) {
    for (int i = 0; i < rows.size(); i++) {
      if (rows.get(i).id().equals(id)) {
        return i;
      }
    }
    return -1;
  }
}
