package eu.wohlben.qits.entities.entity;

/**
 * The state a {@link Archetype#TICKET} entity is in. Stored as the enum name (V4, widened in V7) in
 * {@link WorkEntity#status}, and moved only
 * through {@code TicketService.transition} — see {@code TicketLifecycle} for which moves are legal.
 *
 * <p><b>A status says what has been ACHIEVED, never what is being done.</b> That is the whole
 * reading of these six words, and it is what keeps them from drifting into a task board: there is
 * no {@code IN_PROGRESS} here and there must never be one, because "somebody is working on it" is a
 * fact about a person rather than about the ticket, it is out of date the moment it is written, and
 * the platform already answers it — the workspaces a ticket is worked in are read per request and
 * shown beside the status.
 *
 * <p><b>Entering a status starts the phase that belongs to it.</b> The two halves are the same
 * line read from either end: a status is entered by the phase that produced it, and it is held
 * while the next one runs. {@link #REPORTED} means somebody said what is wrong, so the refine phase
 * runs; {@link #REFINED} means the ticket says what to do, so implement runs; {@link #IMPLEMENTED}
 * means the change is released and deployed, so verify runs; {@link #VERIFIED} means it no longer
 * occurs on the platform, so a person closes it; {@link #DONE} means closed. So reading the status
 * tells you both what is true and what happens next, which is why a phase never needs a status of
 * its own.
 *
 * <p><b>{@link #DROPPED} is the one word not on that line</b>, and it starts nothing: it does not
 * say a phase finished, it says the phases stopped. Everything above is about work that is moving
 * forward or being redone; this is the exit for work that is neither, and where it may be reached
 * from is {@code TicketLifecycle}'s to say rather than this file's.
 *
 * <p><b>Nothing is terminal.</b> {@link #DONE} reopens to {@link #VERIFIED} exactly as every other
 * move goes back, and there is no reject verb anywhere in the lifecycle: a verification that fails
 * is the ordinary backward move {@link #IMPLEMENTED} → {@link #REFINED}. {@link #DROPPED} reopens
 * too, to {@link #REPORTED}. The alternative to a status that reopens is a second row saying the
 * same thing.
 */
public enum TicketStatus {

  /**
   * Somebody said what is wrong or what could be better, and nothing more — the ticket has an
   * impetus and no refined description yet. New tickets start here, and the refine phase runs.
   */
  REPORTED,

  /** The ticket says what to do: the description is the refinement's output. Implement runs. */
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
   * A decision was taken not to do this work. Nothing about the ticket was implemented and nothing
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
