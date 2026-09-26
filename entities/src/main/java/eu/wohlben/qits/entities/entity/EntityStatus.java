package eu.wohlben.qits.entities.entity;

/**
 * The lifecycle of every archetype that has one — {@link Archetype#EPIC} and {@link
 * Archetype#TICKET} alike. Stored as the enum name in {@link WorkEntity#status}, spelled by {@code
 * ck_entity_status} (epics V15), and moved only through a transition — see {@code EntityLifecycle}
 * for which moves are legal.
 *
 * <p><b>It was the ticket's vocabulary, and it is the only one now.</b> Epics had a lifecycle of
 * their own ({@code EpicStatus}: {@code REFINING → IMPLEMENTATION → IMPLEMENTED → SUPERSEDED |
 * ABANDONED}) until qits-392 deleted it and V15 backfilled every epic row onto these words:
 * REFINING became {@link #REPORTED}, IMPLEMENTATION became {@link #REFINED}, IMPLEMENTED stayed, and
 * both ABANDONED and SUPERSEDED became {@link #DROPPED} — a superseded epic is a DROPPED one whose
 * {@code superseded_by_entity_id} names the successor, which is what the word used to say twice.
 * What that bought is the point of the change: an epic can now be {@link #VERIFIED} and {@link
 * #DONE}, which it could not before.
 *
 * <p><b>A status says what has been ACHIEVED, never what is being done.</b> That is the whole
 * reading of these six words, and it is what keeps them from drifting into a task board: there is
 * no {@code IN_PROGRESS} here and there must never be one, because "somebody is working on it" is a
 * fact about a person rather than about the entity, it is out of date the moment it is written, and
 * the platform already answers it — the workspaces an entity is worked in are read per request and
 * shown beside the status.
 *
 * <p><b>Entering a status starts the phase that belongs to it.</b> The two halves are the same
 * line read from either end: a status is entered by the phase that produced it, and it is held
 * while the next one runs. {@link #REPORTED} means somebody said what is wrong, so the refine phase
 * runs; {@link #REFINED} means the entity says what to do, so implement runs; {@link #IMPLEMENTED}
 * means the change is released and deployed, so verify runs; {@link #VERIFIED} means it no longer
 * occurs on the platform, so a person closes it; {@link #DONE} means closed. So reading the status
 * tells you both what is true and what happens next, which is why a phase never needs a status of
 * its own.
 *
 * <p><b>{@link #DROPPED} is the one word not on that line</b>, and it starts nothing: it does not
 * say a phase finished, it says the phases stopped. Everything above is about work that is moving
 * forward or being redone; this is the exit for work that is neither, and where it may be reached
 * from is {@code EntityLifecycle}'s to say rather than this file's.
 *
 * <p><b>Nothing is terminal.</b> {@link #DONE} reopens to {@link #VERIFIED} exactly as every other
 * move goes back, and there is no reject verb anywhere in the lifecycle: a verification that fails
 * is the ordinary backward move {@link #IMPLEMENTED} → {@link #REFINED}. {@link #DROPPED} reopens
 * too, to {@link #REPORTED}. The alternative to a status that reopens is a second row saying the
 * same thing.
 *
 * <p><b>What the words freeze is per archetype, and only an epic freezes anything.</b> An epic's
 * scope (title, description, features, tasks) is editable at {@link #REPORTED} and frozen from
 * {@link #REFINED} on; its implemented markers move only at {@link #REFINED}. Moving the epic back
 * to {@link #REPORTED} is how a frozen scope is reopened. A ticket freezes nothing.
 */
public enum EntityStatus {

  /**
   * Somebody said what is wrong or what could be better, and nothing more — the entity has
   * nothing refined yet (a ticket: an impetus and no refined description; an epic: a draft scope).
   * New tickets and new epics start here, and the refine phase runs.
   */
  REPORTED,

  /** The entity says what to do: the description is the refinement's output. Implement runs. */
  REFINED,

  /** The change is released and deployed. Verify runs — nobody has confirmed it yet. */
  IMPLEMENTED,

  /**
   * It no longer occurs on the platform. What runs is a person's judgement that there is nothing
   * left on the thread; a failed verification never lands here, it goes back to {@link #REFINED}.
   */
  VERIFIED,

  /** Closed. Not terminal: it moves back to {@link #VERIFIED} like any other status. */
  DONE,

  /**
   * A decision was taken not to do this work. Nothing about the entity was implemented and nothing
   * was verified, and nothing is expected to be — which is the whole claim, and why it is not
   * {@link #DONE} under another name: DONE says the thing was dealt with, this says it will not be.
   *
   * <p>It exists because the alternative is worse than untidy. Without it, work somebody decided
   * against stays wherever it was abandoned — most often {@link #REFINED}, which is exactly the
   * word the listing surfaces advertise as ready to be picked up, so the next agent asked to take
   * on the outstanding work picks up the one thing that was ruled out.
   *
   * <p>Not terminal either: it moves back to {@link #REPORTED}, because reviving work that was
   * abandoned means asking again what it is for, and that is the refine phase.
   */
  DROPPED
}
