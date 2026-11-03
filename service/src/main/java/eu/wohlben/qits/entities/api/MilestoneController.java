package eu.wohlben.qits.entities.api;

import eu.wohlben.qits.entities.api.ProjectMilestonesController.MilestoneAnswer;
import eu.wohlben.qits.entities.api.ProjectMilestonesController.MilestoneGroupSpec;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * <b>One milestone: {@code /projects/api/milestones/{milestoneId}}</b> (epic qits-615) — the read,
 * the rename, the whole replace of its condition, and a person's approve of an APPROVAL criterion.
 * The read admits {@code qits:agent}; the rename and the replace admit it bound to the milestone's
 * project; the approve is a person's (or the project's delivery desk's), as a campaign criterion's
 * is ({@link MilestoneDoors}).
 */
@Path("/milestones")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"qits:admin", "qits:admin-agent", "qits:desk-admin", "qits:agent"})
public class MilestoneController {

  @Inject MilestoneDoors doors;

  @Inject SecurityIdentity identity;

  @Schema(name = "MilestoneRename", description = "A milestone's new name.")
  public record MilestoneRename(@Schema(required = true) String displayName) {}

  @Schema(name = "MilestoneGroups", description = "A milestone's whole condition.")
  public record MilestoneGroups(
      @Schema(required = true, description = "At least one group of at least one criterion.")
          List<MilestoneGroupSpec> groups) {}

  @Schema(name = "MilestoneApproval", description = "A person's yes on an APPROVAL criterion.")
  public record MilestoneApproval(@Schema(description = "Why, optionally.") String note) {}

  @GET
  @Path("/{milestoneId}")
  @Operation(
      operationId = "getMilestone",
      summary = "Read one milestone",
      description =
          "The milestone with its groups and criteria — each with createdAt and satisfiedAt, a"
              + " criterion with its evidence or approval — whether it is met and since when, the"
              + " work entities that wait on it (qualified id, title, status) and the campaign"
              + " memberships it gates.")
  @APIResponse(
      responseCode = "200",
      description = "The milestone",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(implementation = MilestoneAnswer.class)))
  @APIResponse(responseCode = "404", description = "No milestone with this id")
  public MilestoneAnswer get(@PathParam("milestoneId") String milestoneId) {
    return new MilestoneAnswer(doors.get(milestoneId));
  }

  @PATCH
  @Path("/{milestoneId}")
  @Consumes({MediaType.APPLICATION_JSON, WorkEntityDoors.MERGE_PATCH_JSON})
  @Operation(
      operationId = "renameMilestone",
      summary = "Rename a milestone",
      description =
          "Changes its displayName and nothing else: no criterion, latch or date moves, and no"
              + " event is sent. A bound agent renames only in its own project.")
  @APIResponse(
      responseCode = "200",
      description = "The milestone renamed",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(implementation = MilestoneAnswer.class)))
  @APIResponse(responseCode = "400", description = "No name")
  @APIResponse(responseCode = "403", description = "An agent outside its own project")
  @APIResponse(responseCode = "404", description = "No milestone with this id")
  public MilestoneAnswer rename(
      @PathParam("milestoneId") String milestoneId, MilestoneRename request) {
    return new MilestoneAnswer(
        doors.rename(identity, milestoneId, request == null ? null : request.displayName()));
  }

  @PUT
  @Path("/{milestoneId}/groups")
  @Operation(
      operationId = "setMilestoneGroups",
      summary = "Replace a milestone's condition",
      description =
          "PUT semantics: every group and criterion. A criterion restating an existing id with the"
              + " same kind and predicate keeps its latch; a changed or new one starts unlatched,"
              + " its floor moved to now; an omitted one is deleted. A milestone that stops holding"
              + " loses its satisfiedAt and MilestoneUnmet is sent; one that comes to hold sends"
              + " MilestoneMet. A bound agent edits only in its own project."
              + ProjectMilestonesController.LATCHING)
  @APIResponse(
      responseCode = "200",
      description = "The milestone as the edit left it",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(implementation = MilestoneAnswer.class)))
  @APIResponse(
      responseCode = "400",
      description =
          "No groups, an empty group, a predicate the service cannot read, a restated id that is"
              + " not this milestone's, or an ENTITY_STATUS target outside the project")
  @APIResponse(responseCode = "403", description = "An agent outside its own project")
  @APIResponse(responseCode = "404", description = "No milestone with this id")
  public MilestoneAnswer setGroups(
      @PathParam("milestoneId") String milestoneId, MilestoneGroups request) {
    return new MilestoneAnswer(
        doors.replaceGroups(
            identity,
            milestoneId,
            ProjectMilestonesController.groups(request == null ? null : request.groups())));
  }

  @POST
  @Path("/{milestoneId}/criteria/{criterionId}/approve")
  @RolesAllowed({"qits:admin", "qits:admin-agent", "qits:desk-admin", "qits:desk"})
  @Operation(
      operationId = "approveMilestoneCriterion",
      summary = "A person's yes on a milestone's APPROVAL criterion",
      description =
          "qits:admin, and then a person this service verified itself; the name recorded is the"
              + " proof's. The milestone's own project's delivery desk (qits:desk, its own delivery"
              + " token) may approve too, recorded under its token subject. Sends MilestoneMet when"
              + " the approval makes the milestone hold. Refused when the criterion is not"
              + " APPROVAL or is already approved.")
  @APIResponse(
      responseCode = "200",
      description = "The milestone with the criterion approved",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(implementation = MilestoneAnswer.class)))
  @APIResponse(
      responseCode = "403",
      description = "Neither a verified person nor this milestone's project's delivery desk")
  @APIResponse(responseCode = "404", description = "No milestone, or no such criterion on it")
  @APIResponse(responseCode = "409", description = "Not an APPROVAL, or already approved")
  public MilestoneAnswer approve(
      @PathParam("milestoneId") String milestoneId,
      @PathParam("criterionId") String criterionId,
      MilestoneApproval request) {
    return new MilestoneAnswer(
        doors.approve(milestoneId, criterionId, request == null ? null : request.note()));
  }
}
