package eu.wohlben.qits.entities.control;

import eu.wohlben.qits.entities.entity.EntityStatus;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.entities.entity.WorkOutcome;
import eu.wohlben.qits.entities.error.BadRequestException;
import java.util.ArrayList;
import java.util.List;

/**
 * <b>Why a move into DROPPED closes the work, as a move states it</b> (qits-248): the {@link
 * WorkOutcome} and, for the three outcomes that name one, the item that answers for it.
 *
 * <p>The rules are stated here once and read by both doors that land DROPPED — the status move
 * ({@code WorkEntityService.move}) and the full-state transition ({@code EntityTransitionService}) —
 * so the two cannot drift. They are request rules, not a {@link TransitionGate}: a gate sees the
 * stored row and the mover, never what the request carries, and gates do not judge a DROP at all.
 *
 * <ul>
 *   <li><b>Every move into DROPPED carries an outcome.</b>
 *   <li><b>No other move carries one</b> — nor a {@code supersededBy}: both are written by the drop.
 *   <li><b>DUPLICATE, RESOLVED_ELSEWHERE and SUPERSEDED require {@code supersededBy}</b>; OBSOLETE,
 *       INVALID and WONT_DO refuse it.
 *   <li><b>The {@code supersededBy} names an item of the same project, and not the item itself</b>
 *       ({@link #referenceRefusal}, which needs the rows and so is asked inside the write).
 * </ul>
 *
 * <p>Each refusal is a 400 naming the rule it broke. A reopen clears both, which is the move's own
 * write rather than anything a caller states.
 *
 * @param outcome why the work is closed, or null when the move states none
 * @param supersededBy the id (a UUID — the doors resolve a qualified id before it gets here) of the
 *     item that answers for it, or null
 */
public record DropOutcome(WorkOutcome outcome, String supersededBy) {

  /** A move that states neither — every move that is not a drop. */
  public static final DropOutcome NONE = new DropOutcome(null, null);

  public DropOutcome {
    supersededBy = supersededBy == null || supersededBy.isBlank() ? null : supersededBy.trim();
  }

  /** The two values a drop states. */
  public static DropOutcome of(WorkOutcome outcome, String supersededBy) {
    return new DropOutcome(outcome, supersededBy);
  }

  /** Whether the move stated anything at all. */
  public boolean isEmpty() {
    return outcome == null && supersededBy == null;
  }

  /**
   * Every rule the stated values break for a move landing {@code target}, in a stable order — the
   * rules that need no row. Empty when the statement is legal.
   */
  public List<String> refusals(EntityStatus target) {
    List<String> refused = new ArrayList<>();
    if (target != EntityStatus.DROPPED) {
      if (outcome != null) {
        refused.add(
            "an outcome is recorded only with a move into DROPPED, and this move is to "
                + target
                + ": leave outcome out");
      }
      if (supersededBy != null) {
        refused.add(
            "supersededBy is recorded only with a move into DROPPED, and this move is to "
                + target
                + ": leave supersededBy out");
      }
      return refused;
    }
    if (outcome == null) {
      refused.add(
          "a move into DROPPED requires an outcome: one of "
              + String.join(", ", WorkOutcome.words())
              + " — DUPLICATE, RESOLVED_ELSEWHERE and SUPERSEDED with the supersededBy item that"
              + " answers for it");
      return refused;
    }
    if (outcome.namesSuccessor() && supersededBy == null) {
      refused.add(
          "outcome "
              + outcome
              + " requires supersededBy: the item (qualified id or UUID) "
              + what(outcome));
    } else if (!outcome.namesSuccessor() && supersededBy != null) {
      refused.add(
          "outcome "
              + outcome
              + " refuses supersededBy: it names no other item — use DUPLICATE, RESOLVED_ELSEWHERE"
              + " or SUPERSEDED to name one");
    }
    return refused;
  }

  /** {@link #refusals}, thrown as one 400 when there is any. */
  public void require(EntityStatus target) {
    List<String> refused = refusals(target);
    if (!refused.isEmpty()) {
      throw new BadRequestException(String.join("; ", refused));
    }
  }

  /**
   * The refusal of a {@code supersededBy} that names no item, an item of another project, or {@code
   * row} itself — or null when {@code named} is an acceptable reference. {@code resolved} is the row
   * the id names, or null when it names none.
   */
  public static String referenceRefusal(WorkEntity row, String named, WorkEntity resolved) {
    if (named == null) {
      return null;
    }
    if (resolved == null) {
      return "supersededBy names no work item: " + named;
    }
    if (resolved.id.equals(row.id)) {
      return "supersededBy names the item itself: an item cannot be closed in favour of itself";
    }
    if (!resolved.projectId.equals(row.projectId)) {
      return "supersededBy names "
          + named
          + ", which is in another project: an item is closed in favour of one of its own project";
    }
    return null;
  }

  private static String what(WorkOutcome outcome) {
    return switch (outcome) {
      case DUPLICATE -> "that asks for the same thing";
      case RESOLVED_ELSEWHERE -> "whose change resolved it";
      case SUPERSEDED -> "that replaced it";
      default -> "it names";
    };
  }
}
