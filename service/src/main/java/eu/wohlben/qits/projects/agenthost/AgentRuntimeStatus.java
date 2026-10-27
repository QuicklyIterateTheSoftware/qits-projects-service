package eu.wohlben.qits.projects.agenthost;

/**
 * What a project's front desk (its agent container) is doing, as the agent-container REST surface
 * reports it (qits-767: computed by {@code deskhost/FrontDesks.state}). The wire value is the
 * constant name — the SPA switches on these strings, so they are a published contract and not an
 * internal enum; {@code QUEUED} and {@code UNAVAILABLE} were added with the front-desk runners, after
 * the SPA learned to tolerate them. Since the desks run as DESK leases (epic qits-1481) every value
 * but {@code UNAVAILABLE} is computed from the lease.
 */
public enum AgentRuntimeStatus {
  /** qits-system reports the desk's lease RUNNING. Its daemon may not be connected. */
  RUNNING,

  /** The desk is desired stopped, and its container is stopped or absent. Its volume is kept. */
  STOPPED,

  /** Its lease is granted on a target (the image may be pulling), and not yet running. */
  PROVISIONING,

  /**
   * The desk is not usable: its lease FAILED or was refused, its daemon could not provision it, its
   * token could not be minted, or this service has no public domain to address it under. {@code
   * failureDetail} says which.
   */
  FAILED,

  /** No desk exists for this project. */
  ABSENT,

  /** Desired running and waiting for a DESK slot: no lease yet, or its lease QUEUED. */
  QUEUED,

  /**
   * No longer answered: it was a front-desk runner disconnected past its reconnect grace, and the
   * runners are gone (epic qits-1481). Kept because the value is a published contract.
   */
  UNAVAILABLE
}
