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
 * epic carries a scope that is committed to. A ticket carries one small thing, so a DONE ticket is
 * still editable, still commentable and still reopenable: there is no {@code requireOpen}, and
 * adding one would only mean filing a duplicate whenever a closure turned out to be wrong.
 *
 * <p>What is kept is the shape of the refusals, because the surfaces above depend on it: a target
 * naming no status is a <b>409</b> (the caller asked for a state that does not exist, the same kind
 * of answer as asking for one that is not reachable), while an absent target is a <b>400</b> — a
 * malformed request rather than a refused move. A {@code type} that names nothing is a 400 instead,
 * and that is not an inconsistency: a type is a field being written, not a move being requested.
 *
 * <p><b>Where the status is stored has moved and nothing here has.</b> {@code TicketService} keeps
 * it on the merged {@code entity} row, as the enum's own {@code name()}, and reads it back into
 * {@link TicketStatus} before asking anything of this class — so the adjacency graph, the refusals
 * and their wording are byte for byte what they were.
 */
final class TicketLifecycle {

  /**
   * The adjacency graph: each status names its neighbours in both directions, so a move is legal
   * exactly when it is one step along REPORTED → REFINED → IMPLEMENTED → VERIFIED → DONE or one
   * step back. Written out per status rather than derived from the ordinal, because the order is a
   * fact about the lifecycle and not about how the enum happens to be declared.
   */
  private static final Map<TicketStatus, Set<TicketStatus>> LEGAL_TARGETS =
      Map.of(
          TicketStatus.REPORTED,
          EnumSet.of(TicketStatus.REFINED),
          TicketStatus.REFINED,
          EnumSet.of(TicketStatus.REPORTED, TicketStatus.IMPLEMENTED),
          TicketStatus.IMPLEMENTED,
          EnumSet.of(TicketStatus.REFINED, TicketStatus.VERIFIED),
          TicketStatus.VERIFIED,
          EnumSet.of(TicketStatus.IMPLEMENTED, TicketStatus.DONE),
          TicketStatus.DONE,
          EnumSet.of(TicketStatus.VERIFIED));

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
   * Rejects a move the lifecycle does not allow, naming both ends. <b>Moves are adjacent-only, in
   * either direction</b> ({@link #LEGAL_TARGETS}): a ticket walks the five statuses one step at a
   * time, forward as each phase finishes and backward when one has to be redone, and asking for the
   * status it already has stays refused rather than reading as a no-op.
   *
   * <p>That is also why there is no reject verb: a verification that fails is the ordinary backward
   * move IMPLEMENTED → REFINED, because what a failed verification establishes is that the ticket
   * needs deciding again — which is the same state as a ticket that has just been refined for the
   * first time, and a second vocabulary for it would only have to be mapped back onto this one.
   */
  static void requireTransition(TicketStatus from, TicketStatus target) {
    if (!LEGAL_TARGETS.getOrDefault(from, EnumSet.noneOf(TicketStatus.class)).contains(target)) {
      throw new ConflictException("A ticket cannot move from " + from + " to " + target);
    }
  }
}
