package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.epics.api.EpicsPrincipal;
import eu.wohlben.qits.epics.control.TicketService;
import eu.wohlben.qits.epics.entity.Ticket;
import eu.wohlben.qits.projects.control.ProjectService;
import eu.wohlben.qits.projects.control.RepositoryService;
import eu.wohlben.qits.projects.control.WorkspaceAgentDispatch;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.Repository;
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
 * <h2>The workspace is told what it is for, and is given no goal</h2>
 *
 * <p>The dispatch carries the ticket's <b>id</b> ({@link WorkspaceAgentDispatch.Subject#ticket}) and
 * no preamble. This door used to render the whole ticket — title, a type/status/assignee/reporter
 * line and the full description — into the workspace's goal, and that copy served no reader well:
 * the instruction below sends the agent to read the ticket live over MCP, so the prose was stale by
 * construction, and on the workspace page it buried the one fact a person scanning the list wants.
 * The workspaces SPA turns the id into a link; the preamble goes back to being what it is, a
 * person's prose, authored where a person creates a workspace by hand.
 *
 * <h2>What lands on the thread</h2>
 *
 * <p>A dispatch that succeeded writes one comment, stamped from the caller's identity exactly as a
 * hand-written one is, and fires the {@code TICKETS} hint so open browsers redraw. A dispatch that
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

  @Inject ProjectService projects;

  @Inject RepositoryService repositories;

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
    Ticket ticket = tickets.get(id); // 404 if the ticket does not exist
    if (dispatch.isUnsatisfied()) {
      throw new DomainException(
          503,
          "No workspaces context is configured, so no agent can be dispatched onto ticket " + id
              + ".");
    }
    Project project = projects.get(ticket.projectId);
    Repository wrapper = wrapperOf(project);
    String branch = "ticket/" + ticket.slug;

    WorkspaceAgentDispatch.Dispatch made =
        dispatch
            .get()
            .dispatchAgent(
                wrapper.id,
                branch,
                true,
                WorkspaceAgentDispatch.Subject.ticket(ticket.id),
                instruction(ticket));

    String changedBy = EpicsPrincipal.changedBy(identity);
    tickets.addComment(ticket.id, comment(branch, made), changedBy);
    publisher.fire(ticket.projectId, ProjectChangeHint.Topic.TICKETS);
    LOG.infof(
        "Dispatched an agent onto ticket %s (%s) in workspace %s on %s",
        ticket.id, ticket.slug, made.workspaceRowId(), branch);
    return new DispatchAgentRequest.Response(
        TicketAgentDispatchDto.of(made, wrapper.id, branch));
  }

  // ---- the pieces --------------------------------------------------------------------------

  /** {@code RefinementService.wrapperOf}'s seam and its refusal — the project IS its wrapper. */
  private Repository wrapperOf(Project project) {
    String wrapperName = ProjectService.wrapperName(project);
    return repositories
        .findByProjectAndName(project.id, wrapperName)
        .orElseThrow(
            () ->
                new DomainException(
                    409,
                    "Project "
                        + project.id
                        + " has no wrapper repository ("
                        + wrapperName
                        + "), so there is nothing to dispatch an agent onto."));
  }

  /**
   * The agent's first turn. Four things are said on purpose and none of them is decoration: read the
   * ticket over MCP rather than working from what the workspace was handed, keep <em>one</em> comment current instead
   * of stacking notes under it, treat the work as unfinished until it is released — the platform's
   * own definition of done, and the one an agent left to itself gets wrong — and then resolve the
   * ticket.
   *
   * <p><b>The resolve is conditional and hangs off the release, which is why it is the last
   * sentence.</b> Without it a dispatched agent that finished cleanly left an OPEN ticket behind and
   * a person had to notice and close it; with it worded as a habit, an agent that was blocked or
   * only half-released would close one that is not done, which is worse. So the sentence names both
   * arms — resolved once released, left OPEN with the gap said on the thread otherwise — and says
   * that resolving is reversible through the same door. That last clause is what gives an unsure
   * agent a cheap correct move instead of a coin flip.
   *
   * <p>Two seams have to hold for this to be an instruction rather than a dead letter, and both are
   * checked rather than assumed. qits-workspace-daemon's {@code AgentLaunchService} lists {@code
   * transition_ticket} in its {@code TICKET_RESOLUTION_TOOLS} bucket, so the tool exists for a kimi
   * session too (there {@code enabledTools} is the whole surface, not a pre-approval). And a
   * dispatch keeps connecting <em>without</em> the {@code agentReadOnly=true} marker — it goes
   * through the daemon's {@code launchChat}, which never sets it — so {@link
   * eu.wohlben.qits.projects.mcp.ReadOnlyRepositoryToolFilter} still hides all five ticket writes
   * from an unattended run. If a dispatch ever starts marking itself read-only, this sentence goes
   * silent along with the thread comments.
   */
  static String instruction(Ticket ticket) {
    return "Work on ticket \""
        + ticket.title
        + "\" ("
        + ticket.type
        + ", slug "
        + ticket.slug
        + "). Read it first with get_ticket (id "
        + ticket.id
        + ") — the description and the comment thread are the brief."
        + " Document your findings and progress as a ticket comment with add_ticket_comment,"
        + " and keep that same comment current with update_ticket_comment as you go:"
        + " short is better for that comment."
        + " Your work is only done once your changes are fully released — integrate the workspace"
        + " and see the release through, and say so on the thread when it is."
        + " Once it is released, resolve the ticket with transition_ticket (target RESOLVED) as the"
        + " last step; if you could not finish it — blocked, refused, or released only in part —"
        + " leave it OPEN and say on the thread what is missing. Resolving is reversible and"
        + " reopening is the same door, so leaving it open when you are unsure is the cheap correct"
        + " answer.";
  }

  /**
   * What the thread is told. A re-dispatch that found an agent already working says so rather than
   * claiming a second one was started — the far side's {@code SKIPPED_RUNNING} is the only thing
   * that knows, and a comment that got it wrong would read as two agents on one ticket.
   */
  private static String comment(String branch, WorkspaceAgentDispatch.Dispatch made) {
    if ("SKIPPED_RUNNING".equals(made.agentLaunch())) {
      return "An agent is already working on this ticket in workspace `" + branch
          + "`; left it to carry on.";
    }
    return "Dispatched a coding agent to workspace `" + branch + "`.";
  }
}
