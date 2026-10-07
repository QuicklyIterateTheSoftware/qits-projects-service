package eu.wohlben.qits.entities.api;

import eu.wohlben.qits.entities.api.CampaignDtos.CampaignMemberDto;
import eu.wohlben.qits.entities.campaign.CampaignService;
import eu.wohlben.qits.entities.error.NotFoundException;
import eu.wohlben.qits.projects.entitieshost.EntityIdResolver;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
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
import jakarta.ws.rs.core.Response;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * <b>A campaign's membership: {@code /projects/api/work/{qualifiedId}/members}</b> (qits-970, epic
 * qits-965) — the {@code /work} home of the deleted {@code GET /campaigns/{id}} (the members; the campaign's own
 * fields are {@code GET /work/{qualifiedId}}'s, its start {@code …/progress}') and of every
 * membership write on {@code /campaigns/{id}/members…}: add, move, remove, condition, and the
 * approve of a gated member's criterion.
 *
 * <p>{@code {qualifiedId}} is the campaign's qualified id or UUID ({@link EntityIdResolver#resolve});
 * an id naming another archetype is the campaign read's 404. <b>Every entity id in a body takes a
 * qualified id too</b>: the added member's {@code entityId}, and an {@code ENTITY_STATUS} criterion's
 * {@code predicate.entityId}. The rules are {@link CampaignDoors}', shared with the campaign routes,
 * and so are the roles: every door admits {@code qits:agent}, bound to the campaign's project,
 * except approve — a person's sign-off, {@code qits:admin} and then a verified person.
 * {@code qits:admin-agent} is admitted too, everywhere {@code qits:admin} is, approve included
 * (qits-628 follow-up); remove it from approve if that door must stay human-only — the verified
 * person check beneath it still refuses a commissioned bearer today.
 *
 * <p>The move and the remove answer the membership as they left it, {@code {"members": […]}}, the
 * list the read answers; the add, the condition and the approve answer the one member.
 */
@Path("/work/{qualifiedId}/members")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"qits:admin", "qits:admin-agent"})
public class WorkMembersController {

  @Inject EntityIdResolver ids;

  @Inject CampaignDoors doors;

  @Inject SecurityIdentity identity;

  @Schema(name = "WorkMemberList", description = "A campaign's members, in campaign order.")
  public record WorkMemberList(List<CampaignMemberDto> members) {}

  @Schema(name = "WorkMemberAnswer", description = "One membership of a campaign.")
  public record WorkMemberAnswer(CampaignMemberDto member) {}

  /**
   * {@code inFlight} omitted means the service decides: true when the member is IMPLEMENTING or
   * later, or an ACTIVE workspace stands on its branch. The answer's {@code joinedRunning} echoes
   * what was decided.
   */
  @Schema(name = "WorkMemberAdd", description = "An entity to gather into the campaign.")
  public record WorkMemberAdd(
      @NotBlank
          @Schema(
              required = true,
              description = "The entity to gather, by qualified id (<projectSlug>-<n>) or UUID.")
          String entityId,
      @Schema(description = "Where it goes, 0-based; omitted appends.") Integer position,
      @Schema(
              description =
                  "Whether it joins already running; omitted lets the service decide from its"
                      + " status and its workspace.")
          Boolean inFlight) {}

  @Schema(name = "WorkMemberMove", description = "Where a membership should now sit.")
  public record WorkMemberMove(
      @NotNull @Schema(required = true, description = "The new position, 0-based.")
          Integer position) {}

  /** One criterion of a condition: {@code id} restates an existing one and keeps its latch. */
  @Schema(name = "WorkConditionCriterion", description = "One criterion of a member's condition.")
  public record WorkConditionCriterion(
      @Schema(description = "An existing criterion's id, to keep its latch; omitted mints one.")
          String id,
      @Schema(description = "ENTITY_STATUS, APPROVAL, …") String kind,
      @Schema(
              description =
                  "The kind's predicate; an ENTITY_STATUS predicate's entityId takes a qualified id"
                      + " or a UUID.")
          Map<String, Object> predicate) {}

  @Schema(name = "WorkConditionGroup", description = "One OR'd group of a member's condition.")
  public record WorkConditionGroup(List<WorkConditionCriterion> criteria) {}

  /** The whole condition — PUT semantics; an empty list means the member waits on nothing. */
  @Schema(
      name = "WorkMemberCondition",
      description = "A member's whole condition: groups OR'd, criteria in a group AND'd.")
  public record WorkMemberCondition(List<WorkConditionGroup> groups) {}

  @Schema(name = "WorkCriterionApproval", description = "A person's yes on an APPROVAL criterion.")
  public record WorkCriterionApproval(@Schema(description = "Why, optionally.") String note) {}

  // --- the routes ------------------------------------------------------------------------------

  @GET
  @RolesAllowed({"qits:admin", "qits:admin-agent", "qits:agent"})
  @Operation(
      operationId = "listWorkMembers",
      summary = "A campaign's members",
      description =
          "Every membership in campaign order: the entity it gathers, its run record and its"
              + " condition. The path names the campaign by qualified id (<projectSlug>-<n>) or"
              + " UUID.")
  @APIResponse(
      responseCode = "200",
      description = "The members",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(implementation = WorkMemberList.class)))
  @APIResponse(responseCode = "404", description = "No campaign with this id")
  public WorkMemberList list(@PathParam("qualifiedId") String qualifiedId) {
    return new WorkMemberList(doors.campaign(campaignId(qualifiedId)).members());
  }

  @POST
  @RolesAllowed({"qits:admin", "qits:admin-agent", "qits:agent"})
  @Operation(
      operationId = "addWorkMember",
      summary = "Gather an entity into a campaign",
      description =
          "Adds a membership — at position, or at the end — for an entity named by qualified id"
              + " or UUID. A bound agent adds only in its own project.")
  @APIResponse(
      responseCode = "201",
      description = "The membership as written",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(implementation = WorkMemberAnswer.class)))
  @APIResponse(responseCode = "400", description = "No entityId")
  @APIResponse(responseCode = "403", description = "An agent outside its own project")
  @APIResponse(responseCode = "404", description = "No campaign, or no entity, with this id")
  @APIResponse(
      responseCode = "409",
      description = "The entity cannot join: already a member, a feature or a task, a closed campaign")
  public Response add(
      @PathParam("qualifiedId") String qualifiedId, @Valid WorkMemberAdd request) {
    String campaignId = campaignId(qualifiedId);
    CampaignMemberDto added =
        doors.addMember(
            identity,
            campaignId,
            ids.resolve(request.entityId()).id,
            request.position(),
            request.inFlight());
    return Response.status(Response.Status.CREATED).entity(new WorkMemberAnswer(added)).build();
  }

  @PUT
  @Path("/{membershipId}/position")
  @RolesAllowed({"qits:admin", "qits:admin-agent", "qits:agent"})
  @Operation(
      operationId = "moveWorkMember",
      summary = "Move a membership within its campaign",
      description = "Answers the members as the move left them.")
  @APIResponse(
      responseCode = "200",
      description = "The members, in their new order",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(implementation = WorkMemberList.class)))
  @APIResponse(responseCode = "403", description = "An agent outside its own project")
  @APIResponse(responseCode = "404", description = "No campaign, or no such membership on it")
  public WorkMemberList move(
      @PathParam("qualifiedId") String qualifiedId,
      @PathParam("membershipId") String membershipId,
      @Valid WorkMemberMove request) {
    return new WorkMemberList(
        doors
            .moveMember(identity, campaignId(qualifiedId), membershipId, request.position())
            .members());
  }

  @DELETE
  @Path("/{membershipId}")
  @RolesAllowed({"qits:admin", "qits:admin-agent", "qits:agent"})
  @Operation(
      operationId = "removeWorkMember",
      summary = "Remove a membership from its campaign",
      description =
          "Refused once the member was claimed, and while another member's criterion targets it."
              + " Answers the members that remain.")
  @APIResponse(
      responseCode = "200",
      description = "The members that remain",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(implementation = WorkMemberList.class)))
  @APIResponse(responseCode = "403", description = "An agent outside its own project")
  @APIResponse(responseCode = "404", description = "No campaign, or no such membership on it")
  @APIResponse(responseCode = "409", description = "Claimed, or targeted by another member")
  public WorkMemberList remove(
      @PathParam("qualifiedId") String qualifiedId,
      @PathParam("membershipId") String membershipId) {
    String campaignId = campaignId(qualifiedId);
    doors.removeMember(identity, campaignId, membershipId);
    return new WorkMemberList(doors.campaign(campaignId).members());
  }

  @PUT
  @Path("/{membershipId}/condition")
  @RolesAllowed({"qits:admin", "qits:admin-agent", "qits:agent"})
  @Operation(
      operationId = "setWorkMemberCondition",
      summary = "Replace a membership's condition",
      description =
          "PUT semantics: the whole condition, groups OR'd and criteria in a group AND'd; an empty"
              + " list waits on nothing. A criterion restating an existing id keeps its latch. An"
              + " ENTITY_STATUS predicate's entityId takes a qualified id or a UUID.")
  @APIResponse(
      responseCode = "200",
      description = "The member with its condition",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(implementation = WorkMemberAnswer.class)))
  @APIResponse(responseCode = "400", description = "A criterion the service cannot read")
  @APIResponse(responseCode = "403", description = "An agent outside its own project")
  @APIResponse(responseCode = "404", description = "No campaign, or no such membership on it")
  public WorkMemberAnswer setCondition(
      @PathParam("qualifiedId") String qualifiedId,
      @PathParam("membershipId") String membershipId,
      WorkMemberCondition request) {
    return new WorkMemberAnswer(
        doors.setCondition(
            identity,
            campaignId(qualifiedId),
            membershipId,
            groupSpecs(request == null ? null : request.groups())));
  }

  @POST
  @Path("/{membershipId}/criteria/{criterionId}/approve")
  @Operation(
      operationId = "approveWorkMemberCriterion",
      summary = "A person's yes on a member's APPROVAL criterion",
      description =
          "qits:admin, and then a person this service verified itself; the name recorded is the"
              + " proof's. Refused when the criterion is not APPROVAL, is already satisfied, or the"
              + " campaign is DONE or DROPPED.")
  @APIResponse(
      responseCode = "200",
      description = "The member with the criterion satisfied",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(implementation = WorkMemberAnswer.class)))
  @APIResponse(responseCode = "403", description = "Not a verified person")
  @APIResponse(responseCode = "404", description = "No campaign, membership or criterion")
  @APIResponse(responseCode = "409", description = "Not an APPROVAL, already satisfied, or closed")
  public WorkMemberAnswer approve(
      @PathParam("qualifiedId") String qualifiedId,
      @PathParam("membershipId") String membershipId,
      @PathParam("criterionId") String criterionId,
      WorkCriterionApproval request) {
    return new WorkMemberAnswer(
        doors.approve(
            campaignId(qualifiedId),
            membershipId,
            criterionId,
            request == null ? null : request.note()));
  }

  // --- the ids ---------------------------------------------------------------------------------

  private String campaignId(String qualifiedId) {
    return ids.resolve(qualifiedId).id;
  }

  /**
   * The wire's condition as the service's spec, with an {@code ENTITY_STATUS} predicate's {@code
   * entityId} resolved from a qualified id. A value naming nothing is handed on unchanged, for the
   * service's own refusal about it.
   */
  private List<CampaignService.GroupSpec> groupSpecs(List<WorkConditionGroup> groups) {
    if (groups == null) {
      return List.of();
    }
    return groups.stream()
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
                                            predicate(criterion.predicate())))
                            .toList()))
        .toList();
  }

  private Map<String, Object> predicate(Map<String, Object> predicate) {
    if (predicate == null || !(predicate.get("entityId") instanceof String named)) {
      return predicate;
    }
    Map<String, Object> resolved = new LinkedHashMap<>(predicate);
    try {
      resolved.put("entityId", ids.resolve(named).id);
    } catch (NotFoundException unknown) {
      // Left as named: the service says what is wrong with it, as on the campaign routes.
    }
    return resolved;
  }
}
