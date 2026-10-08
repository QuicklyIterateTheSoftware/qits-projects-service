package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.projects.agenthost.AgentRuntimeStatus;
import eu.wohlben.qits.projects.deskhost.FrontDeskState;
import eu.wohlben.qits.projects.deskhost.FrontDesks;
import eu.wohlben.qits.projects.entity.FrontDeskLifecycle;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * The per-project front desk's lifecycle (qits-767): ensure it is wanted, stop it, delete it, read
 * what it is doing. The paths keep their {@code agent-container} name — the desk <em>is</em> the
 * project's agent container, now placed on a front-desk runner rather than started by this service.
 *
 * <p>One response shape for every route that answers a body. The panel that drives this has one
 * question — "can I talk to the agent yet?" — and renders {@code container.runtimeStatus}: QUEUED
 * while the desk waits for a runner, PROVISIONING once one took it, RUNNING when its inventory says
 * so, UNAVAILABLE while its runner is gone past the reconnect grace, FAILED with {@code
 * failureDetail}. When it is RUNNING the panel opens its terminal through {@code
 * /projects/container/{projectId}/…}, which reaches the desk's daemon down its own tunnel.
 *
 * <p>Everything below the surface is in {@code deskhost/FrontDesks}. This class only names the
 * routes.
 */
// No @Consumes: every route is a verb on a resource and takes no body, and declaring one would make
// a POST with no Content-Type a 415 rather than the action it plainly is.
@Path("/projects/{projectId}/agent-container")
@Produces(MediaType.APPLICATION_JSON)
@jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:admin-agent"})
public class AgentContainerController {

  @Inject FrontDesks frontDesks;

  /** The single response body the ensure, stop and read routes answer with. */
  public record AgentContainerResponse(
      @Schema(description = "The project's front desk, as this service last observed it.")
          ContainerView container) {

    /**
     * @param runtimeStatus what the desk is doing
     * @param daemonConnected whether the in-container daemon holds an open control socket
     * @param daemonVersion the daemon binary's release identity, or null when it has not said or
     *     was built without a version stamp
     * @param pinnedDaemonVersion the daemon build a desk is composed on today — the project-agent
     *     image's pinned tag. Null for {@code ABSENT}.
     * @param daemonVersionStale whether the connected daemon's version is not {@code
     *     pinnedDaemonVersion}. False when no daemon is connected. A stale desk is rolled onto the
     *     pin by the spec roll once it is quiet.
     * @param failureDetail why {@code runtimeStatus} is {@code FAILED}: the runner's launch failure,
     *     the daemon's failed provision, {@code TOKEN_UNAVAILABLE: …} or {@code
     *     EDGE_PLANE_UNCONFIGURED}. Null for every other status.
     * @param runnerId the front-desk runner holding the desk, or null while it is unplaced
     * @param runnerName that runner's name, or null
     * @param lifecycle the project's {@code front_desk.lifecycle} from its {@code project.yml}
     * @param queuedAt since when the desk has waited for a runner, or null
     */
    public record ContainerView(
        AgentRuntimeStatus runtimeStatus,
        boolean daemonConnected,
        String daemonVersion,
        String pinnedDaemonVersion,
        boolean daemonVersionStale,
        String failureDetail,
        UUID runnerId,
        String runnerName,
        FrontDeskLifecycle lifecycle,
        Instant queuedAt) {}

    static AgentContainerResponse of(FrontDeskState state) {
      return new AgentContainerResponse(
          new ContainerView(
              state.runtimeStatus(),
              state.daemonConnected(),
              state.daemonVersion(),
              state.pinnedDaemonVersion(),
              state.daemonVersionStale(),
              state.failureDetail(),
              state.runnerId(),
              state.runnerName(),
              state.lifecycle(),
              state.queuedAt()));
    }
  }

  /**
   * Want the desk: created (with its token) if absent, demand stamped, its desired state
   * recomputed and its runner told. Answers at once — a desk still waiting for a runner is QUEUED,
   * one being brought up PROVISIONING.
   */
  @POST
  @Path("/ensure")
  public AgentContainerResponse ensure(@PathParam("projectId") String projectId) {
    return AgentContainerResponse.of(frontDesks.ensure(projectId));
  }

  /**
   * Stop an ON_DEMAND desk: its demand is cleared, so it is desired STOPPED and its runner stops the
   * container (keeping it and its volume). An ALWAYS_ON desk answers 409 {@code
   * {"error":"FRONT_DESK_ALWAYS_ON"}}. A project with no desk answers {@code ABSENT}.
   */
  @POST
  @Path("/stop")
  public AgentContainerResponse stop(@PathParam("projectId") String projectId) {
    return AgentContainerResponse.of(frontDesks.stop(projectId));
  }

  /** What the desk is doing, changing nothing. {@code ABSENT} when there is none. */
  @GET
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:admin-agent", "qits:agent"})
  public AgentContainerResponse get(@PathParam("projectId") String projectId) {
    return AgentContainerResponse.of(frontDesks.status(projectId));
  }

  /**
   * Delete the desk: its runner is sent {@code remove} (container <b>and</b> volume), the desk is
   * unplaced and its row deleted, and its token revoked. 204, also when there was none. An ALWAYS_ON
   * desk is created afresh, with a new token, and QUEUED by the next sweep.
   */
  @DELETE
  @Operation(summary = "Delete the project's front desk: its container, its volume and its token")
  @APIResponse(responseCode = "204", description = "The desk is gone, or there was none")
  public Response delete(@PathParam("projectId") String projectId) {
    frontDesks.remove(projectId);
    return Response.noContent().build();
  }
}
