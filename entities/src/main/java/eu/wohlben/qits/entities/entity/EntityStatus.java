package eu.wohlben.qits.entities.entity;

/**
 * The lifecycle of every archetype that has one — {@link Archetype#EPIC} and {@link
 * Archetype#TICKET} alike. Stored as the enum name in {@link WorkEntity#status}, spelled by {@code
 * ck_entity_status} (epics V15), and moved only through a transition — see {@code EntityLifecycle}
 * for the guards and {@code EntityStateMachine} for which moves are legal.
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
 * <p><b>A status says what has been ACHIEVED, or a fact the platform recorded — never a claim
 * somebody keeps up to date by hand.</b> That is what keeps these eight words from drifting into a
 * task board: there is no {@code IN_PROGRESS} that a person sets and forgets, because "somebody is
 * working on it", kept by hand, is out of date the moment it is written. {@link #IMPLEMENTING} and
 * {@link #VERIFYING} are not that word, and they are the two statuses that name something under way
 * (qits-749): <b>the platform sets them</b> — at the dispatch press that starts the implement or the
 * verify phase, on the FLOW hand-off that delivers that phase's turn, and (IMPLEMENTING only) at an
 * agent's first {@code mark_task_implementing} — so nobody keeps them current, and what they record
 * — an implementation, a verification, was started — does not go stale. Each phase leaves its
 * "-ING" status by the same transition it always made, to {@link #IMPLEMENTED} or to {@link
 * #VERIFIED}. Both are skippable: REFINED → IMPLEMENTED and IMPLEMENTED → VERIFIED stay legal moves
 * (SKIPs in {@code EntityStateMachine}), so work an agent finished without ever being marked started
 * is not stranded.
 *
 * <p><b>Entering a status starts the phase that belongs to it.</b> The two halves are the same
 * line read from either end: a status is entered by the phase that produced it, and it is held
 * while the next one runs. {@link #REPORTED} means somebody said what is wrong, so the refine phase
 * runs; {@link #REFINED} means the entity says what to do, so implement runs; {@link #IMPLEMENTING}
 * means that implementation was started, so implement keeps running (a dispatch resumes it); {@link
 * #IMPLEMENTED} means the change is released and deployed, so verify runs; {@link #VERIFYING} means
 * that verification was started, so verify keeps running (a dispatch resumes it); {@link #VERIFIED} means
 * it no longer occurs on the platform, so a person closes it; {@link #DONE} means closed. So reading
 * the status tells you both what is true and what happens next.
 *
 * <p><b>{@link #DROPPED} is the one word not on that line</b>, and it starts nothing: it does not
 * say a phase finished, it says the phases stopped. Everything above is about work that is moving
 * forward or being redone; this is the exit for work that is neither, and where it may be reached
 * from is {@code EntityStateMachine}'s to say rather than this file's.
 *
 * <p><b>{@link #DONE} is the one terminal status, and it has no exits</b> — not back to {@link
 * #VERIFIED}, not to {@link #DROPPED}. A done development that later turns out wrong is a new
 * ticket or epic, which may refer to the done one. Below DONE every move is reversible, and a move
 * back corrects a claim that turned out wrong; it is not how a phase reports failure — a phase that
 * cannot finish, a failed verification included, blocks the entity where it stands (qits-592).
 * {@link #DROPPED} reopens, to {@link #REPORTED}. The
 * states and every legal move between them are declared once, as a state machine, in {@code
 * EntityStateMachine}.
 *
 * <p><b>What the words freeze is per archetype, and only an epic freezes anything.</b> An epic's
 * scope (title, description, features, tasks) is editable at {@link #REPORTED} and frozen from
 * {@link #REFINED} on; its task markers (implementing, implemented) move only while it is {@link
 * #REFINED} or {@link #IMPLEMENTING}. Moving the epic back
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

  /**
   * An implementation was started — the platform moved it here at the dispatch press, or an agent
   * marked its first task implementing. The implement phase runs, and leaves it through the move to
   * {@link #IMPLEMENTED}. Skippable: REFINED → IMPLEMENTED is still a legal move.
   */
  IMPLEMENTING,

  /** The change is released and deployed. Verify runs — nobody has confirmed it yet. */
  IMPLEMENTED,

  /**
   * A verification was started — the platform moved it here at the dispatch press that started the
   * verify phase, or on the FLOW hand-off that delivered its turn. The verify phase runs, and leaves
   * it through the move to {@link #VERIFIED}. Skippable: IMPLEMENTED → VERIFIED is still a legal
   * move. No feature or task marker mirrors it.
   */
  VERIFYING,

  /**
   * It no longer occurs on the platform. What runs is a person's judgement that there is nothing
   * left on the thread; a failed verification never lands here, it blocks where it stands ({@link #VERIFYING}, or
   * {@link #IMPLEMENTED} when nobody moved it).
   */
  VERIFIED,

  /**
   * Closed, and final: the one terminal status, with no exits at all. A follow-up to done work is a
   * new entity.
   */
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
   * <p>Not terminal: it moves back to {@link #REPORTED}, because reviving work that was
   * abandoned means asking again what it is for, and that is the refine phase.
   */
  DROPPED
}
