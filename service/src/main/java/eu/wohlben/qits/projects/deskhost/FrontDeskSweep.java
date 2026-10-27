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
import java.util.List;
import org.jboss.logging.Logger;

/**
 * The front desks' periodic pass (qits-767), every {@code qits.projects.agent-idle-sweep-interval}
 * and once at boot, NORMAL launch mode only:
 *
 * <ol>
 *   <li>every ALWAYS_ON project, every project whose delivery loop is enabled (epic qits-1440), and
 *       every project one of whose refinement rooms wants its session (epic qits-770), without a
 *       desk gets one — so such a desk exists without anyone calling {@code ensure}, and one a
 *       DELETE removed is created afresh;
 *   <li>a desk whose session token (qits-1482) could not be minted is minted again, and its
 *       delivery token is brought in line with the project's loop;
 *   <li>its connected daemon is sent its session credentials again ({@link
 *       DeskSessionCredentials#push}, capability 2 only), the safety net under every change trigger;
 *   <li>the edge belt is applied;
 *   <li>a failure a lease left (the target FAILED it, or refused it) is cleared, so the desk is tried
 *       again — once a sweep interval, never in a tight loop;
 *   <li>every desk's desired state is recomputed — the idle window closing is noticed here — and its
 *       DESK lease brought in line ({@code targethost/DeskLeases}, epic qits-1481): asked for while
 *       wanted, ended while not.
 * </ol>
 */
@ApplicationScoped
public class FrontDeskSweep {

  private static final Logger LOG = Logger.getLogger(FrontDeskSweep.class);

  @Inject FrontDesks frontDesks;

  @Inject FrontDeskRepository desks;

  @Inject ProjectRepository projects;

  @Inject DeskSessionCredentials sessionCredentials;

  @Inject FrontDeskDemand demand;

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

  /** One pass; answers how many desks it went over. */
  public int sweep(Instant now) {
    List<Project> all = QuarkusTransaction.requiringNew().call(() -> projects.listAll());
    for (Project project : all) {
      // Every reason a desk is wanted that needs no row to say so: ALWAYS_ON, the delivery loop, a
      // refinement room's session (epic qits-770).
      if ((project.frontDeskLifecycle == FrontDeskLifecycle.ALWAYS_ON
              || project.deliveryEnabled
              || demand.wants(project, null, now))
          && project.slug != null
          && !project.slug.isBlank()
          && frontDesks.find(project.id).isEmpty()) {
        try {
          frontDesks.ensureRow(project.id);
        } catch (RuntimeException e) {
          LOG.warnf("Could not create the wanted desk of project %s: %s", project.id, e.getMessage());
        }
      }
    }
    int swept = 0;
    List<FrontDesk> rows = QuarkusTransaction.requiringNew().call(() -> desks.listAll());
    for (FrontDesk row : rows) {
      try {
        if (row.sessionTokenId == null) {
          frontDesks.mintSessionIfMissing(row.projectId);
        }
        frontDesks.syncDeliveryToken(row.projectId);
        // The safety net (qits-1482): a change whose send was missed is caught here.
        sessionCredentials.push(row.projectId);
        frontDesks.checkEdge(row.projectId);
        frontDesks.clearLeaseFailure(row.projectId);
        frontDesks.reconcileAndApply(row.projectId, now);
        swept++;
      } catch (RuntimeException e) {
        LOG.warnf("Could not sweep the front desk of project %s: %s", row.projectId, e.getMessage());
      }
    }
    return swept;
  }
}
