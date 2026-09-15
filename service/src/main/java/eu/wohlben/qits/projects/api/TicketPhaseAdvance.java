package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.epics.control.TicketService;
import eu.wohlben.qits.epics.control.WorkBranches;
import eu.wohlben.qits.epics.entity.Ticket;
import eu.wohlben.qits.epics.entity.TicketStatus;
import eu.wohlben.qits.projects.control.ReleaseRequests;
import eu.wohlben.qits.projects.control.WorkspaceAgentDispatch;
import eu.wohlben.qits.projects.control.WorkspaceAgentTurns;
import eu.wohlben.qits.projects.dto.ReleaseRequestDto;
import eu.wohlben.qits.projects.dto.ReleaseRequestSourceDto;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.List;
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
 * <p>{@link TicketStatus#VERIFIED} and {@link TicketStatus#DONE} therefore start no phase and
 * deliver no turn, and that is where the one remaining human decision lives: closing a ticket is a
 * person's move. DONE ends the flow at its first line and writes nothing at all. VERIFIED does the
 * one thing that is not a phase — it asks for the release of the branch the work was done on — and
 * the section below is the whole of it.
 *
 * <h2>VERIFIED asks for a release, and nothing else does</h2>
 *
 * <p>The verify phase ran in the workspace standing on {@code ticket/<slug>} and found the change
 * good. What that branch then needs is to be released, and until now nobody asked: the agent's own
 * instruction says releasing is the goal, but the press that ends verification is a transition and
 * not a release. So a move into VERIFIED opens (or joins) the release request naming that branch on
 * the project's wrapper, and the thread is told which request the ticket now waits on.
 *
 * <p><b>The workspace is looked up first, and the lookup is the point rather than decoration.</b>
 * {@link ReleaseRequests#request} validates a branch name syntactically and nothing else, so an ask
 * naming a branch the git host no longer has lands a PENDING row whose fold answers UNREACHABLE and
 * which the 30-second sweep then retries for ever, with no path that ever settles it. A live
 * reference on exactly {@code ticket/<slug>} is the cheapest available evidence that the branch is
 * there, so <b>no reference means no ask</b> — one plain sentence on the thread and nothing else.
 *
 * <p><b>The call is made in process rather than through {@code ReleaseRequestController}, and
 * bypassing that door is the intent.</b> {@link ReleaseRequests} is role-blind; every check on the
 * release door — {@code @RolesAllowed}, {@code requireAgentProject}, {@code requireAgentBranch} —
 * lives in the controller and judges <em>the caller</em>. Here the caller is not who is asking: the
 * platform is the requester and the ticket lifecycle is the authority, exactly as it is for the turn
 * delivered one arm up. Going through the door would mean widening some agent's {@code git_refs}
 * scope so that pressing a transition also granted it the right to release the wrapper, which is a
 * far larger grant than this feature needs and one that would outlive the press.
 *
 * <h2>A move back into IMPLEMENTED names the request and withdraws nothing</h2>
 *
 * <p>Verification can fail after the release was asked for, and then the ticket moves back. Nothing
 * is withdrawn, because there is no honest inverse to perform: no door removes a single source from
 * a request, the wrapper's request is estate-wide so {@code withdraw} would settle every other
 * branch's release along with this one, and deleting the branch would destroy the work. What the
 * platform owes a person instead is the request id on the thread, so that withdrawing or declining
 * it is a decision they can actually make.
 *
 * <p><b>That note is conditioned on the request existing and never on the direction of the move</b>,
 * which is this class's rule applied where it would be easiest to break: arriving at IMPLEMENTED
 * from REFINED nothing has been asked for, the query finds nothing and no comment is written;
 * arriving back from VERIFIED it does, and the sentence lands. The fact is read rather than inferred
 * from where the ticket came from, so there is still no second table saying which way is which.
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
   * The read half of the dispatch port, and optional for {@link #turns}' reason: with no
   * implementation present there is no workspace to find, so a move into VERIFIED asks for nothing
   * and says nothing — the same silence an absent turn port gets. Named apart from {@link
   * #workspaces} beside it because the two answer different questions: that one derives the address
   * a workspace would stand at, this one says whether one actually does.
   */
  @Inject Instance<WorkspaceAgentDispatch> dispatchedWorkspaces;

  /**
   * Not an {@code Instance}, unlike every port here, because it is not a port: {@link
   * ReleaseRequests} is an {@code @ApplicationScoped} bean of this service's own {@code domain}
   * module, in this JVM, and an assembly without it is an assembly that does not start.
   */
  @Inject ReleaseRequests releaseRequests;

  /**
   * Who asks for the release when the transition carried no name. The platform is the requester on
   * every one of these asks — see the class javadoc — and this is what that looks like on a request
   * a person reads.
   */
  private static final String PLATFORM_REQUESTER = "ticket-lifecycle";

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
    if (ticket.status == TicketStatus.VERIFIED) {
      // The one move that starts no phase and is still not nothing: the work is good, so the branch
      // it was done on is asked to be released. See the class javadoc.
      releaseWorkspace(ticket, changedBy);
      return;
    }
    Optional<TicketPhasePrompts.Started> started = TicketPhasePrompts.startedBy(ticket);
    if (started.isEmpty()) {
      // DONE, and nothing else now that VERIFIED is answered above: the work is over and closing is
      // a person's move. Nothing to do, and nothing to say about having done nothing.
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
    if (ticket.status == TicketStatus.IMPLEMENTED) {
      noteTheReleaseThatStandsOpen(ticket, target.get(), changedBy);
    }
  }

  /**
   * The ask that a move into VERIFIED makes, and the sentence that follows it.
   *
   * <p>Ordered so that the <b>lookup decides whether anything is asked at all</b>: a reference on
   * exactly this ticket's branch is the evidence that the branch is still there, and without it an
   * ask would be a PENDING request the sweep retries for ever. The class javadoc argues that, and
   * why the request is made here rather than through the release door.
   */
  private void releaseWorkspace(Ticket ticket, String changedBy) {
    if (dispatchedWorkspaces.isUnsatisfied()) {
      // No implementation of the port: there is nothing standing anywhere, so there is nothing to
      // release and nothing to say. The same silence an absent turn port gets.
      LOG.debugf(
          "Ticket %s is VERIFIED, and no workspace lookup is configured, so no release was asked"
              + " for",
          ticket.id);
      return;
    }
    String branch = WorkBranches.ticket(ticket).branch();
    List<WorkspaceAgentDispatch.Reference> found;
    try {
      found = dispatchedWorkspaces.get().workspacesReferencing(List.of(ticket.id), List.of());
    } catch (RuntimeException e) {
      // The port says it must not throw; a throw is a port bug and must not touch a transition that
      // has already been recorded. Nothing is known about the branch, which is the "no workspace"
      // answer — and asking anyway is the one thing that cannot be undone.
      LOG.warnf(e, "Could not look up the workspaces of ticket %s: the port threw", ticket.id);
      found = List.of();
    }
    WorkspaceAgentDispatch.Reference standing =
        found.stream()
            .filter(reference -> branch.equals(reference.branch()))
            .findFirst()
            .orElse(null);
    if (standing == null) {
      // A workspace on some other branch is somebody else's work, and no workspace at all is the
      // ordinary state of a ticket walked through by hand.
      LOG.debugf("No workspace stands on %s, so ticket %s asks for no release", branch, ticket.id);
      say(
          ticket,
          "No workspace is standing on `" + branch + "`, so no release was asked for.",
          changedBy);
      return;
    }

    String requester = changedBy == null || changedBy.isBlank() ? PLATFORM_REQUESTER : changedBy;
    try {
      ReleaseRequestDto request =
          releaseRequests.request(
              standing.repositoryId(),
              branch,
              "Ticket " + ticket.slug + ": " + ticket.title,
              requester,
              // No priority: MEDIUM is what a caller who states nothing gets, and this caller has
              // nothing to state — the ticket carries no urgency a release could read.
              null);
      LOG.infof(
          "Ticket %s (%s) is VERIFIED: release request %s now carries %s",
          ticket.id, ticket.slug, request.id(), branch);
      say(
          ticket,
          "Asked for the release of `"
              + branch
              + "`: the ticket now waits on release request "
              + request.id()
              + ".",
          changedBy);
    } catch (RuntimeException e) {
      // The refusal arm's rule, one door over: name what did not happen, carry the far side's own
      // reason, and claim nothing that did.
      LOG.warnf(
          e,
          "Could not ask for the release of %s for ticket %s (%s)",
          branch,
          ticket.id,
          ticket.slug);
      String detail = e.getMessage() == null ? "" : e.getMessage().trim();
      say(
          ticket,
          "Could not ask for the release of `"
              + branch
              + "`"
              + (detail.isBlank() ? "" : ": " + detail)
              + ". The ticket is VERIFIED and nothing is running on it.",
          changedBy);
    }
  }

  /**
   * The second sentence a move into IMPLEMENTED sometimes gets: the release asked for earlier is
   * still standing, and it is a person's to withdraw or decline.
   *
   * <p>Read rather than inferred — see the class javadoc — so a ticket arriving from REFINED finds
   * nothing and is told nothing. A failed read writes nothing either: this note is context on a move
   * that has already happened and has already said what it started, and a sentence about the release
   * is worth exactly nothing if it might be wrong.
   *
   * <p>The state is left unnamed on purpose, because <b>absent is the word meaning open</b> here
   * ({@code ReleaseRequests.statesFor}): the open set is what it answers, plus the last few
   * FINALIZED requests as a tail. A finalized request is a release that landed and is nobody's to
   * withdraw, so the tail is dropped again on the way past.
   */
  private void noteTheReleaseThatStandsOpen(
      Ticket ticket, TicketWorkspaces.Target target, String changedBy) {
    String branch = target.branch();
    ReleaseRequestDto open;
    try {
      open =
          releaseRequests.listByRepo(target.repositoryId(), null).stream()
              .filter(request -> !FINALIZED.equals(request.state()))
              .filter(
                  request ->
                      request.sources().stream()
                          .map(ReleaseRequestSourceDto::name)
                          .anyMatch(branch::equals))
              .findFirst()
              .orElse(null);
    } catch (RuntimeException e) {
      LOG.warnf(
          e,
          "Could not read the release requests of %s, so ticket %s was told nothing about the"
              + " release its branch is on",
          target.repositoryId(),
          ticket.id);
      return;
    }
    if (open == null) {
      return;
    }
    LOG.infof(
        "Ticket %s moved back to IMPLEMENTED while release request %s still carries %s",
        ticket.id, open.id(), branch);
    say(
        ticket,
        "Release request "
            + open.id()
            + " still names `"
            + branch
            + "` as a source: the release was not withdrawn, and withdrawing or declining it is a"
            + " person's decision.",
        changedBy);
  }

  /** The one state word this class reads, and it reads it to drop a row rather than to find one. */
  private static final String FINALIZED = "FINALIZED";

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
