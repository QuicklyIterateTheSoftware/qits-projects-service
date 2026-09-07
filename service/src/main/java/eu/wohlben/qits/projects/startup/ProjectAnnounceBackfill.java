package eu.wohlben.qits.projects.startup;

import eu.wohlben.qits.db.DbRetry;
import eu.wohlben.qits.projects.control.ProjectAnnouncer;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.persistence.ProjectRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Announces, once, the projects that existed before anything announced projects.
 *
 * <p>{@code ProjectCreated} is what tells the platform edge which TLS SANs a project is owed —
 * {@code *.<slug>.<domain>} — and the event is new. Every project created before it existed was
 * created with nobody listening, so the edge has never heard of any of them and no future create can
 * fix that: a project is created once. This is the catch-up, and V15's {@code announced_at} is what
 * makes it a one-off rather than a re-announcement on every boot.
 *
 * <p><b>Publish first, stamp second — the order is the design and not an accident.</b> A crash
 * between the two leaves the row unstamped, so the next boot publishes it again with a fresh {@code
 * eventId}; the edge's projection is an idempotent upsert keyed by the project, so a second {@code
 * ProjectCreated} for the same project is a no-op. Stamping first would make the reverse crash lose
 * the announcement <em>forever</em>: the row would claim to have been announced and nothing would
 * ever look at it again. Of the two failure modes only one is recoverable, and this is the order
 * that chooses it.
 *
 * <p><b>Dark when the bus is.</b> {@code qits.eventstream.enabled=false} is what {@code %dev} and
 * {@code %test} ship (see {@code application.properties}): with the bus off a publish is a debug
 * line, so running the backfill would stamp every row against an announcement that never left — and
 * the stamp is a latch, so the next real boot would skip them all. It is the one gate this class
 * needs; a launch-mode gate would say the same thing less precisely. The suite drives {@link
 * #backfill()} directly, which is the same arrangement {@code ScheduledBackupSweep} and {@code
 * StartupSelfSeed} make with theirs.
 *
 * <p><b>It never fails boot, and it never blocks it.</b> A project the platform has not been told
 * about is not a reason to refuse to serve one, and the next boot asks again — so this runs on a
 * virtual thread after startup, for the reason {@link StartupSelfSeed} and {@link ReservedSlugAudit}
 * do, and swallows everything. A single project's failure costs that project and no other: the loop
 * continues, and the unstamped row is picked up next time.
 */
@ApplicationScoped
public class ProjectAnnounceBackfill {

  private static final Logger LOG = Logger.getLogger(ProjectAnnounceBackfill.class);

  @Inject ProjectRepository projectRepository;

  /**
   * The same optional port {@code ProjectService} announces through — the shipped {@code
   * ProjectLifecycleAnnouncer}, or the suite's recording fake, or nothing at all. Unsatisfied, there
   * is nothing to backfill <em>into</em> and this class stands down rather than stamping rows
   * against announcements nobody made.
   */
  @Inject Instance<ProjectAnnouncer> announcers;

  /**
   * {@code qits.eventstream.enabled} — the bus's own switch, read here rather than mirrored into a
   * key of this class's own so there is one answer to "is the platform listening" and not two.
   * Defaults to the library's shipped {@code true}; the deployable turns it off under {@code %dev}
   * and {@code %test}.
   */
  @ConfigProperty(name = "qits.eventstream.enabled", defaultValue = "true")
  boolean eventstreamEnabled;

  void onStart(@Observes StartupEvent event) {
    if (!eventstreamEnabled) {
      LOG.debug(
          "The eventstream is dark, so the project announce backfill stands down — a stamp without"
              + " a published event would lose the announcement permanently.");
      return;
    }
    Thread.ofVirtual().name("qits-project-announce-backfill").start(this::backfillQuietly);
  }

  /**
   * {@link #backfill()}, with the database's own failures swallowed. A catch-up is not something a
   * boot may fail over, and a database that cannot be read yet is not a finding — the next boot asks
   * again, because nothing was stamped.
   */
  void backfillQuietly() {
    try {
      int announced = backfill();
      if (announced > 0) {
        LOG.infof("Announced %d project(s) the platform had never been told about.", announced);
      }
    } catch (RuntimeException e) {
      LOG.warn(
          "The project announce backfill could not read the projects — retried on the next boot.",
          e);
    }
  }

  /**
   * Publishes {@code ProjectCreated} for every project with no {@code announced_at}, and stamps each
   * one after its announcement has been made.
   *
   * @return how many projects were announced and stamped
   */
  public int backfill() {
    if (!announcers.isResolvable()) {
      return 0;
    }
    List<Project> pending =
        QuarkusTransaction.requiringNew().call(() -> projectRepository.listUnannounced());
    if (pending.isEmpty()) {
      return 0;
    }

    ProjectAnnouncer announcer = announcers.get();
    int announced = 0;
    for (Project project : pending) {
      String id = project.id;
      try {
        // PUBLISH, then STAMP. See the class javadoc: a crash in between re-publishes next boot into
        // an idempotent projection, which is the recoverable half of the only choice available here.
        announcer.onProjectCreated(id, project.slug, project.name, Instant.now());
        stamp(id);
        announced++;
      } catch (RuntimeException e) {
        LOG.warnf(
            e,
            "Could not announce the pre-existing project %s — it stays unstamped and the next boot"
                + " tries again.",
            id);
      }
    }
    return announced;
  }

  /**
   * The stamp, in its own transaction and through {@link DbRetry#inNewTx} — the module's idiom for a
   * write that is rows and nothing else, and therefore one a postgres cutover may be held through.
   * Re-running it is harmless: it re-reads the row and sets one column to a value it computes fresh.
   */
  private void stamp(String projectId) {
    DbRetry.inNewTx(
        "project announce stamp",
        () -> {
          Project row = projectRepository.findById(projectId);
          if (row == null) {
            // Deleted between the read and the stamp. The announcement went out and a ProjectDeleted
            // followed it; there is nothing left to mark.
            return null;
          }
          row.announcedAt = Instant.now();
          // Flush last, for inNewTx's reason: Hibernate would otherwise put this UPDATE in the
          // commit phase, the one round trip a retry cannot place.
          projectRepository.getEntityManager().flush();
          return null;
        });
  }
}
