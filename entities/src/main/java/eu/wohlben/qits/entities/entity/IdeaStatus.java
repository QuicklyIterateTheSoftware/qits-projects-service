package eu.wohlben.qits.entities.entity;

/**
 * <b>Where an idea stands, derived from the work that names it</b> (qits-1387) — never written by a
 * caller. {@code control/IdeaStatusRecompute} is the one place the rule is stated:
 *
 * <ul>
 *   <li>{@link #PLANNED} — no counted work item names the idea as its {@code inspiredBy};
 *   <li>{@link #QUEUED} — at least one counted item does, and not every one of them is DONE;
 *   <li>{@link #DONE} — every counted item is DONE. VERIFIED is not enough.
 * </ul>
 *
 * A DROPPED work item is not counted at all: an idea whose only work was dropped is PLANNED again.
 */
public enum IdeaStatus {
  PLANNED,
  QUEUED,
  DONE
}
