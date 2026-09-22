package eu.wohlben.qits.entities.control;

import eu.wohlben.qits.entities.entity.TicketStatus;
import eu.wohlben.qits.entities.entity.TicketType;
import eu.wohlben.qits.entities.error.ConflictException;
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
 * {@link TicketStatus} before asking anything of this class — so the graph below, the refusals and
 * their wording were untouched by the move.
 */
final class TicketLifecycle {

  /**
   * What each status may move to, and <b>the one place the rule is written</b> — everything else in
   * this repository that has to describe a ticket's moves points here rather than restating them.
   *
   * <p><b>The pipeline is adjacent-only, in both directions.</b> REPORTED → REFINED → IMPLEMENTED →
   * VERIFIED → DONE is walked one step at a time, forward as each phase finishes and backward when
   * one has to be redone, and asking for the status the ticket already has stays refused rather
   * than reading as a no-op.
   *
   * <p><b>{@link TicketStatus#DROPPED} is off that line.</b> It is not a sixth step and it has no
   * neighbours on the chain: it is reachable from every status that is not already closed —
   * REPORTED, REFINED, IMPLEMENTED and VERIFIED — because a decision not to do the work can be
   * taken at any point while the work is still open, and it is reached from nowhere else.
   *
   * <p><b>DONE is offered no drop</b>, and that absence is the decision rather than an oversight.
   * DONE is already an exit; the only thing the move could achieve is to let the weaker outcome
   * overwrite a real one, and a ticket that shipped did not stop having shipped. A closure that was
   * wrong still goes back the way every move goes back — DONE → VERIFIED — and the ticket is then
   * open again and droppable like any other.
   *
   * <p><b>DROPPED reopens to REPORTED and to nothing else</b>, which is what keeps this a graph
   * rather than a graph plus a column: resuming at wherever the ticket was abandoned would mean
   * remembering where that was, and it is the wrong answer regardless — somebody who has changed
   * their mind about abandoned work is asking what it is for again, which is the refine phase.
   *
   * <p>Written out per status rather than derived from the ordinal, because the order is a fact
   * about the lifecycle and not about how the enum happens to be declared.
   */
  private static final Map<TicketStatus, Set<TicketStatus>> LEGAL_TARGETS =
      Map.of(
          TicketStatus.REPORTED,
          EnumSet.of(TicketStatus.REFINED, TicketStatus.DROPPED),
          TicketStatus.REFINED,
          EnumSet.of(TicketStatus.REPORTED, TicketStatus.IMPLEMENTED, TicketStatus.DROPPED),
          TicketStatus.IMPLEMENTED,
          EnumSet.of(TicketStatus.REFINED, TicketStatus.VERIFIED, TicketStatus.DROPPED),
          TicketStatus.VERIFIED,
          EnumSet.of(TicketStatus.IMPLEMENTED, TicketStatus.DONE, TicketStatus.DROPPED),
          TicketStatus.DONE,
          EnumSet.of(TicketStatus.VERIFIED),
          TicketStatus.DROPPED,
          EnumSet.of(TicketStatus.REPORTED));

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
   * Rejects a move the lifecycle does not allow, naming both ends. Which moves those are is {@link
   * #LEGAL_TARGETS}' to say and is argued there — the adjacent-only pipeline, the off-path exit,
   * and why DONE is offered no drop — because a rule written twice is a rule that drifts.
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
