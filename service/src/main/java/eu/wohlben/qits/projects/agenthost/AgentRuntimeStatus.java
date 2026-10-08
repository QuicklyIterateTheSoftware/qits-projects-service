package eu.wohlben.qits.projects.agenthost;

/**
 * What a project's front desk (its agent container) is doing, as the agent-container REST surface
 * reports it (qits-767: computed by {@code deskhost/FrontDesks.state}). The wire value is the
 * constant name — the SPA switches on these strings, so they are a published contract and not an
 * internal enum; {@code QUEUED} and {@code UNAVAILABLE} were added with the front-desk runners, after
 * the SPA learned to tolerate them.
 */
public enum AgentRuntimeStatus {
  /** The runner's inventory says the desk's container is running. Its daemon may not be connected. */
  RUNNING,

  /** The desk is desired stopped, and its container is stopped or absent. Its volume is kept. */
  STOPPED,

  /** Placed on a runner and desired running, and not yet reported running. */
  PROVISIONING,

  /**
   * The desk is not usable: its runner could not launch it, its daemon could not provision it, its
   * token could not be minted, or this service has no public domain to address it under. {@code
   * failureDetail} says which.
   */
  FAILED,

  /** No desk exists for this project. */
  ABSENT,

  /** Desired running and waiting for a runner to take it. */
  QUEUED,

  /**
   * Computed, never stored: the runner holding the desk has been disconnected longer than {@code
   * qits.projects.desk-runner.reconnect-grace}.
   */
  UNAVAILABLE
}
