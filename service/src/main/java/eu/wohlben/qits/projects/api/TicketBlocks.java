package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.entities.control.TicketService;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.entities.error.BadRequestException;
import eu.wohlben.qits.entities.error.ConflictException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * <b>Blocking and unblocking a ticket: the refusal, the write and the remark, once for both
 * doors.</b>
 *
 * <p>A block says the phase the ticket's <em>current</em> status starts cannot finish right now. It
 * is a flag and not a status ({@code WorkEntity.blocked} carries that argument), it is cleared by
 * every transition ({@code TicketService.transition}), and it is set here and nowhere else.
 *
 * <h2>Why this class exists rather than the rule being written at each door</h2>
 *
 * <p>Two surfaces perform this write — {@code entities/api/TicketController}'s route and {@code
 * mcp/TicketMcpTools}' two tools — exactly as two surfaces perform a transition, and a rule written
 * at both would be free to drift. What is written here is all of the rule: a reason is required to
 * block and not to unblock, only a status that starts a phase may be blocked, and what happened is
 * said on the thread. Each door keeps only what is genuinely its own — the agent binding, the hint
 * and the shape it answers.
 *
 * <h2>Why it is in {@code projects.api} and not in the entities module</h2>
 *
 * <p>Because the refusal is about a <b>phase</b>, and a phase is this layer's concept. {@link
 * TicketPhasePrompts} is the one place in this service that reads a status as the work that starts
 * from it, and {@link #requireBlockable} asks exactly that question rather than re-listing the
 * three statuses a phase runs under — so a fourth phase, or a status moving off the line, changes
 * one switch and this refusal follows it. The entities module cannot hold the rule at all: it has
 * no idea a phase exists and depends on {@code domain} nowhere, which is what keeps it liftable.
 * {@code TicketService.setBlocked} therefore writes the row and judges nothing, and says so.
 *
 * <p>That places this beside {@link TicketPhaseAdvance} and {@link TicketWorkspaces}, which are
 * here for the same reason and are the precedent: what a ticket's status <em>means for the work</em>
 * is decided in this package, and the row is written one module down.
 *
 * <h2>The reason is a comment and not a column</h2>
 *
 * <p>A blocker is a remark with an author and a time — which is what the thread already is — so it
 * lands through {@code TicketService.addComment} the way {@code TicketPhaseAdvance.say} lands what
 * became of a phase. A column would be a second place the same sentence lives, and it would go
 * stale the moment the thread moved past it. It is <b>required when blocking</b> because a block
 * with no stated blocker is one nobody can clear: the next reader is told the work stopped and not
 * what would restart it. It is optional when unblocking, where the ticket simply resumes and there
 * may be nothing to add.
 *
 * <p><b>The comment is written before the row is, and never after.</b> A comment that failed would
 * otherwise leave a blocked ticket with no stated blocker, which is the one state this door exists
 * to prevent; a row write that failed after a comment leaves an unblocked ticket carrying a remark,
 * which is merely a remark. The comment is also what makes the refusals worth ordering: both run
 * before either write, so a ticket refused has had nothing written on it.
 */
@ApplicationScoped
public class TicketBlocks {

  @Inject TicketService tickets;

  /**
   * Blocks or unblocks {@code ticket}, records why on its thread, and answers the row as it now
   * stands.
   *
   * @param ticket the ticket as resolved by the door — already known to exist and already bound to
   *     the caller, because a refusal about a row the caller may not see is a refusal that reports
   *     on it
   * @param blocked what the flag should become
   * @param reason why; required when {@code blocked} is true, optional otherwise
   * @param changedBy the caller, resolved by the surface that took the call — passed in for the
   *     reason {@link TicketPhaseAdvance#afterTransition} takes it, since the two surfaces answer
   *     an unnamed caller differently
   */
  public WorkEntity apply(WorkEntity ticket, boolean blocked, String reason, String changedBy) {
    String stated = reason == null ? "" : reason.trim();
    if (blocked && stated.isEmpty()) {
      throw new BadRequestException(
          "A blocked ticket needs a stated blocker: say what is in the way, so somebody can clear"
              + " it.");
    }
    if (blocked) {
      requireBlockable(ticket);
    }
    tickets.addComment(ticket.id, remark(blocked, stated), changedBy);
    return tickets.setBlocked(ticket.id, blocked, changedBy);
  }

  /**
   * <b>409 unless a phase runs while this ticket's status holds.</b> {@link
   * TicketPhasePrompts#startedBy} is the whole test and the whole vocabulary: a status it answers
   * empty for starts no phase, so there is nothing for a block to be about. That is VERIFIED and
   * DONE — the work is over and what is left is a person's judgement — and DROPPED, where the work
   * was decided against and no phase will ever run again.
   *
   * <p>The message names what is missing rather than the status alone, because "409" on a ticket
   * that plainly exists leaves the caller guessing whether the block was rejected or the ticket
   * was. A caller that genuinely cannot proceed on a VERIFIED ticket has a transition to make, not
   * a flag to set.
   *
   * <p>Only the <em>blocking</em> direction is refused. Unblocking a ticket whose status starts no
   * phase is a no-op on the flag and is allowed: the only way to reach that state is a ticket that
   * was blocked and then transitioned — which already cleared it — so an unblock there asks for
   * something that is already true, and refusing it would mean refusing to tidy up a state this
   * door can produce.
   */
  static void requireBlockable(WorkEntity ticket) {
    if (TicketPhasePrompts.startedBy(ticket).isEmpty()) {
      throw new ConflictException(
          "Ticket "
              + ticket.id
              + " is "
              + ticket.status
              + ", so no phase is running and there is nothing to block — a block says the work"
              + " that runs now cannot finish, and no work runs while this status holds.");
    }
  }

  /**
   * What lands on the thread. It names the flag in the first words, because the thread is read for
   * what happened to the ticket and a blocker buried mid-sentence reads as ordinary commentary.
   */
  private static String remark(boolean blocked, String stated) {
    if (blocked) {
      return "Blocked: " + stated;
    }
    return stated.isEmpty() ? "Unblocked." : "Unblocked: " + stated;
  }
}
