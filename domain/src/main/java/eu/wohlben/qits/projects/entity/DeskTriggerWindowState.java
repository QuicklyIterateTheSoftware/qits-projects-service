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
  FAILED,
  /**
   * Closed without a prompt (qits-2501): at the close, or before a delivery attempt, none of its
   * lines still held — each was resolved or superseded (see {@code DeskTriggerEvent#droppedAt}) —
   * so there was nothing left to tell the desk. Its Slots reading alone is never sent. {@code
   * closed_at} is when it closed; {@code message} is null when it went REDUNDANT at the close, and
   * keeps the text last rendered when it went REDUNDANT before a delivery attempt. Final, like
   * DELIVERED: the next trigger opens a new OPEN window.
   */
  REDUNDANT
}
