package eu.wohlben.qits.entities.control;

/**
 * Tells the rest of this service that a row's assignee changed (qits-2518) — once per row and
 * transaction, after it committed, and only when the value differs from the one the row held before.
 *
 * <p>A port for {@link TransitionAnnouncer}'s reason: this module knows of no bus, and the adapter
 * ({@code projects/bus/EntityAssignAnnouncer}) lives in {@code service}. It is told by {@link
 * AssigneeWrites}, the one writer of {@code WorkEntity.assignee}, so no door can forget it.
 *
 * <p><b>Absent is a supported configuration</b> (this module's own suite runs so), and <b>nothing
 * here may throw</b>: it is called from the transaction's after-completion, on the writer's thread.
 */
public interface AssigneeAnnouncer {

  /** {@code change} committed. */
  void onAssigneeChanged(AssigneeChange change);
}
