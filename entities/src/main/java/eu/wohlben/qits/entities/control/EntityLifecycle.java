package eu.wohlben.qits.entities.control;

import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.EntityStatus;
import eu.wohlben.qits.entities.entity.TicketType;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.entities.error.ConflictException;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The lifecycle rules of every archetype that has a status — epic and ticket alike — in one place
 * over one enum, {@link EntityStatus}. Until qits-392 there were two classes here, {@code
 * EpicLifecycle} and {@code TicketLifecycle}, over two enums; the ticket's reversible graph is the
 * one that survived, and the epic's two freeze guards were re-expressed in its words rather than
 * kept beside it.
 *
 * <p><b>The graph is the same for every archetype.</b> {@link #LEGAL_TARGETS} is written once and
 * {@link #requireTransition} asks it for an epic exactly as it does for a ticket; only the sentence
 * a refusal is phrased in names the kind.
 *
 * <p><b>What freezes is not the same, and only an epic freezes anything.</b> An epic carries a
 * scope that is committed to, so its status decides which fields may still be written:
 *
 * <ul>
 *   <li>a structural change — the epic's title or description, and any feature/task create, update
 *       or delete, dependencies included — needs {@link EntityStatus#REPORTED} ({@link
 *       #requireReported});
 *   <li>the implemented markers ({@code implementedOn}/{@code implementedAt}) need {@link
 *       EntityStatus#REFINED} ({@link #requireRefined}).
 * </ul>
 *
 * The two guards therefore reject everything from IMPLEMENTED on without a rule of their own. The
 * freeze is <b>reversible in the ordinary way</b>: an epic whose scope has to change moves back to
 * REPORTED along the graph, and nobody needs a new door for it. A ticket carries one small thing, so
 * a DONE ticket is still editable, still commentable and still reopenable: there is no {@code
 * requireOpen} for it, and adding one would only mean filing a duplicate whenever a closure turned
 * out to be wrong.
 *
 * <p>Deleting an epic stays allowed in every status: it removes the row and its subtree rather than
 * changing a frozen scope, and the audit log outlives it.
 *
 * <p>What is kept is the shape of the refusals, because the surfaces above depend on it: a target
 * naming no status is a <b>409</b> (the caller asked for a state that does not exist, the same kind
 * of answer as asking for one that is not reachable), while an absent target is a <b>400</b> — a
 * malformed request rather than a refused move. A {@code type} that names nothing is a 400 instead,
 * and that is not an inconsistency: a type is a field being written, not a move being requested.
 *
 * <p><b>The two guards read {@link WorkEntity#status}, which is a {@code String}</b>, and compare it
 * against {@link EntityStatus#name()} rather than parsing it. That is deliberate: a guard's job is
 * to refuse, and a row whose status word is unreadable must be refused rather than blow up with a
 * different exception on the way to the refusal.
 */
final class EntityLifecycle {

  /**
   * What each status may move to, and <b>the one place the rule is written</b> — everything else in
   * this repository that has to describe an epic's or a ticket's moves points here rather than
   * restating them.
   *
   * <p><b>The pipeline is adjacent-only, in both directions.</b> REPORTED → REFINED → IMPLEMENTED →
   * VERIFIED → DONE is walked one step at a time, forward as each phase finishes and backward when
   * one has to be redone, and asking for the status the entity already has stays refused rather
   * than reading as a no-op.
   *
   * <p><b>{@link EntityStatus#DROPPED} is off that line.</b> It is not a sixth step and it has no
   * neighbours on the chain: it is reachable from every status that is not already closed —
   * REPORTED, REFINED, IMPLEMENTED and VERIFIED — because a decision not to do the work can be
   * taken at any point while the work is still open, and it is reached from nowhere else.
   *
   * <p><b>DONE is offered no drop</b>, and that absence is the decision rather than an oversight.
   * DONE is already an exit; the only thing the move could achieve is to let the weaker outcome
   * overwrite a real one, and an entity that shipped did not stop having shipped. A closure that was
   * wrong still goes back the way every move goes back — DONE → VERIFIED — and the entity is then
   * open again and droppable like any other.
   *
   * <p><b>DROPPED reopens to REPORTED and to nothing else</b>, which is what keeps this a graph
   * rather than a graph plus a column: resuming at wherever the entity was abandoned would mean
   * remembering where that was, and it is the wrong answer regardless — somebody who has changed
   * their mind about abandoned work is asking what it is for again, which is the refine phase.
   *
   * <p>Written out per status rather than derived from the ordinal, because the order is a fact
   * about the lifecycle and not about how the enum happens to be declared.
   */
  private static final Map<EntityStatus, Set<EntityStatus>> LEGAL_TARGETS =
      Map.of(
          EntityStatus.REPORTED,
          EnumSet.of(EntityStatus.REFINED, EntityStatus.DROPPED),
          EntityStatus.REFINED,
          EnumSet.of(EntityStatus.REPORTED, EntityStatus.IMPLEMENTED, EntityStatus.DROPPED),
          EntityStatus.IMPLEMENTED,
          EnumSet.of(EntityStatus.REFINED, EntityStatus.VERIFIED, EntityStatus.DROPPED),
          EntityStatus.VERIFIED,
          EnumSet.of(EntityStatus.IMPLEMENTED, EntityStatus.DONE, EntityStatus.DROPPED),
          EntityStatus.DONE,
          EnumSet.of(EntityStatus.VERIFIED),
          EntityStatus.DROPPED,
          EnumSet.of(EntityStatus.REPORTED));

  private EntityLifecycle() {}

  /** The status named by {@code value}, or empty when it names none. */
  static Optional<EntityStatus> parse(String value) {
    for (EntityStatus status : EntityStatus.values()) {
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
  static void requireTransition(Archetype archetype, EntityStatus from, EntityStatus target) {
    if (!LEGAL_TARGETS.getOrDefault(from, EnumSet.noneOf(EntityStatus.class)).contains(target)) {
      throw new ConflictException(
          subject(archetype) + " cannot move from " + from + " to " + target);
    }
  }

  /** "An epic" / "A ticket" — the start of a refusal's sentence. */
  private static String subject(Archetype archetype) {
    return archetype == Archetype.EPIC ? "An epic" : "A " + archetype.name().toLowerCase();
  }

  /**
   * The statuses in which an epic's work is over: {@link EntityStatus#IMPLEMENTED}, {@link
   * EntityStatus#VERIFIED}, {@link EntityStatus#DONE} and {@link EntityStatus#DROPPED}. Not the
   * same question as "what may this status move to" — every one of them still has a legal move,
   * backwards included — and resolved all the same. This is what an assembling layer asks before
   * tearing down anything the epic was still holding (its refinement room).
   */
  private static final Set<EntityStatus> RESOLVED =
      EnumSet.of(
          EntityStatus.IMPLEMENTED, EntityStatus.VERIFIED, EntityStatus.DONE, EntityStatus.DROPPED);

  /** Whether {@code status} says the epic's work is over — see {@link #RESOLVED}. */
  static boolean resolves(EntityStatus status) {
    return RESOLVED.contains(status);
  }

  /**
   * Rejects a structural change to an epic whose scope is no longer a draft: scope is editable at
   * {@link EntityStatus#REPORTED} and frozen from {@link EntityStatus#REFINED} on. The message names
   * the status the epic is in, the one the write needs, and the way back — the freeze is reversible
   * along the ordinary graph.
   */
  static void requireReported(WorkEntity epic) {
    if (!EntityStatus.REPORTED.name().equals(epic.status)) {
      throw new ConflictException(
          "The scope of epic "
              + epic.id
              + " is frozen: it is "
              + epic.status
              + ", and scope is editable only at REPORTED. Move the epic back to REPORTED to"
              + " reopen its scope.");
    }
  }

  /**
   * Rejects an implemented-marker change to an epic that is not being implemented: the markers move
   * only at {@link EntityStatus#REFINED}, the status implementation runs in.
   */
  static void requireRefined(WorkEntity epic) {
    if (!EntityStatus.REFINED.name().equals(epic.status)) {
      throw new ConflictException(
          "Implemented markers move only while an epic is REFINED (being implemented): epic "
              + epic.id
              + " is "
              + epic.status
              + ".");
    }
  }
}
