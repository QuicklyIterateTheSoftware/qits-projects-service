package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.entities.error.BadRequestException;
import eu.wohlben.qits.projects.entitieshost.EntityIdResolver;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * <b>An agent session's idle state: {@code POST /projects/api/work/{qualifiedId}/agent-waiting}</b>
 * (qits-895) — the source of the derived block. Every rule is {@link AgentWaiting}'s.
 *
 * <pre>
 *   POST /work/{qualifiedId}/agent-waiting   {waiting, cause, sessionId, at}   → 204
 * </pre>
 *
 * <p>{@code qits:system} alone: the caller is the platform side that watches a session's hooks
 * (qits-workspaces), never a person and never the agent itself — an agent saying it is stuck has
 * {@code block_entity}, which states a reason. The path takes the entity's UUID, which is what that
 * caller holds; a qualified id resolves too, like every {@code /work} path. It answers 204 for every
 * frame about a known entity, an ignored one included, and 404 for an id naming nothing. In {@code
 * projects.api} for {@link EntityBlocks}' reason: which statuses derive a block is a phase question.
 */
@Path("/work/{qualifiedId}/agent-waiting")
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("qits:system")
public class WorkAgentWaitingController {

  @Inject EntityIdResolver ids;

  @Inject AgentWaiting waiting;

  /** One frame of a session's state. */
  @Schema(
      name = "WorkAgentWaitingReport",
      description = "Whether the agent session working an entity is waiting for a person.")
  public record WorkAgentWaitingReport(
      @Schema(
              required = true,
              description =
                  "true when the session ended its turn with nothing in flight; false when it is"
                      + " working again")
          Boolean waiting,
      @Schema(description = "What the session says it is waiting for (the hook that reported it)")
          String cause,
      @Schema(description = "The reporting session, for the log") String sessionId,
      @Schema(
              description =
                  "When the session stamped the frame, epoch milliseconds; absent reads as now. A"
                      + " frame older than the entity's last activity is ignored")
          Long at) {}

  @POST
  @Operation(
      operationId = "reportWorkAgentWaiting",
      summary = "Report whether the agent session on a work entity is waiting for a person",
      description =
          "A session that stays waiting past the debounce makes the entity read as blocked"
              + " (blockSource AGENT_WAITING) without setting its explicit block; waiting=false"
              + " clears it. Ignored for a status that starts no phase, a feature or a task, and a"
              + " frame older than the entity's last activity. Never refuses a dispatch.")
  @APIResponse(responseCode = "204", description = "The frame was taken (or ignored)")
  @APIResponse(responseCode = "400", description = "No waiting value")
  @APIResponse(responseCode = "404", description = "No entity with this id")
  public Response report(
      @PathParam("qualifiedId") String qualifiedId, WorkAgentWaitingReport report) {
    if (report == null || report.waiting() == null) {
      throw new BadRequestException("waiting is required: true or false.");
    }
    waiting.report(
        ids.resolve(qualifiedId),
        report.waiting(),
        report.cause(),
        report.sessionId(),
        report.at() == null ? null : Instant.ofEpochMilli(report.at()));
    return Response.noContent().build();
  }
}
