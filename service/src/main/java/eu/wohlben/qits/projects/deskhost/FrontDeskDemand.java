package eu.wohlben.qits.projects.deskhost;

import eu.wohlben.qits.projects.entity.FrontDesk;
import eu.wohlben.qits.projects.entity.FrontDeskDesired;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.persistence.FrontDeskRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import org.jboss.logging.Logger;

/**
 * Whether a project's front desk should run (qits-767), and the demand stamp that feeds it.
 *
 * <p><b>Desired state</b> is {@link FrontDeskDesired#RUNNING} while any {@link
 * FrontDeskDemandSource} wants the desk — today the project's {@code ALWAYS_ON} lifecycle ({@link
 * AlwaysOnDemand}) and use within {@code qits.projects.agent-idle-timeout} ({@link RecentUseDemand})
 * — and {@link FrontDeskDesired#STOPPED} otherwise. The sources are a list so a new reason is one
 * more bean.
 *
 * <p><b>Demand</b> is {@code front_desk.last_demand_at}: stamped by {@code POST
 * …/agent-container/ensure}, and by the daemon frames {@code AgentDaemonRegistry} counts as use
 * (AgentActivity, CommandChunk, CommandExit, ProjectChanged) at most once per project per {@link
 * #EVIDENCE_INTERVAL}, and only while the desk is wanted — a daemon saying goodbye after a stop must
 * not wake it again. A stop clears it.
 */
@ApplicationScoped
public class FrontDeskDemand {

  private static final Logger LOG = Logger.getLogger(FrontDeskDemand.class);

  /** How often the daemon's evidence of use may reach a row. */
  static final Duration EVIDENCE_INTERVAL = Duration.ofSeconds(60);

  @Inject Instance<FrontDeskDemandSource> sources;

  @Inject FrontDeskRepository desks;

  /** When each project's evidence last reached its row. */
  private final ConcurrentHashMap<String, Instant> evidenceWritten = new ConcurrentHashMap<>();

  /** What {@code project}'s desk should be at {@code now}. */
  public FrontDeskDesired desired(Project project, FrontDesk row, Instant now) {
    return wants(project, row, now) ? FrontDeskDesired.RUNNING : FrontDeskDesired.STOPPED;
  }

  /** Whether any source wants the desk. */
  public boolean wants(Project project, FrontDesk row, Instant now) {
    for (FrontDeskDemandSource source : sources) {
      if (source.wants(project, row, now)) {
        return true;
      }
    }
    return false;
  }

  /** Stamp demand now (the ensure door). */
  public void stamp(String projectId, Instant now) {
    QuarkusTransaction.requiringNew()
        .run(() -> desks.update("lastDemandAt = ?1 where projectId = ?2", now, projectId));
  }

  /** Clear demand (the stop door): the idle window closes at once. */
  public void clear(String projectId) {
    QuarkusTransaction.requiringNew()
        .run(() -> desks.update("lastDemandAt = null where projectId = ?1", projectId));
  }

  /**
   * The desk's daemon said something that evidences use. Throttled to one write per project per
   * {@link #EVIDENCE_INTERVAL}, written off the caller's thread, and only to a wanted desk.
   */
  public void evidenced(String projectId) {
    evidenced(projectId, Instant.now());
  }

  void evidenced(String projectId, Instant now) {
    if (projectId == null) {
      return;
    }
    Instant written =
        evidenceWritten.compute(
            projectId,
            (id, last) -> last != null && last.plus(EVIDENCE_INTERVAL).isAfter(now) ? last : now);
    if (written != now) {
      return;
    }
    Thread.ofVirtual()
        .name("qits-front-desk-demand")
        .start(
            () -> {
              try {
                QuarkusTransaction.requiringNew()
                    .run(
                        () ->
                            desks.update(
                                "lastDemandAt = ?1 where projectId = ?2 and desired = ?3",
                                now,
                                projectId,
                                FrontDeskDesired.RUNNING));
              } catch (RuntimeException e) {
                LOG.debugf("Could not stamp demand on the desk of %s: %s", projectId, e.getMessage());
              }
            });
  }

  /** A suite proving the throttle forgets between cases. */
  void forgetEvidence() {
    evidenceWritten.clear();
  }
}
