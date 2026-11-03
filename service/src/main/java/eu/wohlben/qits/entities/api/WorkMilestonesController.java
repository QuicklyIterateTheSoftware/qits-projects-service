package eu.wohlben.qits.entities.api;

import eu.wohlben.qits.entities.milestone.MilestoneRef;
import eu.wohlben.qits.projects.entitieshost.EntityIdResolver;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
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
 * <b>What a work entity waits on: {@code /projects/api/work/{qualifiedId}/milestones}</b> (epic
 * qits-615) — the read, the attach and the detach. Any archetype may wait on a milestone of its own
 * project. {@code {qualifiedId}} is a qualified id or a UUID ({@link EntityIdResolver#resolve}). The
 * read admits {@code qits:agent}; the attach and the detach admit it bound to the entity's project
 * ({@link MilestoneDoors}). Both writes answer what the entity waits on afterwards.
 */
@Path("/work/{qualifiedId}/milestones")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"qits:admin", "qits:admin-agent", "qits:desk-admin", "qits:agent"})
public class WorkMilestonesController {

  @Inject EntityIdResolver ids;

  @Inject MilestoneDoors doors;

  @Inject SecurityIdentity identity;

  @Schema(
      name = "WorkMilestoneList",
      description = "The milestones a work entity waits on, in the order attached.")
  public record WorkMilestoneList(List<MilestoneRef> milestones) {}

  @GET
  @Operation(
      operationId = "listWorkMilestones",
      summary = "The milestones a work entity waits on",
      description =
          "Each its id, its displayName and since when it is met (satisfiedAt, null while it is"
              + " not). The entity needs something while any is unmet.")
  @APIResponse(
      responseCode = "200",
      description = "The milestones",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(implementation = WorkMilestoneList.class)))
  @APIResponse(responseCode = "404", description = "No entity with this id")
  public WorkMilestoneList list(@PathParam("qualifiedId") String qualifiedId) {
    return new WorkMilestoneList(doors.ofEntity(ids.resolve(qualifiedId)));
  }

  @PUT
  @Path("/{milestoneId}")
  @Operation(
      operationId = "attachWorkMilestone",
      summary = "A work entity waits on a milestone",
      description =
          "Idempotent. The milestone must be of the entity's project. Answers what the entity waits"
              + " on now. A bound agent attaches only in its own project.")
  @APIResponse(
      responseCode = "200",
      description = "The milestones it waits on now",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(implementation = WorkMilestoneList.class)))
  @APIResponse(responseCode = "403", description = "An agent outside its own project")
  @APIResponse(responseCode = "404", description = "No entity, or no milestone, with this id")
  @APIResponse(responseCode = "409", description = "The milestone is of another project")
  public WorkMilestoneList attach(
      @PathParam("qualifiedId") String qualifiedId, @PathParam("milestoneId") String milestoneId) {
    return new WorkMilestoneList(doors.attach(identity, ids.resolve(qualifiedId), milestoneId));
  }

  @DELETE
  @Path("/{milestoneId}")
  @Operation(
      operationId = "detachWorkMilestone",
      summary = "A work entity no longer waits on a milestone",
      description =
          "The milestone itself stays, and so does every campaign membership waiting on it. Answers"
              + " what the entity waits on now. A bound agent detaches only in its own project.")
  @APIResponse(
      responseCode = "200",
      description = "The milestones it waits on now",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(implementation = WorkMilestoneList.class)))
  @APIResponse(responseCode = "403", description = "An agent outside its own project")
  @APIResponse(
      responseCode = "404",
      description = "No entity with this id, or it does not wait on this milestone")
  public WorkMilestoneList detach(
      @PathParam("qualifiedId") String qualifiedId, @PathParam("milestoneId") String milestoneId) {
    return new WorkMilestoneList(doors.detach(identity, ids.resolve(qualifiedId), milestoneId));
  }
}
