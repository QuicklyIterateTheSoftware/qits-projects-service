package eu.wohlben.qits.projects.deskhost;

import eu.wohlben.qits.projects.agenthost.AgentDaemonRegistry;
import eu.wohlben.qits.projects.entity.FrontDesk;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.persistence.FrontDeskRepository;
import eu.wohlben.qits.projects.persistence.ProjectRepository;
import eu.wohlben.qits.projectsdaemon.protocol.DaemonProtocol;
import eu.wohlben.qits.projectsdeskrunner.protocol.DeskState;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Rolls a placed desk onto its current spec (qits-767; it replaces {@code AgentStaleImageSweep}'s
 * decision), every {@code qits.projects.agent-stale-sweep-interval}, NORMAL launch mode only — and
 * for one project at once, when a front-desk settings change asks for it ({@link #roll(String,
 * Instant)}, qits-1514).
 *
 * <p>For each placed desk the current spec is composed; when its hash differs from the applied
 * {@code spec_hash} the new spec replaces it — and the runner is sent its estate, on which it
 * recreates the container — under one of two rules:
 *
 * <ul>
 *   <li><b>A requested roll</b> ({@code roll_requested_at} set, by {@code FrontDeskHooks} on a
 *       {@code front_desk.lifecycle} or {@code front_desk.delivery} change) rolls <b>between
 *       turns</b>: as soon as the activity rollup is not BUSY or WAITING, whatever the use stamp
 *       says. A settings change is somebody asking for the desk to behave differently, and a desk in
 *       steady use would otherwise never pick it up.
 *   <li><b>Everything else</b> — a pin bump, the service's own configuration (image, agent
 *       configuration), a token rotation, a domain change — rolls <b>only when the desk is not
 *       reported RUNNING, or is quiet</b>: no evidence of use for {@code
 *       qits.projects.agent-stale-quiet-window} (PT30M) and the rollup not busy (the rule {@code
 *       AgentStaleImageSweep} carried).
 * </ul>
 *
 * <p>A desk with no applied spec takes the fresh one always. A composed spec equal to the applied
 * one answers a pending request without a roll: the request is cleared and nothing restarts — the
 * hook fires on unchanged settings too.
 */
@ApplicationScoped
public class FrontDeskSpecRoll {

  private static final Logger LOG = Logger.getLogger(FrontDeskSpecRoll.class);

  @Inject FrontDesks frontDesks;

  @Inject FrontDeskRepository desks;

  @Inject ProjectRepository projects;

  @Inject FrontDeskSpecs specs;

  @Inject AgentDaemonRegistry daemons;

  @Inject FrontDeskEstates estates;

  @ConfigProperty(name = "qits.projects.agent-stale-quiet-window", defaultValue = "PT30M")
  Duration quietWindow;

  @Scheduled(
      every = "{qits.projects.agent-stale-sweep-interval}",
      concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
  void onInterval() {
    if (LaunchMode.current() != LaunchMode.NORMAL) {
      return;
    }
    try {
      roll(Instant.now());
    } catch (RuntimeException e) {
      LOG.error("The front desk spec roll failed — retried on the next interval.", e);
    }
  }

  /** One pass; answers how many desks took a new spec. */
  public int roll(Instant now) {
    List<FrontDesk> placed =
        QuarkusTransaction.requiringNew().call(() -> desks.list("runnerId is not null"));
    Set<UUID> runners = new LinkedHashSet<>();
    int rolled = 0;
    for (FrontDesk row : placed) {
      if (rollOne(row, now)) {
        runners.add(row.runnerId);
        rolled++;
      }
    }
    runners.forEach(estates::push);
    return rolled;
  }

  /**
   * The same decision for the desk of {@code projectId} alone, now — a front-desk settings change
   * (qits-1514). Nothing for a project with no desk or an unplaced one; answers 1 when it rolled,
   * after the runner has been sent its estate.
   */
  public int roll(String projectId, Instant now) {
    FrontDesk row = QuarkusTransaction.requiringNew().call(() -> desks.findById(projectId));
    if (row == null || !row.placed()) {
      return 0;
    }
    if (!rollOne(row, now)) {
      return 0;
    }
    estates.push(row.runnerId);
    return 1;
  }

  /** Compose {@code row}'s spec and store it when the rules allow; true when it landed. */
  private boolean rollOne(FrontDesk row, Instant now) {
    Project project = QuarkusTransaction.requiringNew().call(() -> projects.findById(row.projectId));
    if (project == null) {
      return false;
    }
    FrontDeskSpecs.Composed composed;
    try {
      composed = specs.compose(project, row);
    } catch (FrontDeskSpecs.EdgePlaneUnconfigured unconfigured) {
      frontDesks.recordFailure(row.projectId, FrontDeskSpecs.EDGE_PLANE_UNCONFIGURED);
      return false;
    } catch (RuntimeException e) {
      LOG.warnf("Could not compose the desk of project %s: %s", row.projectId, e.getMessage());
      return false;
    }
    if (Objects.equals(composed.hash(), row.specHash)) {
      if (row.rollRequestedAt != null) {
        frontDesks.clearRollRequest(row.projectId);
      }
      return false;
    }
    if (row.specJson != null && DeskState.RUNNING.name().equals(row.reportedState)) {
      boolean requested = row.rollRequestedAt != null;
      if (requested && busy(row.projectId)) {
        LOG.warnf(
            "The front desk of project %s has new settings and is busy; it rolls when the current"
                + " turn ends",
            row.projectId);
        return false;
      }
      if (!requested && !quiet(row.projectId, now)) {
        LOG.warnf(
            "The front desk of project %s has a new spec and is in use; it rolls once it is quiet",
            row.projectId);
        return false;
      }
    }
    if (frontDesks.storeSpec(row.projectId, row.runnerId, composed) == 1) {
      LOG.infof("Rolling the front desk of project %s onto spec %s", row.projectId, composed.hash());
      return true;
    }
    return false;
  }

  /** No evidence of use inside the quiet window, and nothing busy or waiting right now. */
  boolean quiet(String projectId, Instant now) {
    if (quietWindow == null || quietWindow.isZero() || quietWindow.isNegative()) {
      return false;
    }
    Optional<Instant> last = daemons.lastAgentActivityAt(projectId);
    if (last.isPresent() && last.get().isAfter(now.minus(quietWindow))) {
      return false;
    }
    return !busy(projectId);
  }

  /** The activity rollup says a turn is running or waiting on somebody: BUSY or WAITING. */
  boolean busy(String projectId) {
    String state = daemons.agentActivity(projectId).orElse(null);
    return DaemonProtocol.AgentState.BUSY.equals(state)
        || DaemonProtocol.AgentState.WAITING.equals(state);
  }
}
