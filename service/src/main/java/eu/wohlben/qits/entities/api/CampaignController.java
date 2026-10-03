package eu.wohlben.qits.entities.api;

import eu.wohlben.qits.entities.api.CampaignDtos.CampaignDto;
import eu.wohlben.qits.entities.api.CampaignDtos.CampaignMemberDto;
import eu.wohlben.qits.entities.api.CampaignDtos.CampaignProgressDto;
import eu.wohlben.qits.entities.campaign.CampaignService;
import eu.wohlben.qits.entities.control.WorkEntityService;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.projects.api.CampaignInFlight;
import eu.wohlben.qits.projects.api.ProjectChangeHint;
import eu.wohlben.qits.projects.api.ProjectChangePublisher;
import eu.wohlben.qits.projects.refinementhost.EntityResolutions;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.Map;

/**
 * <b>One campaign</b> (qits-413): the read, the status transition, and the authoring of its
 * membership — add, move, remove, condition — plus the one person's latch, approve, and the
 * progress read (qits-418).
 *
 * <ul>
 *   <li><b>Every door but approve admits {@code qits:agent}</b>, bound to the agent's own project:
 *       the campaign is resolved first (an unknown id is its 404) and its project checked before the
 *       write ({@link EntitiesAgentAccess}).
 *   <li><b>Approve is {@code qits:admin} alone</b> — it is the sign-off, and the actor is the
 *       caller's principal, never a body field.
 *   <li><b>The transition is the only door that moves a campaign's status</b> — the MIMO door
 *       refuses it — and goes through {@link EntityResolutions} and so through the announcer;
 *       leaving REFINED pauses the campaign's start in the same transaction (the hook is inside
 *       {@code WorkEntityService.transition}, not here).
 *   <li><b>Every write fires the {@code epics} hint</b>, after the service has returned.
 * </ul>
 */
@Path("/campaigns")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@jakarta.annotation.security.RolesAllowed("qits:admin")
public class CampaignController {

  @Inject CampaignService campaigns;

  @Inject CampaignViews views;

  @Inject WorkEntityService workEntities;

  @Inject EntityResolutions resolutions;

  @Inject CampaignInFlight inFlight;

  @Inject ProjectChangePublisher publisher;

  @Inject SecurityIdentity identity;

  public record CampaignResponse(CampaignDto campaign) {}

  public record CampaignMemberResponse(CampaignMemberDto member) {}

  /** The progress read's wrapper — also what the start press answers (qits-418). */
  public record CampaignProgressResponse(CampaignProgressDto progress) {}

  @GET
  @Path("/{id}")
  @org.eclipse.microprofile.openapi.annotations.Operation(
      operationId = "getCampaign",
      summary = "Get",
      description = "One campaign with its members in campaign order.")
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:agent"})
  public CampaignResponse get(@PathParam("id") String id) {
    return new CampaignResponse(views.campaign(campaigns.get(id)));
  }

  /**
   * <b>How the campaign is doing</b> (qits-418): every member's derived state, what it waits for,
   * each criterion judged (the sentence that would latch it, whether it still can, its evidence),
   * and whether the criteria evaluator is listening at all. Derived on every read; nothing stored.
   */
  @GET
  @Path("/{id}/progress")
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:agent"})
  public CampaignProgressResponse progress(@PathParam("id") String id) {
    return new CampaignProgressResponse(views.progress(campaigns.progress(id)));
  }

  public record TransitionCampaignRequest(@NotBlank String target) {}

  /**
   * A lifecycle move of the campaign — {@code REFINED} readies it to be started, leaving REFINED
   * pauses a started one, {@code DROPPED} stops it. A move the lifecycle does not allow is a 409.
   */
  @POST
  @Path("/{id}/transition")
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:agent"})
  public CampaignResponse transition(
      @PathParam("id") String id, @Valid TransitionCampaignRequest request) {
    String projectId = bind(id);
    resolutions.transition(
        Archetype.CAMPAIGN, id, request.target(), EntitiesPrincipal.changedBy(identity));
    publisher.fire(projectId, ProjectChangeHint.Topic.EPICS);
    return new CampaignResponse(views.campaign(campaigns.get(id)));
  }

  /**
   * {@code inFlight} omitted means the service decides: true when the member is IMPLEMENTING,
   * IMPLEMENTED, VERIFIED or DONE, or an ACTIVE workspace stands on its branch. The answer's {@code
   * joinedRunning} echoes what was decided.
   */
  public record AddCampaignMemberRequest(
      @NotBlank String entityId, Integer position, Boolean inFlight) {}

  @POST
  @Path("/{id}/members")
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:agent"})
  public CampaignMemberResponse addMember(
      @PathParam("id") String id, @Valid AddCampaignMemberRequest request) {
    String projectId = bind(id);
    boolean running = inFlight.resolve(request.inFlight(), campaigns.entity(request.entityId()));
    CampaignService.Member added =
        campaigns.addMember(
            id,
            request.entityId(),
            request.position(),
            running,
            EntitiesPrincipal.changedBy(identity));
    publisher.fire(projectId, ProjectChangeHint.Topic.EPICS);
    return new CampaignMemberResponse(views.member(added));
  }

  public record MoveCampaignMemberRequest(@NotNull Integer position) {}

  @PUT
  @Path("/{id}/members/{membershipId}/position")
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:agent"})
  public CampaignResponse moveMember(
      @PathParam("id") String id,
      @PathParam("membershipId") String membershipId,
      @Valid MoveCampaignMemberRequest request) {
    String projectId = bind(id);
    CampaignService.Campaign moved =
        campaigns.moveMember(
            id, membershipId, request.position(), EntitiesPrincipal.changedBy(identity));
    publisher.fire(projectId, ProjectChangeHint.Topic.EPICS);
    return new CampaignResponse(views.campaign(moved));
  }

  /** 204. 409 once claimed, and while another member's criterion targets this one. */
  @DELETE
  @Path("/{id}/members/{membershipId}")
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:agent"})
  public void removeMember(
      @PathParam("id") String id, @PathParam("membershipId") String membershipId) {
    String projectId = bind(id);
    campaigns.removeMember(id, membershipId, EntitiesPrincipal.changedBy(identity));
    publisher.fire(projectId, ProjectChangeHint.Topic.EPICS);
  }

  /** One criterion of a condition: {@code id} restates an existing one and keeps its latch. */
  public record ConditionCriterion(String id, String kind, Map<String, Object> predicate) {}

  /** One OR'd group of a condition. */
  public record ConditionGroup(List<ConditionCriterion> criteria) {}

  /** The whole condition — PUT semantics; an empty list means the member waits on nothing. */
  public record SetCampaignMemberConditionRequest(List<ConditionGroup> groups) {}

  /**
   * The wire shape of a condition, translated into {@link CampaignService}'s own spec — shared with
   * {@code CampaignMcpTools}' {@code set_campaign_member_condition}, which takes the identical {@link
   * ConditionGroup}/{@link ConditionCriterion} records as tool arguments, so the two doors cannot
   * translate the wire the same shape two different ways.
   */
  public static List<CampaignService.GroupSpec> toGroupSpecs(List<ConditionGroup> groups) {
    return groups == null
        ? List.of()
        : groups.stream()
            .map(
                group ->
                    group == null || group.criteria() == null
                        ? new CampaignService.GroupSpec(List.of())
                        : new CampaignService.GroupSpec(
                            group.criteria().stream()
                                .map(
                                    criterion ->
                                        criterion == null
                                            ? null
                                            : new CampaignService.CriterionSpec(
                                                criterion.id(),
                                                criterion.kind(),
                                                criterion.predicate()))
                                .toList()))
            .toList();
  }

  @PUT
  @Path("/{id}/members/{membershipId}/condition")
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:agent"})
  public CampaignMemberResponse setCondition(
      @PathParam("id") String id,
      @PathParam("membershipId") String membershipId,
      SetCampaignMemberConditionRequest request) {
    String projectId = bind(id);
    List<CampaignService.GroupSpec> groups =
        toGroupSpecs(request == null ? null : request.groups());
    CampaignService.Member member =
        campaigns.setCondition(id, membershipId, groups, EntitiesPrincipal.changedBy(identity));
    publisher.fire(projectId, ProjectChangeHint.Topic.EPICS);
    return new CampaignMemberResponse(views.member(member));
  }

  public record ApproveCampaignCriterionRequest(String note) {}

  /**
   * A person's yes on an APPROVAL criterion — {@code qits:admin} alone. 409 if the criterion is not
   * APPROVAL, is already satisfied (the message names who and when), or the campaign is DONE or
   * DROPPED.
   */
  @POST
  @Path("/{id}/members/{membershipId}/criteria/{criterionId}/approve")
  public CampaignMemberResponse approve(
      @PathParam("id") String id,
      @PathParam("membershipId") String membershipId,
      @PathParam("criterionId") String criterionId,
      ApproveCampaignCriterionRequest request) {
    String projectId = workEntities.get(Archetype.CAMPAIGN, id).projectId;
    CampaignService.Approved approved =
        campaigns.approve(
            id,
            membershipId,
            criterionId,
            request == null ? null : request.note(),
            identity.isAnonymous() ? null : identity.getPrincipal().getName());
    publisher.fire(projectId, ProjectChangeHint.Topic.EPICS);
    return new CampaignMemberResponse(views.member(approved.member()));
  }

  /** The campaign's project — its 404 first — with a bound agent held to it. */
  private String bind(String campaignId) {
    String projectId = workEntities.get(Archetype.CAMPAIGN, campaignId).projectId;
    EntitiesAgentAccess.requireProject(identity, projectId);
    return projectId;
  }
}
