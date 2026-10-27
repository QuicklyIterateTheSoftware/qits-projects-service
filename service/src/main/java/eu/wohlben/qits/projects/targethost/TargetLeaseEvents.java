package eu.wohlben.qits.projects.targethost;

import eu.wohlben.qits.eventstream.QitsEventListener;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * How this service learns what became of its desks' leases (epic qits-1481, qits-1551):
 * qits-system's {@link TargetLeaseChanged} as it happens, and a reconcile from {@code GET
 * /system/api/leases} every {@code qits.projects.target-leases.reconcile-interval} (PT30S), so a
 * lost event only delays a desk. Both hand the lease to {@link DeskLeases}.
 *
 * <p><b>A live listener, not a durable one.</b> The reconcile is the catch-up: what an event says,
 * the next listing says too. Only leases whose owner is {@code qits-projects} and whose slot type is
 * DESK are this service's; every other is ignored.
 *
 * <p>The event arrives bound to the local {@link TargetLeaseChanged} record, which {@code
 * bus/EventWireReflection} registers for native-image.
 */
@ApplicationScoped
public class TargetLeaseEvents implements QitsEventListener<TargetLeaseChanged> {

  private static final Logger LOG = Logger.getLogger(TargetLeaseEvents.class);

  /** How old an unnamed lease has to be before the reconcile ends it: a start's write window. */
  static final Duration ORPHAN_AFTER = Duration.ofMinutes(2);

  @Inject DeskLeases leases;

  @Override
  public Class<TargetLeaseChanged> eventType() {
    return TargetLeaseChanged.class;
  }

  @Override
  public void onEvent(TargetLeaseChanged event) {
    if (event == null
        || !SystemLeases.OWNER.equals(event.ownerApp())
        || !SystemLeases.SLOT_TYPE.equals(event.slotType())) {
      return;
    }
    UUID leaseId = HttpSystemLeases.uuid(event.leaseId());
    if (leaseId == null) {
      LOG.debugf("TargetLeaseChanged %s names no lease id; ignored", event.eventId());
      return;
    }
    try {
      leases.observe(
          new SystemLeases.Lease(
              leaseId,
              event.ownerRef(),
              event.slotType(),
              SystemLeases.LeaseState.parse(event.state()),
              HttpSystemLeases.uuid(event.targetId()),
              event.exitCode(),
              event.detail(),
              null,
              null));
    } catch (RuntimeException e) {
      // The reconcile tells the desk the same thing within an interval.
      LOG.warnf(e, "Could not apply lease %s's %s; the reconcile will", leaseId, event.state());
    }
  }

  @Scheduled(
      every = "{qits.projects.target-leases.reconcile-interval}",
      concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
      skipExecutionIf = Scheduled.ApplicationNotRunning.class)
  void reconcileOnSchedule() {
    try {
      reconcile();
    } catch (RuntimeException e) {
      LOG.warn("The desk lease reconcile failed — retried on the next interval.", e);
    }
  }

  /** One pass; answers the projects whose desk moved. */
  List<String> reconcile() {
    return leases.reconcile(ORPHAN_AFTER, Instant.now());
  }
}
