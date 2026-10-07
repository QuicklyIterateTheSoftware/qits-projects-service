package eu.wohlben.qits.entities.api;

import eu.wohlben.qits.entities.dto.AuditEntryDto;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
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
 * <b>A work entity's change history: {@code GET /projects/api/work/{qualifiedId}/audit}</b>
 * (qits-970, epic qits-965) — the {@code /work} home of the deleted {@code GET /epics/{id}/audit}, for every
 * archetype the audit store keys: a root (epic, ticket, campaign) answers its whole subtree, a
 * feature or a task its own rows, newest first. The log outlives the rows, so a UUID naming a
 * deleted root still answers its history ({@link WorkEntityDoors#audit}). A read: {@code
 * qits:agent} too.
 */
@Path("/work/{qualifiedId}/audit")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"qits:admin", "qits:admin-agent", "qits:agent"})
public class WorkAuditController {

  @Inject WorkEntityDoors doors;

  @Schema(name = "WorkAudit", description = "A work entity's change history, newest first.")
  public record WorkAudit(List<AuditEntryDto> entries) {}

  @GET
  @Operation(
      operationId = "getWorkAudit",
      summary = "A work entity's change history",
      description =
          "Newest first. A root (epic, ticket, campaign) answers its whole subtree — its features,"
              + " tasks and every thread under it, deleted rows included; a feature or a task its"
              + " own rows. The path names the entity by qualified id (<projectSlug>-<n>) or UUID; a"
              + " UUID still answers after the entity is deleted.")
  @APIResponse(
      responseCode = "200",
      description = "The history",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(implementation = WorkAudit.class)))
  @APIResponse(responseCode = "404", description = "A qualified id naming no entity")
  public WorkAudit audit(@PathParam("qualifiedId") String qualifiedId) {
    return new WorkAudit(doors.audit(qualifiedId));
  }
}
