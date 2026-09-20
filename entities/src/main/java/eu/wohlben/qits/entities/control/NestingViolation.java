package eu.wohlben.qits.entities.control;

import eu.wohlben.qits.entities.entity.Archetype;

/**
 * <b>One illegal membership in a post-state</b>, structured for the same reason {@link
 * ArchetypeViolation} is: the caller has a screen or a tool result to attach it to, and a sentence
 * would make every consumer a parser of English.
 *
 * <p>The entity named is the <b>child</b> — the one whose placement is wrong — because that is the
 * one a caller can move. A parent is never at fault for what somebody hung under it.
 *
 * @param entityId the child whose placement is refused
 * @param archetype what that child is (or is becoming) in the post-state
 * @param parentId what it was to hang under, or null when the complaint is that it stands alone
 * @param parentArchetype the parent's kind in the post-state, or null when there is no parent or the
 *     parent could not be resolved
 * @param reason what is wrong
 */
public record NestingViolation(
    String entityId,
    Archetype archetype,
    String parentId,
    Archetype parentArchetype,
    Reason reason) {

  /** What kind of thing is wrong with a membership. */
  public enum Reason {

    /**
     * The child's kind may not stand alone. Today that is a feature or a task with no parent — the
     * flag is declared per archetype rather than derived from depth, so widening it (a campaign) is
     * one word; see {@link Archetypes}.
     */
    NOT_A_ROOT,

    /**
     * The stated parent is neither in the post-state nor in the store. A separate answer from
     * "illegal nesting" on purpose: one is a tree that would be wrong and the other is a caller
     * naming something that is not there, and collapsing them would send somebody looking for a
     * depth rule that never fired.
     */
    UNKNOWN_PARENT,

    /**
     * The parent is not shallower than the child. That covers epic-under-epic, ticket-under-ticket,
     * ticket-under-epic (the two roots are the same depth, which is V4's "nothing joins the two
     * tables" as a rule rather than as prose) and task-under-task. <b>Skipping levels is not a
     * violation</b>: a task directly under an epic is strictly deeper and therefore legal, because
     * the rule is about containment and not about a fixed number of rungs.
     */
    NOT_NESTABLE,

    /**
     * The membership graph closes on itself. Under the depth rule this cannot happen — every edge
     * strictly increases depth, so a walk upwards terminates — and it is checked anyway because the
     * check is a bounded walk over facts already in hand, and because the one state where it
     * <em>could</em> arise is a post-state assembled by a caller that has not been validated yet.
     * A cycle found here means the depth rule was not applied or was applied to a different graph.
     */
    CYCLE
  }

  /** A readable sentence for a log line or a plain error body; the structure above is the contract. */
  public String message() {
    return switch (reason) {
      case NOT_A_ROOT -> "a " + archetype + " cannot stand on its own — it must be part of something";
      case UNKNOWN_PARENT -> "there is no " + parentId + " to be part of";
      case NOT_NESTABLE -> "a " + archetype + " cannot be part of a " + parentArchetype;
      case CYCLE -> "this would make " + entityId + " part of itself";
    };
  }
}
