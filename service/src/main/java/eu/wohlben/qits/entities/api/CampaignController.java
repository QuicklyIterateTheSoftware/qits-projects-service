package eu.wohlben.qits.entities.api;

import eu.wohlben.qits.entities.api.CampaignDtos.CampaignDto;
import eu.wohlben.qits.entities.api.CampaignDtos.CampaignMemberDto;
import eu.wohlben.qits.entities.api.CampaignDtos.CampaignProgressDto;
import eu.wohlben.qits.entities.campaign.CampaignService;
import eu.wohlben.qits.projects.refinementhost.EntityResolutions;
import eu.wohlben.qits.projects.security.PersonCheck;
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
 *   <li><b>Approve is a person alone</b> — it is the sign-off: {@code qits:admin}, then {@link
 *       PersonCheck}, and the actor is the name from that proof, never a body field nor a
 *       forwarded header.
 *   <li><b>The transition is the only door that moves a campaign's status</b> — the MIMO door
 *       refuses it — and goes through {@link EntityResolutions} and so through the announcer;
 *       leaving REFINED and READY_FOR_DEV pauses the campaign's start in the same transaction (the
 *       hook is inside {@code WorkEntityService.transition}, not here); the move between those two
 *       neither starts nor pauses it (qits-887).
 *   <li><b>Every write fires the {@code epics} hint</b>, after the service has returned.
 * </ul>
 */
@Path("/campaigns")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@jakarta.annotation.security.RolesAllowed("qits:admin")
public class CampaignController {

  /** Every rule behind these routes, shared with the {@code /work} family (qits-970). */
  @Inject CampaignDoors doors;

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
    return new CampaignResponse(doors.campaign(id));
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
    return new CampaignProgressResponse(doors.progress(id));
  }

  public record TransitionCampaignRequest(@NotBlank String target) {}

  /**
   * A lifecycle move of the campaign — {@code REFINED} readies it to be started, leaving REFINED
   * and READY_FOR_DEV pauses a started one, {@code DROPPED} stops it. A move the lifecycle does not
   * allow is a 409. REFINED → READY_FOR_DEV is a person's move (qits-887, {@code PERSON_APPROVAL}):
   * the caller is built into a {@code Mover} by {@link EntityMovers}, and a machine is refused with a
   * 409 — as is anybody while a member that is not DROPPED is still before READY_FOR_DEV ({@code
   * MEMBERS_SCHEDULED}). The move neither starts nor pauses the campaign.
   */
  @POST
  @Path("/{id}/transition")
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:agent"})
  public CampaignResponse transition(
      @PathParam("id") String id, @Valid TransitionCampaignRequest request) {
    return new CampaignResponse(doors.transition(identity, id, request.target()));
  }

  /**
   * {@code inFlight} omitted means the service decides: true when the member is IMPLEMENTING,
   * IMPLEMENTED, VERIFYING, VERIFIED or DONE, or an ACTIVE workspace stands on its branch. The answer's {@code
   * joinedRunning} echoes what was decided.
   */
  public record AddCampaignMemberRequest(
      @NotBlank String entityId, Integer position, Boolean inFlight) {}

  @POST
  @Path("/{id}/members")
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:agent"})
  public CampaignMemberResponse addMember(
      @PathParam("id") String id, @Valid AddCampaignMemberRequest request) {
    return new CampaignMemberResponse(
        doors.addMember(
            identity, id, request.entityId(), request.position(), request.inFlight()));
  }

  public record MoveCampaignMemberRequest(@NotNull Integer position) {}

  @PUT
  @Path("/{id}/members/{membershipId}/position")
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:agent"})
  public CampaignResponse moveMember(
      @PathParam("id") String id,
      @PathParam("membershipId") String membershipId,
      @Valid MoveCampaignMemberRequest request) {
    return new CampaignResponse(doors.moveMember(identity, id, membershipId, request.position()));
  }

  /** 204. 409 once claimed, and while another member's criterion targets this one. */
  @DELETE
  @Path("/{id}/members/{membershipId}")
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:agent"})
  public void removeMember(
      @PathParam("id") String id, @PathParam("membershipId") String membershipId) {
    doors.removeMember(identity, id, membershipId);
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
    List<CampaignService.GroupSpec> groups =
        toGroupSpecs(request == null ? null : request.groups());
    return new CampaignMemberResponse(doors.setCondition(identity, id, membershipId, groups));
  }

  public record ApproveCampaignCriterionRequest(String note) {}

  /**
   * A person's yes on an APPROVAL criterion — {@code qits:admin} at the door, then a person this
   * service verified itself ({@link PersonCheck}, 403 otherwise), whose name is what is recorded.
   * 409 if the criterion is not APPROVAL, is already satisfied (the message names who and when), or
   * the campaign is DONE or DROPPED.
   */
  @POST
  @Path("/{id}/members/{membershipId}/criteria/{criterionId}/approve")
  public CampaignMemberResponse approve(
      @PathParam("id") String id,
      @PathParam("membershipId") String membershipId,
      @PathParam("criterionId") String criterionId,
      ApproveCampaignCriterionRequest request) {
    return new CampaignMemberResponse(
        doors.approve(id, membershipId, criterionId, request == null ? null : request.note()));
  }
}
