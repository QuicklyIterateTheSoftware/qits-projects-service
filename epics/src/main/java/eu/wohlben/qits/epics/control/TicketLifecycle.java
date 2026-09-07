package eu.wohlben.qits.epics.control;

import eu.wohlben.qits.epics.entity.TicketStatus;
import eu.wohlben.qits.epics.entity.TicketType;
import eu.wohlben.qits.epics.error.ConflictException;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The ticket lifecycle rules, in one place for the reason {@link EpicLifecycle} is — except that
 * there is only one service obeying them, and what is <em>absent</em> here is the whole difference
 * between the two.
 *
 * <p><b>Nothing freezes.</b> An epic's phase decides which fields may still be written, because an
 * epic carries a scope that is committed to. A ticket carries one small thing, so a resolved ticket
 * is still editable, still commentable and still reopenable: there is no {@code requireOpen}, and
 * adding one would only mean filing a duplicate whenever a resolution turned out to be wrong.
 *
 * <p>What is kept is the shape of the refusals, because the surfaces above depend on it: a target
 * naming no status is a <b>409</b> (the caller asked for a state that does not exist, the same kind
 * of answer as asking for one that is not reachable), while an absent target is a <b>400</b> — a
 * malformed request rather than a refused move. A {@code type} that names nothing is a 400 instead,
 * and that is not an inconsistency: a type is a field being written, not a move being requested.
 */
final class TicketLifecycle {

  /** What each status may move to. Both ways, and no status is terminal. */
  private static final Map<TicketStatus, Set<TicketStatus>> LEGAL_TARGETS =
      Map.of(
          TicketStatus.OPEN,
          EnumSet.of(TicketStatus.RESOLVED),
          TicketStatus.RESOLVED,
          EnumSet.of(TicketStatus.OPEN));

  private TicketLifecycle() {}

  /** The status named by {@code value}, or empty when it names none. */
  static Optional<TicketStatus> parse(String value) {
    for (TicketStatus status : TicketStatus.values()) {
      if (status.name().equals(value)) {
        return Optional.of(status);
      }
    }
    return Optional.empty();
  }

  /** The type named by {@code value}, or empty when it names none. */
  static Optional<TicketType> parseType(String value) {
    for (TicketType type : TicketType.values()) {
      if (type.name().equals(value)) {
        return Optional.of(type);
      }
    }
    return Optional.empty();
  }

  /**
   * Rejects a move the lifecycle does not allow, naming both ends. Today that is only a move to the
   * status the ticket is already in — stated as a rule rather than as a special case, so a third
   * status would be described here and nowhere else.
   */
  static void requireTransition(TicketStatus from, TicketStatus target) {
    if (!LEGAL_TARGETS.getOrDefault(from, EnumSet.noneOf(TicketStatus.class)).contains(target)) {
      throw new ConflictException("A ticket cannot move from " + from + " to " + target);
    }
  }
}
