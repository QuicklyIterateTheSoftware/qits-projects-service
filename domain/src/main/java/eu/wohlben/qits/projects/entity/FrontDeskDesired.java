package eu.wohlben.qits.projects.entity;

/**
 * Whether a project's front desk should be running (qits-767): {@code front_desk.desired}, spelled
 * as the constant's name — the same two words the runner protocol's {@code DesiredState} carries.
 */
public enum FrontDeskDesired {
  /** The desk's container runs: started, created or recreated by its runner. */
  RUNNING,
  /** The desk's container is stopped; it and its volume are kept. */
  STOPPED
}
