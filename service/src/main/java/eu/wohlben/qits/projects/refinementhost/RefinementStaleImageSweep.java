package eu.wohlben.qits.projects.refinementhost;

import eu.wohlben.qits.projects.control.ProjectService;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.Refinement;
import eu.wohlben.qits.projects.persistence.RefinementRepository;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonProtocol;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Stops a running refinement container whose daemon is not the one this service pins, while nobody
 * is using it. The refinement twin of {@code agenthost/AgentStaleImageSweep}, and a port of it:
 * every invariant below is that class's, restated where the refinement axis makes a different
 * argument for the same rule.
 *
 * <h2>What it exists for</h2>
 *
 * <p>A refinement container runs the image it was created with for ever. There is exactly one door
 * in this harness that applies a new pin — the wake arm of {@link RefinementService}'s ensure
 * ladder, which sends {@code Recreate.ifChanged} through {@link
 * RefinementContainerFactory#forExistingContainer} — and that door is only reachable from a
 * <b>stopped</b> container. Nothing stopped one. A refinement's container is started when somebody
 * opens the refining page and then simply stays up: {@link RefinementService#stopContainer} is a
 * person's press, {@link RefinementService#discard} happens only when the epic leaves refinement,
 * and there is no idle sweep on this axis at all. So an image bump reached a refinement container
 * only if a person happened to press Stop, or if the epic was resolved and a brand-new container
 * was built for the next one — which is to say, on exactly the long-lived refinements where a stale
 * toolchain costs the most.
 *
 * <p>This sweep is the missing stop. It does not apply the pin and deliberately cannot: it hands
 * the container to the wake path, which already knows how to replace one.
 *
 * <h2>Why stopping is the whole action</h2>
 *
 * <p><b>It stops, and never removes</b> — the rule every verb in this harness keeps except the
 * explicit discard, and here it is also what makes the fix small. {@code Recreate.ifChanged} on the
 * next wake does the replacement, and what has to survive that replacement survives it by
 * construction: the checkout is on the per-refinement {@code /workspace} volume, which the
 * orchestrator's replacement path re-mounts rather than discards (see the clean-gate note below),
 * and the commissioned credential is read back off the {@code refinement} row by {@link
 * RefinementContainerFactory#forExistingContainer} rather than re-minted. So a stale container is
 * stopped, woken by the next person who opens the refining page, and comes back on the pinned image
 * with its work where it left it.
 *
 * <p><b>It moves the IMAGE and not the CHECKOUT.</b> The daemon skips its self-clone on a populated
 * {@code /workspace}, so a replaced container reattaches the same tree at the same commit — a new
 * daemon binary over an old checkout. Nothing here touches a working tree, and nothing here should
 * start to.
 *
 * <p><b>There is deliberately no {@code clean} gate</b>, which is the one place this sweep looks
 * less careful than {@link RefinementService#recreateContainer} and is not. That door refuses an
 * unclean tree because a <em>recreate</em> destroys the container's writable layer and re-clones;
 * this is a stop, which destroys nothing at all. The replacement that may follow at the next wake
 * is not destructive either: qits-containers' {@code ContainerRegistry} performs a {@code REPLACE}
 * as {@code docker stop} + {@code docker rm -f} with <b>no {@code -v}</b>, then re-{@code
 * ensureVolume}s and re-mounts the identical named volume, identically for every lifetime policy.
 * Gating on cleanliness would also be gating on something this host frequently does not know:
 * {@link RefinementDaemonRegistry#clean} is empty for a daemon that has not reported, unknown would
 * have to be read as dirty, and a container whose daemon never says so would be unsweepable for
 * ever — which is the defect, not a safeguard against it.
 *
 * <h2>Why there is no second sweep to fold this into</h2>
 *
 * <p>On the agent axis this class's twin has to argue why it is not an arm of the idle sweep. Here
 * there is no such neighbour: refinement containers carry {@code Policy.explicitLifetime()}, there
 * is no {@code IDLE_STOP} belt and no host-side idle window, so this is the only clock-driven verb
 * on the axis and the only reader of {@link RefinementDaemonRegistry#lastAgentActivityAt}. That is
 * also why a container on the pin is left <em>completely</em> alone below, rather than touched.
 */
@ApplicationScoped
public class RefinementStaleImageSweep {

  private static final Logger LOG = Logger.getLogger(RefinementStaleImageSweep.class);

  @Inject RefinementRuntime runtime;

  @Inject RefinementDaemonRegistry registry;

  /** The stop verb, with its tunnel teardown and its registry eviction. */
  @Inject RefinementService refinements;

  /** The one reading of the image pin — {@link RefinementContainerFactory#imageVersion()}. */
  @Inject RefinementContainerFactory factory;

  /** The rows a container name is resolved back to. */
  @Inject RefinementRepository store;

  /** Where a refinement's project slug — half of its container name — comes from. */
  @Inject ProjectService projectService;

  /**
   * How quiet a stale container has to be before it is taken away, measured from the last thing
   * that actually happened in it.
   *
   * <p>Thirty minutes: long enough that a person who stepped away mid-refinement comes back to the
   * container they left, short enough that a bump reaches the estate within a working day rather
   * than waiting for somebody to press Stop. This sweep only ever acts on a container that is
   * already running the wrong image, so the cost of being wrong is a restart, not a lost afternoon.
   *
   * <p><b>Zero or negative is the KILL SWITCH for the whole sweep</b>, the convention {@code
   * qits.projects.agent-idle-timeout} established and {@code
   * qits.projects.agent-stale-quiet-window} follows: the pass returns having done nothing. It
   * emphatically does <em>not</em> mean "stop every stale container now" — a quiet window of zero
   * read as a deadline would stop every refinement on the estate on the first pass after an image
   * release, which is the one reading an operator reaching for a kill switch can least afford to
   * get.
   */
  @ConfigProperty(name = "qits.projects.refinement-stale-quiet-window", defaultValue = "PT30M")
  Duration quietWindow;

  /**
   * How often the sweep runs. A floor on how late a stop is, not a window: a stale container that
   * falls quiet a minute after a pass is stopped by the next one.
   *
   * <p>Gated to {@link LaunchMode#NORMAL}, the gate {@code AgentCredentialReconcile} and {@code
   * AgentStaleImageSweep} carry: a suite or a {@code quarkus:dev} session must not start stopping
   * containers in the background. The suite drives {@link #sweep(Instant)} directly instead, which
   * is the whole reason it takes a clock.
   */
  @Scheduled(
      every = "{qits.projects.refinement-stale-sweep-interval}",
      concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
  void sweepStaleRefinements() {
    if (LaunchMode.current() != LaunchMode.NORMAL) {
      return;
    }
    sweepQuietly();
  }

  /**
   * One pass with every failure logged and none rethrown — a stop that could not be taken must not
   * cost the next container in the listing its pass, and a scheduler that swallows an exception
   * silently is how the defect this sweep answers ran unseen on the agent axis for a week.
   *
   * <p>{@link ActivateRequestContext} because the enumeration below reaches Panache — twice, once
   * for the refinement rows and once for the projects that name them — and a scheduler thread is
   * nobody's request. The same arrangement {@code AgentStaleImageSweep.sweepQuietly} carries, and
   * for the same reason.
   */
  @ActivateRequestContext
  void sweepQuietly() {
    try {
      sweep(Instant.now());
    } catch (RuntimeException e) {
      LOG.error("The refinement stale-image sweep failed — retried on the next interval.", e);
    }
  }

  /**
   * {@link #sweepStaleRefinements()} as of a given instant; answers how many containers it stopped.
   *
   * <p>The instant is a parameter so a test can travel past the quiet window with a fake clock
   * rather than sleeping for one or booting a second application to shorten it.
   */
  int sweep(Instant now) {
    if (quietWindow == null || quietWindow.isZero() || quietWindow.isNegative()) {
      return 0;
    }
    // One reading of the pin per pass, from the one accessor that honours the emergency override.
    String pin = factory.imageVersion();
    Instant quietSince = now.minus(quietWindow);
    Map<String, Long> byContainerName = liveRefinementsByContainerName();
    int stopped = 0;
    for (RefinementRuntime.ContainerInfo container : runtime.listRefinementContainers()) {
      if (!container.running()) {
        // Nothing to do and nothing to say: a stopped container picks the pin up on its next wake,
        // which is the whole mechanism this sweep exists to reach.
        continue;
      }
      Long refinementId = byContainerName.get(container.containerName());
      if (refinementId == null) {
        // No live refinement answers to this name — a discarded row's container, or one whose
        // project is gone. Every action past the stop is addressed by a row id there is no longer
        // one of.
        LOG.debugf(
            "Skipping the refinement container %s: no live refinement is named by it",
            container.containerName());
        continue;
      }
      Optional<RefinementDaemonRegistry.DaemonInfo> daemon = registry.lookup(refinementId);
      if (daemon.isEmpty()) {
        // No connected daemon, so nothing has told us what this container is running. "Could not
        // ask" is not "behind" — the same four-answer discipline RefinementRuntime.inspect carries
        // — and judging here would stop a container for the crime of having a daemon that is still
        // booting or has briefly dropped. The listing itself answers the same way one level up: an
        // orchestrator that would not answer comes back as an empty list, so a pass that could not
        // ask simply does nothing.
        LOG.debugf(
            "Not judging the container of refinement %s: no daemon is connected to say what it is"
                + " running",
            refinementId);
        continue;
      }
      String reported = daemon.get().daemonVersion();
      if (!isBehind(reported, pin)) {
        // On the pin. Nothing at all happens here — in particular no runtime.touch. There is no
        // idle policy on this axis and nothing reads the orchestrator's idle clock for a refinement
        // container, so stamping it would be a write with no reader; and the clock belongs to the
        // orchestrator rather than to this sweep, which is not in the business of extending a
        // container's life as a side effect of deciding not to act on it.
        continue;
      }
      if (!isQuiet(refinementId, quietSince)) {
        // Named once per pass, at WARN, deliberately. A container that is never quiet would
        // otherwise be stale for ever with nothing said about it, and silence is precisely how the
        // defect this sweep answers ran unseen. Stopping it by hand through the existing Stop verb
        // is the intended escape, and it is available to whoever reads this line.
        LOG.warnf(
            "The container of refinement %s reports daemon %s but the pin is %s, and it is not"
                + " quiet enough to stop — press Stop on it to pick the pinned image up",
            refinementId, reported == null ? "no version" : reported, pin);
        continue;
      }
      LOG.infof(
          "Stopping the container of refinement %s: it reports daemon %s and the pin is %s. The"
              + " next wake replaces it; the checkout and the credential survive.",
          refinementId, reported == null ? "no version" : reported, pin);
      refinements.stopContainer(refinementId);
      stopped++;
    }
    return stopped;
  }

  /**
   * Whether the daemon a container reports is not the one this service pins.
   *
   * <p><b>Any difference is behind, by string inequality and never by a calver ordering.</b> A
   * container <em>ahead</em> of the pin violates "the daemon and the host were built and tested
   * together" exactly as much as one behind it — it is an image nobody here gated — and the way one
   * appears is {@code qits.projects.refinement-image-version-override} being taken back off, which
   * is a moment the estate should converge on the pin rather than keep whatever was newest.
   *
   * <p><b>A null or blank version is behind.</b> {@code daemonVersion} is on this protocol's
   * {@code Hello} and has been since it had one, so an image that announces none is older than the
   * harness that would read it. Reading the absence as "cannot tell" would make the very oldest
   * containers the only ones this sweep never reaches.
   */
  private static boolean isBehind(String reported, String pin) {
    return reported == null || reported.isBlank() || !reported.equals(pin);
  }

  /**
   * Whether this container is quiet enough to take away — <b>both</b> halves, because each catches
   * what the other cannot.
   *
   * <p>The <b>stamp</b> catches what produces no {@link
   * eu.wohlben.qits.workspacedaemon.protocol.AgentActivity} at all: an open terminal, a person
   * reading files through the proxy, a browser holding the refining page. None of that is an agent
   * session and none of it would appear in the rollup, and all of it is somebody working in the
   * container.
   *
   * <p>The <b>rollup</b> catches what the stamp ages out of: an agent thinking between two frames.
   * A long tool call is minutes of silence in the middle of a turn, and the stamp keeps ageing
   * through it — so a container could pass the window while an agent was mid-sentence. {@code BUSY}
   * and {@code WAITING} both say a session is live and neither is a moment to stop a container in;
   * {@code IDLE} and {@code ENDED} say it is not.
   *
   * <p><b>The rollup's veto is bounded, and it has to be.</b> A session that stops reporting
   * without ever saying it ended — an agent that died before its {@code Stop} hook fired, and the
   * control socket's reconnect adoption re-asserting it on every restart of this service — would
   * otherwise hold a {@code BUSY} for ever and veto this sweep permanently, on precisely the
   * long-lived containers it exists to reach. The key {@code
   * qits.projects.refinement.stale-activity-ttl-ms} is where that bound lives and where its four
   * hours are argued; it is strictly longer than the
   * quiet window above, because at equal values the rollup could never veto anything the stamp had
   * not already vetoed and this second condition would quietly stop meaning anything.
   *
   * <p><b>A container that has never been stamped is quiet.</b> Nothing has ever happened in it —
   * one that outlived a restart of this service, or whose daemon connected and did nothing since —
   * and that is the emptiest a container gets rather than an unknown to be cautious about. Treating
   * it as unknown would leave the longest-stale containers on the estate untouched for ever.
   */
  private boolean isQuiet(Long refinementId, Instant quietSince) {
    Optional<Instant> lastStamp = registry.lastAgentActivityAt(refinementId);
    if (lastStamp.isPresent() && lastStamp.get().isAfter(quietSince)) {
      return false;
    }
    String state = registry.agentActivity(refinementId).orElse(null);
    return !DaemonProtocol.AgentState.BUSY.equals(state)
        && !DaemonProtocol.AgentState.WAITING.equals(state);
  }

  /**
   * Every live refinement's container name to its row id.
   *
   * <p><b>Two reads per pass and no network call at all</b>, which is the shape {@code
   * AgentStaleImageSweep.liveProjectsByContainerName} keeps and the reason the name is computed
   * here rather than asked for per row. A refinement's container name is {@code
   * qits-ref-<projectSlug>-<epicSlug>}: the epic slug is on the row (the branch it cut is {@code
   * refining/<epicSlug>}, which {@link RefinementService#epicSlugOf} is the one reading of), and
   * the project slug comes from the projects table — so one listing of each, folded into a map,
   * answers for every container in the orchestrator's listing. Going the other way, from container
   * to row, would be {@link RefinementService#view} per name: a drift read and an {@code inspect}
   * each, which is the read this axis has already once had to take off a page.
   *
   * <p>A refinement whose project has gone, or whose slug is blank, is simply not in the map, and
   * the container that answers to its name is skipped for the same reason a discarded row's is: the
   * stop is addressed by a row id, and this is not one that can be acted on.
   */
  private Map<String, Long> liveRefinementsByContainerName() {
    Map<String, String> slugsByProjectId = new HashMap<>();
    for (Project project : liveProjects()) {
      if (project.slug != null && !project.slug.isBlank()) {
        slugsByProjectId.put(project.id, project.slug);
      }
    }
    Map<String, Long> byName = new HashMap<>();
    for (Refinement refinement : liveRefinements()) {
      String projectSlug = slugsByProjectId.get(refinement.projectId);
      if (projectSlug == null) {
        continue;
      }
      byName.put(
          factory.containerName(projectSlug, RefinementService.epicSlugOf(refinement)),
          refinement.id);
    }
    return byName;
  }

  /**
   * The refinements a container name may belong to. One method so the suite can supply them without
   * a database, which is what keeps this test plain JUnit — the seam {@code
   * AgentStaleImageSweep.liveProjects} is, for the same reason.
   *
   * <p>The transaction is opened here because {@link RefinementRepository} deliberately opens none,
   * the split every repository in that package keeps.
   */
  List<Refinement> liveRefinements() {
    return QuarkusTransaction.requiringNew().call(store::listAll);
  }

  /** The projects those refinements hang off — the other half of a container name, same seam. */
  List<Project> liveProjects() {
    return projectService.list();
  }
}
