package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.epics.api.EpicsPrincipal;
import eu.wohlben.qits.epics.control.EpicService;
import eu.wohlben.qits.epics.entity.Epic;
import eu.wohlben.qits.epics.entity.EpicStatus;
import eu.wohlben.qits.projects.control.ProjectService;
import eu.wohlben.qits.projects.control.RepositoryService;
import eu.wohlben.qits.projects.control.WorkspaceAgentDispatch;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.error.DomainException;
import eu.wohlben.qits.projects.refinementhost.EpicOutline;
import eu.wohlben.qits.projects.refinementhost.EpicResolutions;
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
 * "Start implementation" on a refined epic: freeze the scope and stand an implementing agent up on
 * it, in one press. {@link TicketDispatchController}'s door one planning level higher, and read that
 * class first — everything the two share is explained there and is not restated here.
 *
 * <h2>Why it is here and not beside {@code EpicController}</h2>
 *
 * <p>The same reason the ticket door is: this needs {@code domain} — the project, its wrapper
 * repository and the {@link WorkspaceAgentDispatch} port — and the epics jar depends on {@code
 * domain} nowhere and must keep not depending on it. The service layer may cross, and putting the
 * class in {@code projects.api} is that crossing declared in the package name. Two JAX-RS resources
 * sharing {@code @Path("/epics")} is fine so long as no method path collides, which is the shape
 * {@code TicketController} and {@link TicketDispatchController} already have.
 *
 * <h2>The branch an epic gets</h2>
 *
 * <p>{@code epic/<epicSlug>} on the project's <b>wrapper</b>, with {@code branchTree} — the
 * aggregate workspace over every submodule. An epic spans the estate and names no single component
 * (its <em>tasks</em> name repositories, one each, and there is no one of them the epic is about),
 * so the wrapper is the answer and the whole estate is what is branched. The slug is the branch
 * segment because it is minted at create and never re-derived, so a retitled epic keeps the branch
 * its agent is already working on.
 *
 * <h2>The order is load-bearing: transition first, dispatch second</h2>
 *
 * <p>The epic is moved to {@link EpicStatus#IMPLEMENTATION} <b>before</b> the dispatch is made, and
 * that is not a stylistic preference. The marker door the dispatched agent is told to use — {@code
 * mark_task_implemented} on {@code EpicMcpTools} — is only open while the owning epic is in
 * IMPLEMENTATION ({@code EpicLifecycle.requireImplementation}). A dispatch that raced the transition
 * would hand the agent a tool its own epic refuses, and the agent would discover that halfway
 * through the first task with no way to fix it from inside its container.
 *
 * <p>The cost of that order is the failure it leaves behind, and the cost is the acceptable one: if
 * the dispatch then fails, the epic is in IMPLEMENTATION with no agent on it. That is a
 * <em>legitimate</em> state rather than a broken one — the scope really is frozen, somebody really
 * did decide to start — and the button can simply be pressed again, because the far side adopts the
 * workspace already standing on {@code epic/<slug>} instead of making a second one.
 *
 * <p>What comes <b>before</b> the transition is only what is knowable without attempting anything:
 * the epic itself (404), a status that says the work is over (409), a project with no wrapper (409)
 * and an assembly with no workspaces context at all (503). None of those is a dispatch that failed;
 * each is a precondition that was never going to hold, and moving an epic's status on the way to one
 * of them would be a status change nothing asked for.
 *
 * <h2>Re-pressing is the retry, so IMPLEMENTATION is not a refusal</h2>
 *
 * <p>The transition runs only when the epic is {@link EpicStatus#REFINING}. An epic already in
 * IMPLEMENTATION is dispatched onto exactly as it stands — {@code EpicService.planTransition} would
 * 409 on {@code IMPLEMENTATION→IMPLEMENTATION}, and a door whose retry answered 409 would leave a
 * failed dispatch with no way back. Every other status ({@link EpicStatus#IMPLEMENTED}, {@link
 * EpicStatus#SUPERSEDED}, {@link EpicStatus#ABANDONED}) is a 409 from this door naming the status:
 * you do not dispatch an agent onto work that is over.
 *
 * <p>The move goes through {@link EpicResolutions} and never {@code EpicService.transition} — that
 * class's javadoc makes the rule explicit, and it is the only way an epic's status should be moved
 * from a door. {@code REFINING→IMPLEMENTATION} is not a resolving move, so nothing is discarded
 * here: the epic's refinement, if it has one, survives the freeze exactly as it does through the
 * board's own transition. Tearing that refinement down at this moment is a different ticket
 * ({@code 6cab37e4}); this door neither does it nor makes it harder to do.
 *
 * <h2>Nothing is written on the epic</h2>
 *
 * <p>An epic has no comment thread — that is the whole difference from the ticket door, which
 * stamps one. The epic's <b>description is the plan</b>, written by people and refined by a
 * refinement agent, and a dispatch appending "an agent was started" to it would be this door editing
 * somebody's plan to record its own bookkeeping. So what lands is exactly two things: the {@code
 * EPICS} hint, so open boards redraw on the status move, and the DTO the caller is answered with.
 * A failed dispatch, having written nothing, leaves nothing to undo.
 */
@Path("/epics")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("qits:admin")
public class EpicDispatchController {

  private static final Logger LOG = Logger.getLogger(EpicDispatchController.class);

  @Inject EpicService epics;

  @Inject EpicResolutions resolutions;

  @Inject EpicOutline outline;

  @Inject ProjectService projects;

  @Inject RepositoryService repositories;

  @Inject SecurityIdentity identity;

  @Inject ProjectChangePublisher publisher;

  /** Optional, like every port here. Absent is a 503 naming what is missing. */
  @Inject Instance<WorkspaceAgentDispatch> dispatch;

  /** No body: everything the dispatch needs is derived from the epic it is about. */
  public record DispatchAgentRequest() {
    public record Response(EpicAgentDispatchDto dispatch) {}
  }

  @POST
  @Path("/{id}/dispatch-agent")
  public DispatchAgentRequest.Response dispatchAgent(@PathParam("id") String id) {
    Epic epic = epics.get(id); // 404 if the epic does not exist
    requireStartable(epic);
    if (dispatch.isUnsatisfied()) {
      throw new DomainException(
          503,
          "No workspaces context is configured, so no agent can be dispatched onto epic " + id + ".");
    }
    Project project = projects.get(epic.projectId);
    Repository wrapper = wrapperOf(project);
    String branch = "epic/" + epic.slug;

    if (epic.status == EpicStatus.REFINING) {
      // First, and through EpicResolutions — see the class javadoc for both halves of why.
      epic = resolutions.transition(id, EpicStatus.IMPLEMENTATION.name(), changedBy()).epic();
      // The status moved, so open boards redraw. A re-press moved nothing and announces nothing.
      publisher.fire(epic.projectId, ProjectChangeHint.Topic.EPICS);
    }

    WorkspaceAgentDispatch.Dispatch made =
        dispatch.get().dispatchAgent(wrapper.id, branch, true, preamble(epic), instruction(epic));

    LOG.infof(
        "Dispatched an agent onto epic %s (%s) in workspace %s on %s",
        epic.id, epic.slug, made.workspaceRowId(), branch);
    return new DispatchAgentRequest.Response(EpicAgentDispatchDto.of(made, wrapper.id, branch));
  }

  // ---- the pieces --------------------------------------------------------------------------

  /**
   * Refuses an epic whose work is over. REFINING is the ordinary press and IMPLEMENTATION is the
   * re-press; everything else names the status back, because "409" alone would leave the caller
   * guessing which of three finished statuses it walked into.
   */
  private static void requireStartable(Epic epic) {
    if (epic.status != EpicStatus.REFINING && epic.status != EpicStatus.IMPLEMENTATION) {
      throw new DomainException(
          409,
          "Epic "
              + epic.id
              + " is "
              + epic.status
              + ", so there is no implementation to start — an agent is not dispatched onto work"
              + " that is over.");
    }
  }

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
   * The workspace's goal, Markdown — the epic under {@code # Implement:}, with its feature/task
   * outline, rendered by the same {@link EpicOutline} a refinement opens with.
   *
   * <p>It is a <b>snapshot for orientation and never the brief</b>. These bytes are frozen into the
   * container at creation while the epic goes on moving — a task marked implemented, a description
   * corrected — which is exactly why the instruction below sends the agent to read the epic live
   * with {@code get_epic} instead of working from what it was handed.
   */
  private String preamble(Epic epic) {
    return outline.render(epic, "Implement");
  }

  /**
   * The agent's first turn. Five things are said on purpose, in this order, and none of them is
   * decoration: read the epic over MCP rather than working from the preamble; work the features and
   * their tasks in the order the {@code dependsOn} links describe; mark each task implemented
   * <em>as it lands</em> rather than in a batch at the end, so a run that dies halfway leaves a true
   * record of how far it got; treat the work as unfinished until it is released, which is the
   * platform's own definition of done and the one an agent left to itself gets wrong; and then say
   * so.
   *
   * <p><b>The closing move is conditional and last, and it deliberately stops short of the epic's
   * own close.</b> The agent reports that everything is marked and released — it does not move the
   * epic to IMPLEMENTED, and there is no tool here with which it could. {@code EpicMcpTools} exposes
   * no lifecycle move at all, on the stated ground that freezing a scope and declaring one done are
   * decisions about scope rather than reports about work, and this change does not add one: an epic
   * declared done stamps every unimplemented feature and task in the same transaction, so an agent
   * that finished four tasks of five would be able to declare the fifth shipped by pressing the
   * wrong button. The other arm is spelled out for the same reason the ticket instruction spells its
   * one out — blocked, refused or released only in part means saying what is missing and leaving the
   * epic in implementation, which is the state a person can act on.
   *
   * <p>Two seams have to hold for this to be an instruction rather than a dead letter, and both are
   * checked rather than assumed. qits-workspace-daemon's pre-approval bucket must list {@code
   * mark_task_implemented}, {@code get_epic} and {@code list_epics}: on the kimi path {@code
   * enabledTools} is the whole tool surface rather than a pre-approval, so a tool that is not listed
   * does not exist for that session and every sentence below about marking is silently unactionable.
   * And a dispatch keeps connecting <em>without</em> {@code agentReadOnly=true} — it goes through
   * the daemon's {@code launchChat}, which never sets it — so {@link
   * eu.wohlben.qits.projects.mcp.ReadOnlyRepositoryToolFilter} still fences the epic writes,
   * {@code mark_task_implemented} included, off an unattended run. If a dispatch ever starts marking
   * itself read-only, the marking half of this instruction goes silent.
   */
  static String instruction(Epic epic) {
    return "Work on epic \""
        + epic.title
        + "\" (slug "
        + epic.slug
        + ") in this project. Read it first with get_epic (id "
        + epic.id
        + ") — the description and its feature/task tree are the brief, so read it live rather than"
        + " trusting the preamble you were handed."
        + " Work the features and their tasks in order, respecting the dependsOn links between"
        + " them."
        + " Mark each task implemented with mark_task_implemented as it lands, rather than in a"
        + " batch at the end."
        + " Your work is only done once your changes are fully released — integrate the workspace"
        + " and see the release through."
        + " Once every task is marked and the changes are released, say so; the epic's own Mark"
        + " implemented is a person's press and not yours. If you could not finish it — blocked,"
        + " refused, or released only in part — say what is missing and leave the epic in"
        + " implementation.";
  }

  /** The audit's {@code changed_by} for the transition this door makes. */
  private String changedBy() {
    return EpicsPrincipal.changedBy(identity);
  }
}
