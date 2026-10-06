package eu.wohlben.qits.projects.mcp;

import eu.wohlben.qits.entities.control.Mover;
import eu.wohlben.qits.entities.api.CampaignController;
import eu.wohlben.qits.entities.api.CampaignDtos.CampaignDto;
import eu.wohlben.qits.entities.api.CampaignDtos.CampaignMemberDto;
import eu.wohlben.qits.entities.api.CampaignDtos.CampaignProgressDto;
import eu.wohlben.qits.entities.api.CampaignDtos.CampaignSummaryDto;
import eu.wohlben.qits.entities.api.CampaignViews;
import eu.wohlben.qits.entities.campaign.CampaignService;
import eu.wohlben.qits.entities.control.WorkEntityService;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.entities.error.NotFoundException;
import eu.wohlben.qits.projects.api.CampaignInFlight;
import eu.wohlben.qits.projects.api.ProjectChangeHint;
import eu.wohlben.qits.projects.api.ProjectChangePublisher;
import eu.wohlben.qits.projects.refinementhost.EntityResolutions;
import io.quarkiverse.mcp.server.McpServer;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.WrapBusinessError;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;

/**
 * <b>The campaign half of the "repository" MCP server</b> (qits-414), mounted beside {@link
 * EpicMcpTools} and {@link TicketMcpTools} on the same declared server ({@code /projects/mcp}) for
 * the reason {@link EpicMcpTools}' javadoc gives.
 *
 * <p><b>Use case: an agent builds a campaign's membership and order.</b> It proposes a campaign,
 * gathers the epics and tickets it gathers, orders them, and states each member's condition — what
 * the campaign waits for before dispatching that member. It is a thin layer over {@link
 * CampaignService}: every tool here does exactly what {@code CampaignController} /
 * {@code ProjectCampaignsController} do over HTTP, reusing the same service calls, the same
 * in-flight default ({@link CampaignInFlight}), the same project binding and the same {@code EPICS}
 * hint on every write — and {@link CampaignViews} renders the identical DTOs both doors answer, so a
 * tool's JSON is byte-for-byte what the REST door would have answered for the same call.
 *
 * <p><b>There is no start tool and no approve tool, and that is deliberate.</b> Starting a campaign
 * and approving an APPROVAL criterion are both {@code qits:admin} presses on the REST door — a
 * person's sign-off — and an MCP tool with no credential behind it would be exactly the "dispatch
 * without a person" door the campaigns epic exists to refuse. An agent may build the plan; only a
 * person may press go on it or sign off a gated member. Reading how a running campaign is doing,
 * {@code get_campaign_progress} (qits-418), is a read and is here.
 *
 * <p>Scope comes from {@link ProjectScope} (the {@code X-QITS-Project} header), never from a tool
 * argument, and every campaign id a tool is handed is checked back to that project — a campaign in
 * another project reads as not found, exactly as {@link EpicMcpTools} and {@link TicketMcpTools}
 * behave for their own rows.
 *
 * <p>{@link WrapBusinessError} turns anything a tool throws — the scoping checks here and {@code
 * CampaignService}'s {@code NotFoundException}/{@code BadRequestException}/{@code
 * ConflictException} (a claimed member's condition refused to change, an empty group, a member
 * bound to the wrong project, a campaign no longer editable) — into a tool result with {@code
 * isError=true} carrying the message, so a 409 the REST door would answer is a readable refusal here
 * too.
 *
 * <p>Every mutating tool fires a {@link ProjectChangeHint} on {@code EPICS}, the channel the work
 * desk listens on, and every one of them is registered in {@code
 * ReadOnlyRepositoryToolFilter.MUTATING_TOOLS}, which fails closed.
 *
 * <p><b>No {@code @Transactional} here</b>, for the reason {@link EpicMcpTools} states: these tools
 * straddle two persistence units ({@code epics} for the campaign row and {@code projects} for the
 * scope check), and Narayana enlists only one local resource per transaction. {@code CampaignService}
 * opens its own, exactly as it does for the REST controllers.
 */
@ApplicationScoped
@WrapBusinessError
public class CampaignMcpTools {

  /**
   * What the audit log records for a write with no forwarded identity. An MCP session is a machine
   * caller; naming it beats a null {@code changed_by} that reads as "unknown human".
   */
  private static final String AGENT = "mcp-agent";

  @Inject ProjectScope scope;

  @Inject WorkEntityService workEntities;

  /** The campaign's own thread, embedded in {@code get_campaign} (qits-551). */
  @Inject eu.wohlben.qits.entities.control.EntityCommentService thread;

  @Inject CampaignService campaigns;

  @Inject CampaignViews views;

  @Inject CampaignInFlight inFlight;

  @Inject EntityResolutions resolutions;

  @Inject ProjectChangePublisher changePublisher;

  @Inject SecurityIdentity identity;

  // --- Reads ------------------------------------------------------------------

  @McpServer("repository")
  @Tool(
      name = "list_campaigns",
      description =
          "List the campaigns of the project this session is scoped to, oldest first: each with its"
              + " status, whether it is blocked, whether it has ever been started and is currently"
              + " running, and how many"
              + " members it gathers. Use get_campaign to read one campaign's membership and order.")
  public List<CampaignSummaryDto> listCampaigns() {
    return views.summaries(campaigns.listByProject(scope.requireProjectId()));
  }

  @McpServer("repository")
  @Tool(
      name = "get_campaign",
      description =
          "Read one campaign of this project in full: its description, its start (null if it has"
              + " never run), and every member in position order — the entity it gathers, whether it"
              + " joined already in flight, and its condition (the OR'd groups of AND'd criteria it"
              + " waits on, each with its satisfied marker). Read it before building on a campaign, so"
              + " the order and conditions you extend are the ones that exist. It also carries the"
              + " campaign's own comment thread, oldest first.")
  public CampaignDetail getCampaign(
      @ToolArg(description = "id of a campaign in this project") String id) {
    requireCampaignInProject(id);
    return CampaignDetail.of(
        views.campaign(campaigns.get(id)), CommentMcpTools.threadOf(thread, id));
  }

  /**
   * {@code get_campaign}'s answer: the campaign as the REST door draws it ({@link CampaignDto}),
   * flat, plus its own comment thread, oldest first (qits-551). A record of its own rather than a
   * field on {@link CampaignDto}, because that one is the REST wire and the goldens pin it.
   */
  public record CampaignDetail(
      String id,
      long number,
      String qualifiedId,
      String projectId,
      String slug,
      String title,
      String description,
      String status,
      boolean blocked,
      eu.wohlben.qits.entities.api.CampaignDtos.CampaignStartDto start,
      List<CampaignMemberDto> members,
      List<CommentMcpTools.CommentDetail> comments) {

    static CampaignDetail of(CampaignDto campaign, List<CommentMcpTools.CommentDetail> comments) {
      return new CampaignDetail(
          campaign.id(),
          campaign.number(),
          campaign.qualifiedId(),
          campaign.projectId(),
          campaign.slug(),
          campaign.title(),
          campaign.description(),
          campaign.status(),
          campaign.blocked(),
          campaign.start(),
          campaign.members(),
          comments);
    }
  }

  @McpServer("repository")
  @Tool(
      name = "get_campaign_progress",
      description =
          "Read how a campaign of this project is doing: for every member, in position order, its"
              + " state (DROPPED, DONE, DISPATCH_FAILED, JOINED_RUNNING, RUNNING, REFUSED, READY or"
              + " WAITING), the entity ids it waits for, and each criterion of its condition judged —"
              + " whether it is satisfied and by what evidence, the sentence that would satisfy it,"
              + " and, for one that waits on another member's status, whether it still can and why"
              + " not. It also says whether the criteria evaluator is listening at all (evaluator:"
              + " connected, lastSweepCompletedAt, stalled; consumerFailing with lastError and"
              + " lastErrorAt when the criteria consumer is failing on its frames, which also makes"
              + " stalled true; watermarkAt, how far its catch-up has read), which is how a"
              + " correctly waiting campaign is told from one nothing is listening for. Read-only;"
              + " derived afresh on"
              + " every call.")
  public CampaignProgressDto getCampaignProgress(
      @ToolArg(description = "id of a campaign in this project") String id) {
    requireCampaignInProject(id);
    return views.progress(campaigns.progress(id));
  }

  // --- Create and transition ---------------------------------------------------

  @McpServer("repository")
  @Tool(
      name = "create_campaign",
      description =
          "Propose a new campaign for this project. It is created REPORTED and empty — no members,"
              + " never started — so add its members with add_campaign_member next. Moving it to"
              + " REFINED is the claim that its membership and order are ready; starting a REFINED"
              + " campaign is a person's press, not a tool on this server.")
  public CampaignDto createCampaign(
      @ToolArg(description = "short label for lists and breadcrumbs") String title,
      @ToolArg(required = false, description = "the long-form Markdown description") String description) {
    String projectId = scope.requireProjectId();
    WorkEntity created = campaigns.create(projectId, title, description, changedBy());
    announce(projectId);
    return views.campaign(campaigns.get(created.id));
  }

  @McpServer("repository")
  @Tool(
      name = "transition_campaign",
      description =
          "Move a campaign along its lifecycle: REPORTED, REFINED, READY_FOR_DEV, IMPLEMENTED,"
              + " VERIFIED, DONE or DROPPED, by the rule transition_epic states for an epic —"
              + " adjacent moves only, one step at a time — except that a campaign NEVER enters"
              + " IMPLEMENTING or VERIFYING (refused with a 409: its members are what is implemented"
              + " and verified), so its walk is REPORTED <-> REFINED <-> READY_FOR_DEV <->"
              + " IMPLEMENTED <-> VERIFIED -> DONE with both left out: READY_FOR_DEV -> IMPLEMENTED"
              + " and IMPLEMENTED -> VERIFIED are one step forward, VERIFIED -> IMPLEMENTED and"
              + " IMPLEMENTED -> READY_FOR_DEV one step back. Its membership and conditions are editable only while it is"
              + " REPORTED or REFINED — add_campaign_member, move_campaign_member,"
              + " remove_campaign_member and set_campaign_member_condition are all refused once it"
              + " moves past REFINED. Moving out of REFINED pauses a campaign that is currently"
              + " running. Starting a REFINED campaign, and approving an APPROVAL criterion, are both"
              + " a person's press and are not reachable from this server.")
  public CampaignDto transitionCampaign(
      @ToolArg(description = "id of a campaign in this project") String id,
      @ToolArg(
              description =
                  "the status to move to: REPORTED, REFINED, READY_FOR_DEV, IMPLEMENTED, VERIFIED,"
                      + " DONE or DROPPED (never IMPLEMENTING or VERIFYING). It must be a neighbour of the campaign's"
                      + " current status on that walk; DONE is final and moves nowhere")
          String target) {
    requireCampaignInProject(id);
    String projectId = scope.requireProjectId();
    // The agent surface: a machine, whatever the session says (qits-887).
    resolutions.transition(Archetype.CAMPAIGN, id, target, Mover.machine(changedBy()));
    announce(projectId);
    return views.campaign(campaigns.get(id));
  }

  // --- Membership ---------------------------------------------------------------

  @McpServer("repository")
  @Tool(
      name = "add_campaign_member",
      description =
          "Add an epic or a ticket of this project to a campaign, at a position (omit to append)."
              + " Refused with a 409 for a feature or a task, one a campaign may not hold, one"
              + " of another project, a duplicate, or a campaign no longer REPORTED or REFINED. When"
              + " a member already stands at the position just before this one and is not yet"
              + " VERIFIED or DONE, the new member is SEEDED to wait on that predecessor reaching"
              + " VERIFIED — one group, one ENTITY_STATUS criterion — so ordering members is itself"
              + " how a dependency between them is stated; add set_campaign_member_condition"
              + " afterwards for anything more. Whether the member joins already IN FLIGHT is, unless"
              + " you state inFlight explicitly, decided for you: true when the member is already"
              + " IMPLEMENTING, IMPLEMENTED, VERIFYING, VERIFIED or DONE, or when an ACTIVE workspace"
              + " already stands on its"
              + " branch — both mean its work is already under way and the campaign should not wait"
              + " on a fresh dispatch of it.")
  public CampaignMemberDto addCampaignMember(
      @ToolArg(description = "id of a campaign in this project") String campaignId,
      @ToolArg(description = "id of an epic or ticket of this project to gather") String entityId,
      @ToolArg(required = false, description = "where to insert it; omit or past the end appends")
          Integer position,
      @ToolArg(
              required = false,
              description =
                  "whether the member joins already in flight; omit to let the service decide from"
                      + " the member's status and any active workspace on its branch")
          Boolean inFlight) {
    requireCampaignInProject(campaignId);
    String projectId = scope.requireProjectId();
    boolean running = this.inFlight.resolve(inFlight, campaigns.entity(entityId));
    CampaignService.Member added =
        campaigns.addMember(campaignId, entityId, position, running, changedBy());
    announce(projectId);
    return views.member(added);
  }

  @McpServer("repository")
  @Tool(
      name = "move_campaign_member",
      description =
          "Move a campaign member to a new position (clamped to the last one), renumbering the span"
              + " between. Never touches any member's condition: the dependency between members is"
              + " the criteria, not the order, so moving a member does not change what it or anything"
              + " else waits on.")
  public CampaignDto moveCampaignMember(
      @ToolArg(description = "id of a campaign in this project") String campaignId,
      @ToolArg(description = "id of a member of this campaign") String membershipId,
      @ToolArg(description = "the new position, zero-based; clamped to the last one") int position) {
    requireCampaignInProject(campaignId);
    String projectId = scope.requireProjectId();
    CampaignService.Campaign moved =
        campaigns.moveMember(campaignId, membershipId, position, changedBy());
    announce(projectId);
    return views.campaign(moved);
  }

  @McpServer("repository")
  @Tool(
      name = "remove_campaign_member",
      description =
          "Remove a member from a campaign, along with its condition. Refused with a 409 once the"
              + " member is claimed — its run record would be lost — and refused while another"
              + " member's condition still targets this one; edit that member's condition first, or"
              + " see which members wait on it with get_campaign.")
  public String removeCampaignMember(
      @ToolArg(description = "id of a campaign in this project") String campaignId,
      @ToolArg(description = "id of a member of this campaign") String membershipId) {
    requireCampaignInProject(campaignId);
    String projectId = scope.requireProjectId();
    campaigns.removeMember(campaignId, membershipId, changedBy());
    announce(projectId);
    return "Removed member " + membershipId + " from campaign " + campaignId;
  }

  @McpServer("repository")
  @Tool(
      name = "set_campaign_member_condition",
      description =
          "Replace a member's whole condition — PUT semantics: every group and criterion you state"
              + " here is the condition afterwards, and any you leave out is deleted. The condition is"
              + " OR'd groups of AND'd criteria — the member waits until at least one group has every"
              + " one of its criteria satisfied — and an empty group is refused (it would hold at"
              + " once, which is never what stating one means). Pass an empty list of groups to make"
              + " the member wait on nothing. Restate an existing criterion by its id to keep its"
              + " satisfied marker; a criterion given no id is a new one. There are four kinds, each"
              + " with its own predicate fields: ENTITY_STATUS {entityId, status} — another member of"
              + " THIS campaign reaches that lifecycle status; DEPLOYMENT_ACTIVE {applicationName,"
              + " environmentName?, minimumVersion?} — that application goes live, optionally in one"
              + " environment and at least one version; SCM_RELEASE {repositoryName, projectId?,"
              + " minimumVersion?} — that repository releases, optionally in one project and at least"
              + " one version; APPROVAL {} — a person's yes, which only a person gives (there is no"
              + " tool here for it). Refused with a 409 once the member is claimed: a claimed member's"
              + " condition is settled and does not change.")
  public CampaignMemberDto setCampaignMemberCondition(
      @ToolArg(description = "id of a campaign in this project") String campaignId,
      @ToolArg(description = "id of a member of this campaign") String membershipId,
      @ToolArg(
              description =
                  "the whole condition: a list of OR'd groups, each a list of AND'd criteria"
                      + " ({id?, kind, predicate}); an empty list means the member waits on nothing")
          List<CampaignController.ConditionGroup> groups) {
    requireCampaignInProject(campaignId);
    String projectId = scope.requireProjectId();
    List<CampaignService.GroupSpec> specs = CampaignController.toGroupSpecs(groups);
    CampaignService.Member member =
        campaigns.setCondition(campaignId, membershipId, specs, changedBy());
    announce(projectId);
    return views.member(member);
  }

  // --- Scoping ------------------------------------------------------------------

  /**
   * Ensures {@code campaignId} names a campaign of the scoped project. A campaign elsewhere reads as
   * not found rather than as forbidden — the model is told nothing about what other projects hold.
   */
  private WorkEntity requireCampaignInProject(String campaignId) {
    WorkEntity campaign = workEntities.get(Archetype.CAMPAIGN, campaignId);
    if (!scope.requireProjectId().equals(campaign.projectId)) {
      throw new NotFoundException("Campaign not found in this project: " + campaignId);
    }
    return campaign;
  }

  // --- Plumbing -----------------------------------------------------------------

  /** Tell the project's browsers to re-read the campaign — the same {@code EPICS} channel. */
  private void announce(String projectId) {
    changePublisher.fire(projectId, ProjectChangeHint.Topic.EPICS);
  }

  /** The audit's {@code changed_by}: the forwarded user, else the agent marker. */
  private String changedBy() {
    if (identity == null || identity.isAnonymous() || identity.getPrincipal() == null) {
      return AGENT;
    }
    return identity.getPrincipal().getName();
  }
}
