package eu.wohlben.qits.projects.bus;

import eu.wohlben.qits.eventstream.QitsEventBus;
import eu.wohlben.qits.projects.control.ReleaseRequestSourceMoveAnnouncer;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Turns a source's move between release requests into {@link ReleaseRequestSourceMoved} and hands
 * it to the bus (qits-1665) — {@link ReleaseRequestStateChangedAnnouncer}'s shape.
 *
 * <p>Called by {@code domain}'s {@code ReleaseRequests.moveSource} after the transaction that moved
 * the source committed, on the caller's thread, so the bus takes the cause from that thread's
 * causation scope. {@code @DefaultBean}: the suite's recording fake wins the port, and no test
 * reaches the bus.
 */
@ApplicationScoped
@DefaultBean
public class ReleaseRequestSourceMovedAnnouncer implements ReleaseRequestSourceMoveAnnouncer {

  @Inject QitsEventBus bus;

  @Override
  public void onSourceMoved(SourceMove move) {
    bus.publish(event(move));
  }

  /** The event for one move — package-visible so a test reads the real mapping. */
  static ReleaseRequestSourceMoved event(SourceMove move) {
    return new ReleaseRequestSourceMoved(
        null,
        move.projectId(),
        move.repoId(),
        move.repoName(),
        move.branch(),
        move.fromRequestId(),
        move.fromQualifiedId(),
        move.toRequestId(),
        move.toQualifiedId(),
        move.minted(),
        move.movedBy(),
        move.reason(),
        move.movedAt());
  }
}
