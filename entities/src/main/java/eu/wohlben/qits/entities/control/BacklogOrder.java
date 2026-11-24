package eu.wohlben.qits.entities.control;

import eu.wohlben.qits.entities.entity.TicketType;
import java.time.Instant;
import java.util.Comparator;

/**
 * <b>The backlog order</b> (qits-2595): the order the work list answers in under {@code
 * order=priority}, and the one the delivery desk takes open work in — {@code DELIVERY_DESK_PROMPT}
 * step 10 ("Work the backlog") in {@code AgentSurfaceDefaults}: bugs first, then by priority, then
 * oldest by creation time. Defined once here so a long-running agent picking its next item from
 * {@code GET /projects/{project}/work?unassigned=true&order=priority} reads the desk's order.
 *
 * <ol>
 *   <li>{@code ticketType == BUG} first. An epic has no ticketType, so it sorts with the non-bugs.
 *   <li>{@code priority} descending. Work always holds one (qits-2496, 0 by default), so a negative
 *       priority comes after unranked work; a null reads as 0.
 *   <li>{@code createdAt} ascending — older first; a null last.
 *   <li>{@code id} ascending, so equal rows still answer one order.
 * </ol>
 *
 * <p>Unlike {@link SiblingPriorities#ORDER}, which orders one parent's children for a board, this
 * mixes epics and tickets across the whole project, as the desk does.
 */
public final class BacklogOrder {

  /** The order — see the class javadoc. */
  public static final Comparator<EntitySummary> ORDER =
      Comparator.comparing((EntitySummary e) -> e.ticketType() == TicketType.BUG ? 0 : 1)
          .thenComparing(
              (EntitySummary e) -> e.priority() == null ? 0 : e.priority(),
              Comparator.reverseOrder())
          .thenComparing(
              EntitySummary::createdAt, Comparator.nullsLast(Comparator.<Instant>naturalOrder()))
          .thenComparing(EntitySummary::id, Comparator.nullsLast(Comparator.naturalOrder()));

  private BacklogOrder() {}
}
