package eu.wohlben.qits.projects.deskhost;

import eu.wohlben.qits.projects.entity.DeskRole;
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
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * The desks' periodic pass (qits-767), every {@code qits.projects.agent-idle-sweep-interval} and
 * once at boot, NORMAL launch mode only:
 *
 * <ol>
 *   <li>every ALWAYS_ON project without a FRONT desk gets one (and its token), and every project
 *       whose delivery loop is enabled (epic qits-1440) without a DELIVERY desk gets one (qits-1482)
 *       — so such a desk exists without anyone calling {@code ensure}, and one a DELETE removed is
 *       created afresh; a DELIVERY desk whose project's loop is off is retired;
 *   <li>a desk whose token could not be minted is minted again, and its role's other tokens are
 *       brought in line ({@link FrontDesks#syncTokens});
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
    Map<String, Project> byId = new HashMap<>();
    for (Project project : all) {
      byId.put(project.id, project);
      if (project.slug == null || project.slug.isBlank()) {
        continue;
      }
      if (project.frontDeskLifecycle == FrontDeskLifecycle.ALWAYS_ON) {
        create(project, DeskRole.FRONT);
      }
      if (project.deliveryEnabled) {
        create(project, DeskRole.DELIVERY);
      }
    }
    Set<UUID> moved = new LinkedHashSet<>();
    List<FrontDesk> rows = QuarkusTransaction.requiringNew().call(() -> desks.listAll());
    for (FrontDesk row : rows) {
      String deskId = row.deskId();
      try {
        Project project = byId.get(row.projectId);
        if (row.role() == DeskRole.DELIVERY && (project == null || !project.deliveryEnabled)) {
          frontDesks.retireDesk(deskId);
          continue;
        }
        if (row.tokenId == null) {
          frontDesks.mintIfMissing(deskId);
        }
        frontDesks.syncTokens(deskId);
        frontDesks.checkEdge(deskId);
        UUID runner = frontDesks.reconcile(deskId, now);
        if (runner != null) {
          moved.add(runner);
        }
      } catch (RuntimeException e) {
        LOG.warnf("Could not sweep the desk %s: %s", deskId, e.getMessage());
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

  /** The project's desk of {@code role}, created (with its tokens) when it has none. */
  private void create(Project project, DeskRole role) {
    if (frontDesks.find(project.id, role).isPresent()) {
      return;
    }
    try {
      frontDesks.ensureDesk(project.id, role);
    } catch (RuntimeException e) {
      LOG.warnf(
          "Could not create the wanted %s desk of project %s: %s", role, project.id, e.getMessage());
    }
  }
}
