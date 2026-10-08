package eu.wohlben.qits.projects.deskhost;

import eu.wohlben.qits.projects.agenthost.AgentRuntimeStatus;
import eu.wohlben.qits.projects.entity.FrontDeskLifecycle;
import java.time.Instant;
import java.util.UUID;

/**
 * A project's front desk as the agent-container doors answer it (qits-767): the status computed
 * from its row, its runner's presence and its daemon, what the daemon said about itself, and where
 * the desk is placed.
 *
 * @param runtimeStatus the computed status; {@code UNAVAILABLE} is never stored
 * @param daemonConnected whether the desk's daemon holds its control socket to this process
 * @param daemonVersion what the daemon's {@code Hello} said, or null
 * @param pinnedDaemonVersion the project-agent image pin; null while there is no desk
 * @param daemonVersionStale whether a connected daemon is not at the pin
 * @param failureDetail why the status is FAILED, else null
 * @param runnerId the runner holding the desk, or null while unplaced
 * @param runnerName that runner's name, or null
 * @param lifecycle the project's {@code front_desk.lifecycle}
 * @param queuedAt since when the desk has waited for a runner, or null
 */
public record FrontDeskState(
    AgentRuntimeStatus runtimeStatus,
    boolean daemonConnected,
    String daemonVersion,
    String pinnedDaemonVersion,
    boolean daemonVersionStale,
    String failureDetail,
    UUID runnerId,
    String runnerName,
    FrontDeskLifecycle lifecycle,
    Instant queuedAt) {

  /** No desk row. */
  public static FrontDeskState absent(FrontDeskLifecycle lifecycle) {
    return new FrontDeskState(
        AgentRuntimeStatus.ABSENT, false, null, null, false, null, null, null, lifecycle, null);
  }
}
