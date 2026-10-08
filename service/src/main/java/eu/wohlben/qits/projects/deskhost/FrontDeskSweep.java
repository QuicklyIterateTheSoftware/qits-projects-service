package eu.wohlben.qits.projects.deskhost;

import eu.wohlben.qits.projects.entity.FrontDesk;
import eu.wohlben.qits.projects.entity.FrontDeskLifecycle;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.persistence.FrontDeskRepository;
import eu.wohlben.qits.projects.persistence.ProjectRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * The front desks' periodic pass (qits-767), every {@code qits.projects.agent-idle-sweep-interval}
 * and once at boot, NORMAL launch mode only:
 *
 * <ol>
 *   <li>every ALWAYS_ON project without a desk gets one (and its token) — so an ALWAYS_ON desk
 *       exists without anyone calling {@code ensure}, and one a DELETE removed is created afresh;
 *   <li>a desk whose token could not be minted is minted again;
 *   <li>the edge belt is applied;
 *   <li>every desk's desired state is recomputed — the idle window closing is noticed here — and a
 *       wanted unplaced desk is stamped {@code queued_at};
 *   <li>every runner one of whose desks moved is sent its estate.
 * </ol>
 */
@ApplicationScoped
public class FrontDeskSweep {

  private static final Logger LOG = Logger.getLogger(FrontDeskSweep.class);

  @Inject FrontDesks frontDesks;

  @Inject FrontDeskRepository desks;

  @Inject ProjectRepository projects;

  @Inject FrontDeskEstates estates;

  void onStart(@Observes StartupEvent event) {
    if (LaunchMode.current() != LaunchMode.NORMAL) {
      return;
    }
    Thread.ofVirtual().name("qits-front-desk-boot-pass").start(this::sweepQuietly);
  }

  @Scheduled(
      every = "{qits.projects.agent-idle-sweep-interval}",
      concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
  void onInterval() {
    if (LaunchMode.current() != LaunchMode.NORMAL) {
      return;
    }
    sweepQuietly();
  }

  void sweepQuietly() {
    try {
      sweep(Instant.now());
    } catch (RuntimeException e) {
      LOG.error("The front desk sweep failed — retried on the next interval.", e);
    }
  }

  /** One pass; answers how many runners were sent an estate. */
  public int sweep(Instant now) {
    List<Project> all = QuarkusTransaction.requiringNew().call(() -> projects.listAll());
    for (Project project : all) {
      if (project.frontDeskLifecycle == FrontDeskLifecycle.ALWAYS_ON
          && project.slug != null
          && !project.slug.isBlank()
          && frontDesks.find(project.id).isEmpty()) {
        try {
          frontDesks.ensureRow(project.id);
        } catch (RuntimeException e) {
          LOG.warnf("Could not create the ALWAYS_ON desk of project %s: %s", project.id, e.getMessage());
        }
      }
    }
    Set<UUID> moved = new LinkedHashSet<>();
    List<FrontDesk> rows = QuarkusTransaction.requiringNew().call(() -> desks.listAll());
    for (FrontDesk row : rows) {
      try {
        if (row.tokenId == null) {
          frontDesks.mintIfMissing(row.projectId);
        }
        frontDesks.checkEdge(row.projectId);
        UUID runner = frontDesks.reconcile(row.projectId, now);
        if (runner != null) {
          moved.add(runner);
        }
      } catch (RuntimeException e) {
        LOG.warnf("Could not sweep the front desk of project %s: %s", row.projectId, e.getMessage());
      }
    }
    int sent = 0;
    for (UUID runner : moved) {
      if (estates.push(runner)) {
        sent++;
      }
    }
    return sent;
  }
}
