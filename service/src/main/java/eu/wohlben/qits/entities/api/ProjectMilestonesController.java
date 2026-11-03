package eu.wohlben.qits.entities.api;

import eu.wohlben.qits.entities.api.MilestoneDtos.MilestoneDto;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * <b>A project's milestones: {@code /projects/api/projects/{project}/milestones}</b> (epic
 * qits-615) — the list and the create. A milestone is a foreseeable "needs to be met", shared by
 * any number of work entities and campaign memberships; one is {@code GET /milestones/{id}}. {@code
 * {project}} is the project's id or its slug. The read admits {@code qits:agent} unbound; the create
 * admits it bound to its own project ({@link MilestoneDoors}).
 */
@Path("/projects/{project}/milestones")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"qits:admin", "qits:admin-agent", "qits:desk-admin", "qits:agent"})
public class ProjectMilestonesController {

  /** The doors' description of when each criterion kind latches, shared by create and edit. */
  static final String LATCHING =
      " Groups are OR'd and the criteria in a group AND'd; the milestone is met while one group"
          + " holds. Kinds: ENTITY_STATUS {entityId (qualified id or UUID of a work entity of the"
          + " same project), status}, DEPLOYMENT_ACTIVE, SCM_RELEASE, APPROVAL {} (a person's"
          + " approve). ENTITY_STATUS latches from the current state at once when its target already"
          + " stands at or past the status (evidence CURRENT_STATE). DEPLOYMENT_ACTIVE and SCM_RELEASE"
          + " latch only on events that happen after the criterion was made: a deployment or a"
          + " release from before it never meets it.";

  @Inject MilestoneDoors doors;

  @Inject SecurityIdentity identity;

  @Schema(name = "MilestoneList", description = "A project's milestones, oldest first.")
  public record MilestoneList(List<MilestoneDto> milestones) {}

  @Schema(name = "MilestoneAnswer", description = "One milestone.")
  public record MilestoneAnswer(MilestoneDto milestone) {}

  /** One criterion of a milestone as stated: {@code id} restates an existing one, keeping its latch. */
  @Schema(name = "MilestoneCriterionSpec", description = "One criterion of a milestone, as stated.")
  public record MilestoneCriterionSpec(
      @Schema(description = "An existing criterion's id, to keep its latch; omitted states a new one.")
          String id,
      @Schema(required = true, description = "ENTITY_STATUS, DEPLOYMENT_ACTIVE, SCM_RELEASE or APPROVAL")
          String kind,
      @Schema(
              description =
                  "The kind's predicate; an ENTITY_STATUS predicate's entityId takes a qualified id"
                      + " or a UUID. APPROVAL takes {}.")
          java.util.Map<String, Object> predicate) {}

  @Schema(name = "MilestoneGroupSpec", description = "One OR'd group of a milestone, as stated.")
  public record MilestoneGroupSpec(List<MilestoneCriterionSpec> criteria) {}

  @Schema(name = "MilestoneCreate", description = "A new milestone: its name and its condition.")
  public record MilestoneCreate(
      @Schema(required = true, description = "What it is called, e.g. \"bootstrap test node\".")
          String displayName,
      @Schema(required = true, description = "At least one group of at least one criterion.")
          List<MilestoneGroupSpec> groups) {}

  @GET
  @Operation(
      operationId = "listProjectMilestones",
      summary = "List a project's milestones",
      description =
          "Every milestone of the project, oldest first, each whole: its groups and criteria with"
              + " their dates, whether it is met and since when, and what waits on it. The project"
              + " is its id or its slug. met=false lists the unmet ones only — what work still"
              + " needs — and met=true the met ones.")
  @APIResponse(
      responseCode = "200",
      description = "The milestones",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(implementation = MilestoneList.class)))
  @APIResponse(responseCode = "404", description = "No such project")
  public MilestoneList list(
      @PathParam("project") String project,
      @Parameter(description = "Only the met (true) or the unmet (false) milestones.")
          @QueryParam("met")
          Boolean met) {
    return new MilestoneList(doors.list(project, met));
  }

  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  @Operation(
      operationId = "createMilestone",
      summary = "Create a milestone",
      description =
          "A new milestone of the project, named and with its whole condition. A bound agent creates"
              + " only in its own project."
              + LATCHING)
  @APIResponse(
      responseCode = "201",
      description = "The milestone as written, latched from the current state",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(implementation = MilestoneAnswer.class)))
  @APIResponse(
      responseCode = "400",
      description =
          "No name, no groups, an empty group, a predicate the service cannot read, or an"
              + " ENTITY_STATUS target outside the project")
  @APIResponse(responseCode = "403", description = "An agent outside its own project")
  @APIResponse(responseCode = "404", description = "No such project")
  public Response create(@PathParam("project") String project, MilestoneCreate request) {
    MilestoneDto created =
        doors.create(
            identity,
            project,
            request == null ? null : request.displayName(),
            groups(request == null ? null : request.groups()));
    return Response.status(Response.Status.CREATED).entity(new MilestoneAnswer(created)).build();
  }

  /** The wire's groups as the doors read them. */
  static List<MilestoneDoors.GroupSpec> groups(List<MilestoneGroupSpec> groups) {
    if (groups == null) {
      return null;
    }
    return groups.stream()
        .map(
            group ->
                group == null
                    ? null
                    : new MilestoneDoors.GroupSpec(
                        group.criteria() == null
                            ? null
                            : group.criteria().stream()
                                .map(
                                    criterion ->
                                        criterion == null
                                            ? null
                                            : new MilestoneDoors.CriterionSpec(
                                                criterion.id(),
                                                criterion.kind(),
                                                criterion.predicate()))
                                .toList()))
        .toList();
  }
}
