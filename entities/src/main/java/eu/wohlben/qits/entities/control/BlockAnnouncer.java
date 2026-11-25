package eu.wohlben.qits.entities.control;

/**
 * Tells the rest of this service that a row's explicit block flag changed (qits-2674) — once per row
 * and transaction, after it committed, and only when the flag differs from the one the row held
 * before.
 *
 * <p>A port for {@link AssigneeAnnouncer}'s reason: this module knows of no bus, and the adapter
 * ({@code projects/bus/EntityBlockAnnouncer}) lives in {@code service}. It is told by {@link
 * BlockWrites}, which every write of {@code WorkEntity.blocked} goes through — the block door's
 * {@link WorkEntityService#setBlocked} and a transition's clear — so no door can forget it.
 *
 * <p><b>Absent is a supported configuration</b> (this module's own suite runs so), and <b>nothing
 * here may throw</b>: it is called from the transaction's after-completion, on the writer's thread.
 */
public interface BlockAnnouncer {

  /** {@code change} committed. */
  void onBlockChanged(BlockChange change);
}
