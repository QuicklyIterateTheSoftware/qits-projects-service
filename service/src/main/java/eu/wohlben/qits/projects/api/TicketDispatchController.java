package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.epics.api.EpicsPrincipal;
import eu.wohlben.qits.epics.control.TicketService;
import eu.wohlben.qits.epics.entity.TicketStatus;
import eu.wohlben.qits.epics.entity.WorkEntity;
import eu.wohlben.qits.projects.control.WorkspaceAgentDispatch;
import eu.wohlben.qits.projects.error.DomainException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.jboss.logging.Logger;

/**
 * "Assign agent" on a ticket: one door that makes a workspace over the whole estate and starts a
 * coding agent in it, then says so on the ticket's own thread.
 *
 * <h2>Why it is here and not beside {@code TicketController}</h2>
 *
 * <p>Every other ticket route lives in {@code eu.wohlben.qits.epics.api} because it is the epics
 * module's surface and needs nothing else. This one needs {@code domain} — the project, its wrapper
 * repository and the {@link WorkspaceAgentDispatch} port — and the epics jar depends on {@code
 * domain} nowhere and must keep not depending on it, because it is the module most likely to be
 * lifted out next. The <em>service</em> layer may cross, which is the precedent {@code
 * ProjectTicketsController} already sets by validating a project id against {@code domain}; putting
 * the class in {@code projects.api} is that same crossing declared in the package name.
 *
 * <h2>The branch a ticket gets</h2>
 *
 * <p>{@code ticket/<slug>} on the project's <b>wrapper</b>, with {@code branchTree} — the aggregate
 * workspace over every submodule. A ticket names work and names no repository, so there is no single
 * component to stand a workspace on and guessing one from the text would be a guess the agent then
 * has to work around. The wrapper is the project; the whole estate is the answer.
 *
 * <p>The slug is the branch segment for the reason it exists: minted at create and never
 * re-derived, so a retitled ticket keeps the branch its agent is already working on.
 *
 * <p>Both halves of that address are resolved by {@link TicketWorkspaces} rather than here, because
 * {@link TicketPhaseAdvance} has to arrive at the same one when it speaks to the workspace this door
 * stood up. Its {@code require} arm carries this door's own 409 — the one a person reads when a
 * project has no wrapper to make a workspace on — unchanged.
 *
 * <h2>The agent's first turn is picked by the ticket's status</h2>
 *
 * <p>{@link TicketPhasePrompts#promptFor(WorkEntity)} is the whole of it, and that class holds the
 * argument for every sentence in the three templates — including the two seams that have to hold
 * for any of them to be an instruction rather than a dead letter, which moved there with the words
 * they are about. What this door does with the answer is the rest of this section.
 *
 * <p>A status is what has been <em>achieved</em> and the phase that runs while it holds is what
 * happens next, so REPORTED starts refinement, REFINED starts implementation and IMPLEMENTED starts
 * verification — and pressing "assign agent" on a half-finished ticket <b>resumes</b> it there
 * rather than starting it over. {@link TicketStatus#VERIFIED} and {@link TicketStatus#DONE} start
 * nothing at all, so the prompt is empty and this door answers <b>409</b> naming the status. That
 * refusal runs <em>before</em> the port is asked for anything: the ticket is past the work, so no
 * workspace is stood up, nothing is launched, and nothing lands on the thread. Closing a ticket is
 * a person's move, and reopening it to VERIFIED or IMPLEMENTED is the way back to a dispatchable
 * one.
 *
 * <h2>The workspace is told what it is for, and is given no goal</h2>
 *
 * <p>The dispatch carries the ticket's <b>id</b> ({@link WorkspaceAgentDispatch.Subject#ticket}) and
 * no preamble. This door used to render the whole ticket — title, a type/status/assignee/reporter
 * line and the full description — into the workspace's goal, and that copy served no reader well:
 * every phase template sends the agent to read the ticket live over MCP, so the prose was stale by
 * construction, and on the workspace page it buried the one fact a person scanning the list wants.
 * The workspaces SPA turns the id into a link; the preamble goes back to being what it is, a
 * person's prose, authored where a person creates a workspace by hand.
 *
 * <h2>What lands on the thread</h2>
 *
 * <p>A dispatch that succeeded writes one comment, stamped from the caller's identity exactly as a
 * hand-written one is, <b>naming the phase it started</b> — there are three now, they read nothing
 * like each other, and a thread saying only "an agent was dispatched" would leave a reader unable to
 * tell a refinement from a verification. It fires the {@code TICKETS} hint so open browsers redraw.
 * A dispatch that
 * failed writes nothing at all — a comment saying an agent is on it when none is would be worse
 * than the error the caller already gets.
 */
@Path("/tickets")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("qits:admin")
public class TicketDispatchController {

  private static final Logger LOG = Logger.getLogger(TicketDispatchController.class);

  @Inject TicketService tickets;

  /**
   * The wrapper and the branch, resolved where {@link TicketPhaseAdvance} resolves them. Two flows
   * address one workspace and the address is derived rather than stored, so one of them computing it
   * differently would be invisible until a phase hand-off quietly stopped arriving.
   */
  @Inject TicketWorkspaces workspaces;

  @Inject SecurityIdentity identity;

  @Inject ProjectChangePublisher publisher;

  /** Optional, like every port here. Absent is a 503 naming what is missing. */
  @Inject Instance<WorkspaceAgentDispatch> dispatch;

  /** No body: everything the dispatch needs is derived from the ticket it is about. */
  public record DispatchAgentRequest() {
    public record Response(TicketAgentDispatchDto dispatch) {}
  }

  @POST
  @Path("/{id}/dispatch-agent")
  public DispatchAgentRequest.Response dispatchAgent(@PathParam("id") String id) {
    WorkEntity ticket = tickets.get(id); // 404 if the ticket does not exist
    // Before anything is asked of anybody: a ticket past the work starts no phase and no workspace.
    TicketPhasePrompts.Started started = phaseOrRefuse(ticket);
    if (dispatch.isUnsatisfied()) {
      throw new DomainException(
          503,
          "No workspaces context is configured, so no agent can be dispatched onto ticket " + id
              + ".");
    }
    // Where this ticket's agent stands: the project's wrapper and ticket/<slug>, with the refs it
    // may push — resolved by the one collaborator the phase hand-off resolves through too.
    TicketWorkspaces.Target target = workspaces.require(ticket);
    String branch = target.branch();

    WorkspaceAgentDispatch.Dispatch made =
        dispatch
            .get()
            .dispatchAgent(
                target.repositoryId(),
                branch,
                target.scope().gitRefs(),
                true,
                WorkspaceAgentDispatch.Subject.ticket(ticket.id),
                started.instruction());

    String changedBy = EpicsPrincipal.changedBy(identity);
    tickets.addComment(ticket.id, comment(branch, made, started.phase()), changedBy);
    publisher.fire(ticket.projectId, ProjectChangeHint.Topic.TICKETS);
    LOG.infof(
        "Dispatched an agent onto ticket %s (%s) for the %s phase in workspace %s on %s",
        ticket.id, ticket.slug, started.phase(), made.workspaceRowId(), branch);
    return new DispatchAgentRequest.Response(
        TicketAgentDispatchDto.of(made, target.repositoryId(), branch));
  }

  // ---- the pieces --------------------------------------------------------------------------

  /**
   * The phase this ticket's status starts, with the turn its agent gets — or the <b>409</b> that
   * says there is none. {@link TicketStatus#VERIFIED} and {@link TicketStatus#DONE} are the two: the
   * ticket is past the work, and what is left is a person's judgement rather than an agent's run.
   *
   * <p>It is the first thing this door does after resolving the ticket, ahead of the workspaces
   * port, the project and the wrapper, for the reason {@code EpicDispatchController.requireStartable}
   * gives one level up — a refusal that was never going to be avoidable is decided before anything
   * is attempted, so nothing is stood up and nothing is written for a caller about to be refused.
   * The message names the status back, because "409" alone leaves the caller guessing which of the
   * two it walked into and what would make the ticket dispatchable again.
   */
  private static TicketPhasePrompts.Started phaseOrRefuse(WorkEntity ticket) {
    return TicketPhasePrompts.startedBy(ticket)
        .orElseThrow(
            () ->
                new DomainException(
                    409,
                    "Ticket "
                        + ticket.id
                        + " is "
                        + ticket.status
                        + ", so there is no phase left to start — what remains is a person's to"
                        + " decide, and an agent is not dispatched onto work that is over."));
  }

  /**
   * What the thread is told. It <b>names the phase</b>, because there are three of them and they
   * read nothing like each other: a reader scanning the thread can see that this press started a
   * verification and not a second implementation.
   *
   * <p>A re-dispatch that found an agent already working says so rather than claiming a second one
   * was started — the far side's {@code SKIPPED_RUNNING} is the only thing that knows, and a comment
   * that got it wrong would read as two agents on one ticket. That arm names no phase on purpose:
   * the agent that is already running was started for whatever the status said <em>then</em>, and
   * this press has not looked.
   */
  private static String comment(
      String branch, WorkspaceAgentDispatch.Dispatch made, String phase) {
    if ("SKIPPED_RUNNING".equals(made.agentLaunch())) {
      return "An agent is already working on this ticket in workspace `" + branch
          + "`; left it to carry on.";
    }
    return "Dispatched a coding agent to workspace `" + branch + "` for the " + phase + " phase.";
  }
}
