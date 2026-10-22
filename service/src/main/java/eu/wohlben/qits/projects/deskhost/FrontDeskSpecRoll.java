package eu.wohlben.qits.projects.deskhost;

import eu.wohlben.qits.projects.agenthost.AgentDaemonRegistry;
import eu.wohlben.qits.projects.entity.DeskRole;
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
 * decision), every {@code qits.projects.agent-stale-sweep-interval}, NORMAL launch mode only.
 *
 * <p>For each placed desk the current spec is composed; when its hash differs from the applied
 * {@code spec_hash} the new spec replaces it — and the runner is sent its estate, on which it
 * recreates the container — <b>only when the desk is not reported RUNNING, or is quiet</b>: no
 * evidence of use for {@code qits.projects.agent-stale-quiet-window} (PT30M) and the activity rollup
 * not busy (the rule {@code AgentStaleImageSweep} carried). A desk with no applied spec takes the
 * fresh one always. A pin bump, a lifecycle flip and a domain change all roll this way.
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
      Project project =
          QuarkusTransaction.requiringNew().call(() -> projects.findById(row.projectId));
      if (project == null) {
        continue;
      }
      String deskId = row.deskId();
      FrontDeskSpecs.Composed composed;
      try {
        composed = specs.compose(project, row);
      } catch (FrontDeskSpecs.EdgePlaneUnconfigured unconfigured) {
        frontDesks.recordFailure(deskId, FrontDeskSpecs.EDGE_PLANE_UNCONFIGURED);
        continue;
      } catch (RuntimeException e) {
        LOG.warnf("Could not compose the desk %s: %s", deskId, e.getMessage());
        continue;
      }
      if (Objects.equals(composed.hash(), row.specHash)) {
        continue;
      }
      if (row.specJson != null
          && DeskState.RUNNING.name().equals(row.reportedState)
          && !quiet(deskId, now)) {
        LOG.warnf(
            "The desk %s has a new spec and is in use; it rolls once it is quiet", deskId);
        continue;
      }
      if (frontDesks.storeSpec(deskId, row.runnerId, composed) == 1) {
        LOG.infof("Rolling the desk %s onto spec %s", deskId, composed.hash());
        runners.add(row.runnerId);
        rolled++;
      }
    }
    runners.forEach(estates::push);
    return rolled;
  }

  /**
   * No evidence of use inside the quiet window, and nothing busy or waiting right now.
   *
   * <p>A DELIVERY desk (qits-1482) skips the window: its loop wakes every few minutes by itself, so
   * it would never be quiet for half an hour, and nobody works in it — between two passes is quiet
   * enough.
   */
  boolean quiet(String deskId, Instant now) {
    if (quietWindow == null || quietWindow.isZero() || quietWindow.isNegative()) {
      return false;
    }
    Optional<Instant> last = daemons.lastAgentActivityAt(deskId);
    if (DeskRole.ofDeskId(deskId) == DeskRole.FRONT
        && last.isPresent()
        && last.get().isAfter(now.minus(quietWindow))) {
      return false;
    }
    String state = daemons.agentActivity(deskId).orElse(null);
    return !DaemonProtocol.AgentState.BUSY.equals(state)
        && !DaemonProtocol.AgentState.WAITING.equals(state);
  }
}
