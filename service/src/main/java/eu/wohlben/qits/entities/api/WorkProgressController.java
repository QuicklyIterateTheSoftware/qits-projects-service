package eu.wohlben.qits.entities.api;

import eu.wohlben.qits.entities.api.CampaignDtos.CampaignProgressDto;
import eu.wohlben.qits.projects.entitieshost.EntityIdResolver;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * <b>How a campaign is doing: {@code GET /projects/api/work/{qualifiedId}/progress}</b> (qits-970,
 * epic qits-965) — the {@code /work} home of the deleted {@code GET /campaigns/{id}/progress}, addressed by
 * qualified id or UUID ({@link EntityIdResolver#resolve}). Every member's derived state, what it
 * waits for, each criterion judged, and whether the criteria evaluator is listening; derived on
 * every read, nothing stored ({@link CampaignDoors#progress}). A read: {@code qits:agent} too. The
 * progress is a campaign's — any other archetype is the campaign read's 404.
 *
 * <p>Its answer, {@code {"progress": …}}, is also what {@code POST /work/{qualifiedId}/dispatch}
 * answers on a campaign: the start press.
 */
@Path("/work/{qualifiedId}/progress")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"qits:admin", "qits:agent"})
public class WorkProgressController {

  @Inject EntityIdResolver ids;

  @Inject CampaignDoors doors;

  @Schema(name = "WorkProgressAnswer", description = "A campaign's progress, derived on the read.")
  public record WorkProgressAnswer(CampaignProgressDto progress) {}

  @GET
  @Operation(
      operationId = "getWorkProgress",
      summary = "How a campaign is doing",
      description =
          "Every member's derived state, what it waits for, each criterion judged (the sentence"
              + " that would latch it, whether it still can, its evidence), and whether the criteria"
              + " evaluator is listening. The path names the campaign by qualified id"
              + " (<projectSlug>-<n>) or UUID.")
  @APIResponse(
      responseCode = "200",
      description = "The progress",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(implementation = WorkProgressAnswer.class)))
  @APIResponse(responseCode = "404", description = "No campaign with this id")
  public WorkProgressAnswer progress(@PathParam("qualifiedId") String qualifiedId) {
    return new WorkProgressAnswer(doors.progress(ids.resolve(qualifiedId).id));
  }
}
