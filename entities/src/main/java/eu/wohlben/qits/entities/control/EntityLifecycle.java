package eu.wohlben.qits.entities.control;

import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.EntityStatus;
import eu.wohlben.qits.entities.entity.TicketType;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.entities.error.ConflictException;
import java.util.Optional;

/**
 * The lifecycle rules of every archetype that has a status — epic and ticket alike — in one place
 * over one enum, {@link EntityStatus}. Until qits-392 there were two classes here, {@code
 * EpicLifecycle} and {@code TicketLifecycle}, over two enums; the ticket's reversible graph is the
 * one that survived, and the epic's two freeze guards were re-expressed in its words rather than
 * kept beside it.
 *
 * <p><b>The graph is the same for every archetype, and it is not written here.</b> It is {@link
 * EntityStateMachine}, the one explicit declaration of the states and their moves; {@link
 * #requireTransition} asks it for an epic exactly as it does for a ticket, and only the sentence a
 * refusal is phrased in names the kind. The guards below are this class's own: they are about which
 * fields a status still permits, not about which moves it permits.
 *
 * <p><b>What freezes is not the same, and only an epic freezes anything.</b> An epic carries a
 * scope that is committed to, so its status decides which fields may still be written:
 *
 * <ul>
 *   <li>a structural change — the epic's title or description, and any feature/task create, update
 *       or delete, dependencies included — needs {@link EntityStatus#REPORTED} ({@link
 *       #requireReported});
 *   <li>the task markers — implemented ({@code implementedOn}/{@code implementedAt}) and, since
 *       qits-749, implementing ({@code implementingOn}/{@code implementingAt}) — need the epic being
 *       implemented: {@link EntityStatus#REFINED} or {@link EntityStatus#IMPLEMENTING} ({@link
 *       #requireBeingImplemented}). IMPLEMENTING is included because an implementing agent marks
 *       its tasks while its epic is IMPLEMENTING.
 * </ul>
 *
 * The two guards therefore reject everything from IMPLEMENTED on without a rule of their own. The
 * freeze is <b>reversible in the ordinary way below DONE</b>: an epic whose scope has to change moves
 * back to REPORTED along the graph, and nobody needs a new door for it. A DONE epic's scope is frozen
 * for good, because DONE is final — a follow-up is a new epic. A ticket carries one small thing, so a
 * DONE ticket is still editable and still commentable: there is no {@code requireOpen} for its
 * fields. Its <em>status</em> is final like any DONE entity's.
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
 * against {@link EntityStatus#name()} rather than {@code valueOf}-ing it. That is deliberate: a
 * guard's job is to refuse, and a row whose status word is unreadable must be refused rather than
 * blow up with a different exception on the way to the refusal ({@link #requireReported} reads the
 * word through {@link #parse} only to choose the wording of its refusal).
 */
final class EntityLifecycle {

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
   * Rejects a move the lifecycle does not allow, naming both ends — and, for a move out of DONE,
   * saying that DONE is final and that a follow-up is a new ticket or epic. Which moves are legal is
   * {@link EntityStateMachine}'s to say and is argued there, because a rule written twice is a rule
   * that drifts; this adds only the subject of the sentence.
   *
   * <p>There is no reject verb either. A move back corrects a claim that turned out wrong; a
   * verification that fails is not one — it blocks the entity at IMPLEMENTED with what still occurs,
   * and a person decides what follows (qits-592).
   */
  static void requireTransition(Archetype archetype, EntityStatus from, EntityStatus target) {
    EntityStateMachine.refusal(archetype, from, target)
        .ifPresent(
            reason -> {
              throw new ConflictException(subject(archetype) + " " + reason);
            });
  }

  /** "An epic" / "A ticket" — the start of a refusal's sentence. */
  private static String subject(Archetype archetype) {
    return archetype == Archetype.EPIC ? "An epic" : "A " + archetype.name().toLowerCase();
  }

  /**
   * Whether {@code status} says an epic's work is over: IMPLEMENTED or beyond on the walk, or
   * DROPPED. Not the same question as "what may this status move to" — IMPLEMENTED and VERIFIED
   * still move backwards — and resolved all the same. This is what an assembling layer asks before
   * tearing down anything the epic was still holding (its refinement room). Read off {@link
   * EntityStateMachine} rather than listed, so it cannot disagree with the walk.
   */
  static boolean resolves(EntityStatus status) {
    return EntityStateMachine.isOffWalk(status)
        || EntityStateMachine.isAtOrPast(status, EntityStatus.IMPLEMENTED);
  }

  /**
   * Rejects a structural change to an epic whose scope is no longer a draft: scope is editable at
   * {@link EntityStatus#REPORTED} and frozen from {@link EntityStatus#REFINED} on. The message names
   * the status the epic is in, the one the write needs, and the way back — the freeze is reversible
   * along the ordinary graph, except at DONE, which is final: there the message says so instead of
   * pointing at a way back that does not exist.
   */
  static void requireReported(WorkEntity epic) {
    if (!EntityStatus.REPORTED.name().equals(epic.status)) {
      Optional<EntityStatus> status = parse(epic.status);
      if (status.isPresent() && EntityStateMachine.isTerminal(status.get())) {
        throw new ConflictException(
            "The scope of epic "
                + epic.id
                + " is frozen for good: "
                + EntityStateMachine.finality(status.get())
                + ".");
      }
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
   * Rejects a task-marker change (implemented or implementing) to an epic that is not being
   * implemented: the markers move only at {@link EntityStatus#REFINED} or {@link
   * EntityStatus#IMPLEMENTING}, the two statuses the implement phase runs in. (It was {@code
   * requireRefined} until qits-749 put IMPLEMENTING between REFINED and IMPLEMENTED.)
   */
  static void requireBeingImplemented(WorkEntity epic) {
    if (!EntityStatus.REFINED.name().equals(epic.status)
        && !EntityStatus.IMPLEMENTING.name().equals(epic.status)) {
      throw new ConflictException(
          "Task markers move only while an epic is REFINED or IMPLEMENTING (being implemented):"
              + " epic "
              + epic.id
              + " is "
              + epic.status
              + ".");
    }
  }
}
