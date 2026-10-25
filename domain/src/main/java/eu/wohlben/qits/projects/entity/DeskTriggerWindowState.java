package eu.wohlben.qits.projects.entity;

/**
 * Where a {@link DeskTriggerWindow} stands (epic qits-1604). Stored as its name; grows without a
 * migration.
 */
public enum DeskTriggerWindowState {
  /** Admitting: every trigger of the project joins it until {@code closes_at}. At most one. */
  OPEN,
  /** Closed and rendered, not yet taken by the desk: the sweep tries it again. */
  OWED,
  /** The desk's daemon handed the message to the desk session at once. */
  DELIVERED,
  /** The desk's daemon took it behind a running turn. As final as DELIVERED for this service. */
  QUEUED,
  /** Never to be delivered: the daemon called the message malformed, or it was owed too long. */
  FAILED
}
