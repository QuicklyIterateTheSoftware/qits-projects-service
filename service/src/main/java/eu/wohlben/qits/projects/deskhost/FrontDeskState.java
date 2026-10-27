package eu.wohlben.qits.projects.deskhost;

import eu.wohlben.qits.projects.agenthost.AgentRuntimeStatus;
import eu.wohlben.qits.projects.entity.FrontDeskLifecycle;
import java.time.Instant;
import java.util.UUID;

/**
 * A project's front desk as the agent-container doors answer it (qits-767): the status computed
 * from its row, its DESK lease and its daemon, what the daemon said about itself, and the target
 * the desk runs on (epic qits-1481).
 *
 * @param runtimeStatus the computed status; {@code UNAVAILABLE} is never stored
 * @param daemonConnected whether the desk's daemon holds its control socket to this process
 * @param daemonVersion what the daemon's {@code Hello} said, or null
 * @param pinnedDaemonVersion the project-agent image pin; null while there is no desk
 * @param daemonVersionStale whether a connected daemon is not at the pin
 * @param failureDetail why the status is FAILED; for QUEUED, why the desk's lease waits (the
 *     lease's {@code detail}), when qits-system said; else null
 * @param targetId the execution target the desk's lease was granted on, or null
 * @param lifecycle the project's {@code front_desk.lifecycle}
 * @param queuedAt since when the desk has waited for a slot, or null
 */
public record FrontDeskState(
    AgentRuntimeStatus runtimeStatus,
    boolean daemonConnected,
    String daemonVersion,
    String pinnedDaemonVersion,
    boolean daemonVersionStale,
    String failureDetail,
    UUID targetId,
    FrontDeskLifecycle lifecycle,
    Instant queuedAt) {

  /** No desk row. */
  public static FrontDeskState absent(FrontDeskLifecycle lifecycle) {
    return new FrontDeskState(
        AgentRuntimeStatus.ABSENT, false, null, null, false, null, null, lifecycle, null);
  }
}
