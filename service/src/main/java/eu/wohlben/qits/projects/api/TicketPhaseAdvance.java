package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.epics.control.TicketService;
import eu.wohlben.qits.epics.entity.Ticket;
import eu.wohlben.qits.epics.entity.TicketStatus;
import eu.wohlben.qits.projects.control.WorkspaceAgentTurns;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * The next phase starts itself: a ticket that just moved has the turn its <b>new</b> status begins
 * delivered into the workspace already standing on its branch, and the thread is told what happened.
 *
 * <h2>The transition is the whole trigger</h2>
 *
 * <p>A ticket's three phases run in one workspace, separated by a context reset rather than by a
 * container ({@link WorkspaceAgentTurns}). So the moment an agent claims a phase — its own {@code
 * transition_ticket} call — is the moment this service knows something that workspace does not, and
 * this class is what says it. Nobody presses anything between phases: refine ends, the ticket is
 * REFINED, and the implement turn arrives in the session the agent is already in. Pressing "Assign
 * agent" stays the way to <em>resume</em> a ticket nobody is working on; it stops being the way to
 * continue one that is.
 *
 * <p>It hangs off the transition and off nothing else. Not off assignment, not off a comment, not
 * off a release — those say something about a ticket without saying that a phase ended, and a phase
 * started from any of them would be started from a claim nobody made.
 *
 * <h2>One rule, and it is {@link TicketPhasePrompts} unchanged</h2>
 *
 * <p><b>The prompt for a status is the work that starts from it</b>, which is exactly what {@link
 * TicketPhasePrompts#startedBy(Ticket)} already computes for the dispatch door. This class adds no
 * second table and no second switch: it reads that one, and everything else follows from it.
 *
 * <p><b>Direction is deliberately not consulted.</b> The ticket's new status is the entire input, so
 * a failed verification moving IMPLEMENTED → REFINED gets the <em>implement</em> turn — which is
 * precisely right, because REFINED means the ticket says what to do and implementing is what runs
 * next — and a person reopening a DONE ticket to VERIFIED gets nothing, which is also right, because
 * VERIFIED starts no phase at all. A rule that asked "forward or back?" would need a second table to
 * answer from, and the second table is the thing that goes wrong.
 *
 * <p>{@link TicketStatus#VERIFIED} and {@link TicketStatus#DONE} therefore end the flow at its first
 * line, and that is where the one remaining human decision lives: closing a ticket is a person's
 * move, so nothing is delivered and nothing is written on a transition into either.
 *
 * <h2>Why this is not on {@code TicketService}</h2>
 *
 * <p>Because the epics module has no idea what a workspace is, and must keep not having one. {@code
 * epics/} depends on neither {@code domain} nor anything framework-shaped; it owns its database, its
 * errors and its lineage, and it is the module most likely to be lifted out of this repository next.
 * Putting this on {@code TicketService} would make the lifecycle itself depend on {@code
 * control/WorkspaceAgentTurns}, on the project, on the wrapper repository and thereby on the whole
 * catalog — the epics jar would carry a workspace concept into any service that ever reused it, and
 * the lift-out would stop being a database move.
 *
 * <p>There is a second reason, and it survives even if the modules were one. {@code
 * TicketService.transition} is the <b>recording</b> of a fact, held under the module's write
 * patience; delivery is an outward call to another service that may hang, refuse or be absent. A
 * transaction that had to wait on qits-workspaces before it could commit would let an unreachable
 * sibling fail a move that has already happened in every sense that matters, and a transaction that
 * called out and then rolled back would have spoken about a status no row ever held. Separating them
 * is what makes "the transition is recorded first and can never be undone by a delivery" a
 * structural property instead of a promise. Both call sites are non-transactional and call this
 * <em>after</em> the service returns, exactly as they already fire their change hints.
 *
 * <h2>It lives in {@code projects.api} for {@link TicketDispatchController}'s reason</h2>
 *
 * <p>It needs {@code domain} — the project, the wrapper and the port — and the epics jar depends on
 * {@code domain} nowhere. The <em>service</em> layer may cross, which is the crossing {@code
 * ProjectTicketsController} already makes, and the package name is where that crossing is declared.
 * It is {@code public} for one narrow reason: both transition surfaces are outside this package
 * ({@code eu.wohlben.qits.epics.api.TicketController} and {@code
 * eu.wohlben.qits.projects.mcp.TicketMcpTools}), and the alternative — a copy per surface — is the
 * drift this class exists to prevent. {@link TicketPhasePrompts} stays package-private and is read
 * from here, which is the whole reason this class is in that package rather than beside either
 * caller.
 *
 * <h2>What lands on the thread, and what deliberately does not</h2>
 *
 * <p>Every failure is a WARN and a sentence on the thread, because <b>that comment is the only place
 * a reader learns whether an agent is now working</b>. The sentence names the phase that was started
 * — from {@link TicketPhasePrompts#startedBy}, so the words and the naming come from one switch —
 * and where nothing was delivered it says so with the reason and <b>claims nothing about an
 * agent</b>: a thread saying work resumed when it did not is worse than a thread saying nothing.
 *
 * <p><b>A ticket with no workspace gets no comment at all.</b> {@link
 * WorkspaceAgentTurns.Outcome#NO_WORKSPACE} is not a failure — the delivery door never creates a
 * workspace, so it is the ordinary answer for a ticket nobody has dispatched an agent onto — and a
 * person walking a ticket through the statuses by hand would otherwise have their thread filled with
 * "there was nobody to tell", once per move. The same silence covers a project with no wrapper
 * repository ({@link TicketWorkspaces#find}) and an assembly with no {@link WorkspaceAgentTurns}
 * implementation at all: in all three there is no workspace to speak to, nothing was asked of
 * anybody, and nothing is said.
 *
 * <p>The {@code TICKETS} hint is fired <b>only where a comment was written</b>, which is the rule the
 * rest of this code follows — a hint announces that something changed, and on the silent paths
 * nothing did. The transition's own hint has already gone out from the caller by then, so an open
 * browser has seen the status move regardless.
 */
@ApplicationScoped
public class TicketPhaseAdvance {

  private static final Logger LOG = Logger.getLogger(TicketPhaseAdvance.class);

  @Inject TicketService tickets;

  @Inject TicketWorkspaces workspaces;

  @Inject ProjectChangePublisher publisher;

  /**
   * Optional, like every port here, and <b>absent is a supported configuration</b>: nothing is
   * asked, nothing is said, and every transition behaves exactly as it did before this class
   * existed. See the class javadoc for why that silence is the same silence as a ticket with no
   * workspace, rather than a comment about missing configuration a reader could do nothing with.
   */
  @Inject Instance<WorkspaceAgentTurns> turns;

  /**
   * Start the phase the ticket's new status begins, in the workspace standing on its branch, and say
   * on the thread what happened.
   *
   * <p><b>Never throws</b>, for the reason the port does not: it is called after a transition that is
   * already recorded, and nothing here may turn a move that happened into an error for the caller.
   * Both call sites wrap it anyway; that belt is theirs and this one is ours.
   *
   * <p><b>The signature takes {@code changedBy}, where the epic wrote {@code
   * afterTransition(Ticket)}.</b> The comment is stamped from the caller's identity exactly as the
   * transition itself is, and the two surfaces do not resolve identity the same way: {@code
   * EpicsPrincipal.changedBy} answers {@code null} for an unnamed caller, while {@code
   * TicketMcpTools.changedBy()} answers its own {@code AGENT} fallback, because a tool call arriving
   * without a forwarded user is still an agent doing the work. Injecting {@code SecurityIdentity}
   * here would silently pick the first of those for both surfaces, so the thread would attribute an
   * agent's own hand-off to nobody. Passing the value in means the stamp on this comment is the same
   * string the transition's audit row carries, whichever door it came through, and it keeps this bean
   * out of the request context entirely.
   *
   * @param ticket the ticket <b>as it is after the move</b> — the new status is the only input to
   *     which phase starts
   * @param changedBy the caller, resolved by the surface that took the transition; may be null
   */
  public void afterTransition(Ticket ticket, String changedBy) {
    Optional<TicketPhasePrompts.Started> started = TicketPhasePrompts.startedBy(ticket);
    if (started.isEmpty()) {
      // VERIFIED and DONE: the work is over and closing is a person's move. Nothing to do, and
      // nothing to say about having done nothing.
      return;
    }
    if (turns.isUnsatisfied()) {
      return;
    }
    Optional<TicketWorkspaces.Target> target = workspaces.find(ticket);
    if (target.isEmpty()) {
      LOG.warnf(
          "Ticket %s moved to %s but its project (%s) has no wrapper repository, so there is no"
              + " branch to start the %s phase on",
          ticket.id, ticket.status, ticket.projectId, started.get().phase());
      return;
    }
    deliver(ticket, started.get(), target.get(), changedBy);
  }

  /**
   * The turn, and the one sentence that follows it. Split out so the method above reads as the rule
   * it implements — status picks a phase, a phase needs an address — and this one as what is done
   * with the answer.
   */
  private void deliver(
      Ticket ticket,
      TicketPhasePrompts.Started started,
      TicketWorkspaces.Target target,
      String changedBy) {
    String branch = target.branch();
    WorkspaceAgentTurns.Turn turn;
    try {
      turn = turns.get().deliver(target.repositoryId(), branch, started.instruction());
    } catch (RuntimeException e) {
      // The port says it must not throw; a throw is a port bug and must not touch a transition that
      // has already been recorded. The thread still gets the honest sentence, because a reader
      // asking "is anything working on this?" is owed an answer either way.
      LOG.warnf(
          e,
          "Could not start the %s phase of ticket %s on %s: the delivery port threw",
          started.phase(),
          ticket.id,
          branch);
      turn =
          new WorkspaceAgentTurns.Turn(
              WorkspaceAgentTurns.Outcome.COULD_NOT, "the delivery failed unexpectedly");
    }

    if (turn.outcome() == WorkspaceAgentTurns.Outcome.NO_WORKSPACE) {
      // The ordinary answer for a ticket nobody dispatched an agent onto. Said in the log for
      // somebody debugging a hand-off, and nowhere else — see the class javadoc.
      LOG.debugf(
          "No workspace stands on %s, so ticket %s starts its %s phase when somebody dispatches one",
          branch, ticket.id, started.phase());
      return;
    }

    if (turn.spoken()) {
      LOG.infof(
          "Started the %s phase of ticket %s (%s) in the workspace on %s (%s)",
          started.phase(), ticket.id, ticket.slug, branch, turn.outcome());
    } else {
      LOG.warnf(
          "Could not start the %s phase of ticket %s (%s) on %s: %s",
          started.phase(), ticket.id, ticket.slug, branch, turn.detail());
    }
    say(ticket, comment(ticket, started.phase(), branch, turn), changedBy);
  }

  /**
   * What the thread is told, one sentence per outcome.
   *
   * <p>The two spoken arms are kept apart rather than folded into "the turn was delivered", because
   * the difference is the one a reader of the thread is actually asking about: an agent that was
   * <em>already there</em> carried straight on, while one that had to be <b>launched</b> started from
   * a cold session — and if a phase produced nothing, which of the two happened is the first thing
   * worth knowing.
   *
   * <p>The refusal arm names the phase that did <em>not</em> start, carries the far side's own
   * reason, and says nothing whatsoever about an agent. It ends by naming the status the ticket now
   * holds, because that is what makes the sentence actionable: the move stands, and the phase is
   * started by pressing the ticket's own button.
   */
  private static String comment(
      Ticket ticket, String phase, String branch, WorkspaceAgentTurns.Turn turn) {
    return switch (turn.outcome()) {
      case DELIVERED ->
          "Started the "
              + phase
              + " phase: the agent working in the workspace on `"
              + branch
              + "` was told.";
      case LAUNCHED ->
          "Started the "
              + phase
              + " phase: no agent was running in the workspace on `"
              + branch
              + "`, so one was launched to take it.";
      default ->
          "Could not start the "
              + phase
              + " phase"
              + (turn.detail().isBlank() ? "" : ": " + turn.detail())
              + ". The ticket is "
              + ticket.status
              + " and nothing is running on it.";
    };
  }

  /**
   * The comment, and the redraw that goes with it. Wrapped for the same reason the port's own call
   * is: this runs after a recorded transition, and a ticket store that refused a comment must not
   * reach the caller as a failure of a move that already happened. The hint is fired here and only
   * here, which is what makes "a hint only where something was written" true by construction.
   */
  private void say(Ticket ticket, String body, String changedBy) {
    try {
      tickets.addComment(ticket.id, body, changedBy);
      publisher.fire(ticket.projectId, ProjectChangeHint.Topic.TICKETS);
    } catch (RuntimeException e) {
      LOG.warnf(e, "Could not say on ticket %s's thread what became of its next phase", ticket.id);
    }
  }
}
