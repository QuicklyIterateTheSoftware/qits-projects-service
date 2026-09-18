package eu.wohlben.qits.projects.agenthost;

import eu.wohlben.qits.projects.control.ProjectService;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projectsdaemon.protocol.DaemonProtocol;
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
 * Stops a running project-agent container whose daemon is not the one this service pins, while
 * nobody is using it.
 *
 * <h2>What it exists for</h2>
 *
 * <p>A project agent container runs the image it was created with for ever. There is exactly one
 * door in this harness that applies a new pin — {@link AgentContainers#ensure}'s {@code
 * !existing.running()} arm, which sends {@code Recreate.ifChanged} through {@link
 * AgentContainerFactory#forRestart} — and that door is only reachable from a <b>stopped</b>
 * container. Nothing stopped one: {@link AgentDaemonRegistry#onMessage} stamps {@code lastActivity}
 * on every inbound frame including the heartbeat, the daemon heartbeats every twenty seconds
 * unconditionally, so {@link AgentIdleSweep}'s window never elapsed — and because that sweep {@link
 * ContainerRuntime#touch}es what it keeps, the orchestrator's own {@code IDLE_STOP} belt never fired
 * either. An image bump therefore reached a container only if a person happened to press Stop.
 *
 * <p>This sweep is the missing stop. It does not apply the pin and deliberately cannot: it hands the
 * container to the wake path, which already knows how to replace one.
 *
 * <h2>Why stopping is the whole action</h2>
 *
 * <p><b>It stops, and never removes</b> — the same rule every other verb in this harness keeps, and
 * here it is also what makes the fix a two-line one. {@code Recreate.ifChanged} on the next wake does
 * the replacement, and what has to survive that replacement survives it by construction: the checkout
 * is on the per-project {@code /workspace} volume, which the {@code IDLE_STOP} policy forbids the
 * orchestrator to remove, and the container's commissioned platform credential is read back out of
 * {@link AgentCommissions} rather than re-minted. So a stale container is stopped, woken by the next
 * person who opens the panel, and comes back on the pinned image with its work where it left it.
 *
 * <p><b>It moves the IMAGE and not the CHECKOUT.</b> The daemon skips its self-clone on a populated
 * {@code /workspace}, so a replaced container reattaches the same tree at the same commit — a new
 * daemon binary over an old checkout. That is a different problem with a different fix and its own
 * ticket (95889191, answered by the platform-access CLI's {@code checkout-daemon}); nothing here
 * touches a working tree, and nothing here should start to.
 *
 * <h2>Why it is not an arm of {@link AgentIdleSweep}</h2>
 *
 * <p>Different question, different clock. The idle sweep asks "is anybody using this project", which
 * is measured from everything the daemon says <em>including</em> the heartbeat, and that is correct
 * for what it decides. This one asks "is this container behind the pin, and is it quiet enough to
 * take away right now", which is measured from {@link AgentDaemonRegistry#lastAgentActivityAt} — the
 * stamp the heartbeat does not write. Folding the two together would put the heartbeat back into this
 * decision, which is the defect, or take it out of the idle one, which would make every live
 * container look reapable. The windows differ by an order of magnitude for the same reason: four
 * hours is "nobody came back today", thirty minutes is "nobody is mid-sentence".
 *
 * <p>It also touches nothing it keeps. Stamping the orchestrator's clock is {@link AgentIdleSweep}'s
 * job and that clock wants exactly one writer; a second sweep stamping it would quietly extend every
 * container's idle life by however often this one runs.
 */
@ApplicationScoped
public class AgentStaleImageSweep {

  private static final Logger LOG = Logger.getLogger(AgentStaleImageSweep.class);

  @Inject ContainerRuntime runtime;

  @Inject AgentDaemonRegistry registry;

  @Inject AgentContainers agentContainers;

  /** The one reading of the image pin — {@link AgentContainerFactory#imageVersion()}. */
  @Inject AgentContainerFactory factory;

  /** Where a container name is resolved back to the project that derives it. */
  @Inject ProjectService projectService;

  /**
   * How quiet a stale container has to be before it is taken away, measured from the last thing that
   * actually happened in it.
   *
   * <p>Thirty minutes: long enough that a person who stepped away mid-refinement comes back to the
   * container they left, short enough that a bump reaches an estate within a working day rather than
   * waiting for somebody to press Stop. It is not the idle window and must not be set to it — this
   * sweep only ever acts on a container that is already running the wrong image, so the cost of being
   * wrong is a restart, not a lost afternoon.
   *
   * <p><b>Zero or negative is the KILL SWITCH for the whole sweep</b>, following {@link
   * AgentIdleSweep#idleTimeout}'s convention exactly: the pass returns having done nothing.
   * It emphatically does <em>not</em> mean "stop every stale container now" — a quiet window of zero
   * read as a deadline would stop the entire estate on the first pass after an image release, which
   * is the one reading an operator reaching for a kill switch can least afford to get.
   */
  @ConfigProperty(name = "qits.projects.agent-stale-quiet-window", defaultValue = "PT30M")
  Duration quietWindow;

  /**
   * How often the sweep runs. A floor on how late a stop is, not a window: a stale container that
   * falls quiet a minute after a pass is stopped by the next one.
   *
   * <p>Gated to {@link LaunchMode#NORMAL}, the gate {@link AgentCredentialReconcile} carries: a suite
   * or a {@code quarkus:dev} session must not start stopping containers in the background. The suite
   * drives {@link #sweep(Instant)} directly instead, which is the whole reason it takes a clock.
   */
  @Scheduled(
      every = "{qits.projects.agent-stale-sweep-interval}",
      concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
  void sweepStaleAgents() {
    if (LaunchMode.current() != LaunchMode.NORMAL) {
      return;
    }
    sweepQuietly();
  }

  /**
   * One pass with every failure logged and none rethrown — a stop that could not be taken must not
   * cost the next container in the listing its pass, and a scheduler that swallows an exception
   * silently is how the original defect ran for a week.
   *
   * <p>{@link ActivateRequestContext} because {@link #liveProjects()} reaches Panache and a scheduler
   * thread is nobody's request; the same arrangement {@code AgentCredentialReconcile.reconcile}
   * carries, and for the same reason.
   */
  @ActivateRequestContext
  void sweepQuietly() {
    try {
      sweep(Instant.now());
    } catch (RuntimeException e) {
      LOG.error("The stale-image sweep failed — retried on the next interval.", e);
    }
  }

  /**
   * {@link #sweepStaleAgents()} as of a given instant; answers how many containers it stopped.
   *
   * <p>The instant is a parameter for the reason {@link AgentIdleSweep#sweep} takes one: a test can
   * travel past the quiet window with a fake clock rather than sleeping for one or booting a second
   * application to shorten it.
   */
  int sweep(Instant now) {
    if (quietWindow == null || quietWindow.isZero() || quietWindow.isNegative()) {
      return 0;
    }
    // One reading of the pin per pass, from the one accessor that honours the emergency override.
    String pin = factory.imageVersion();
    Instant quietSince = now.minus(quietWindow);
    Map<String, String> byContainerName = liveProjectsByContainerName();
    int stopped = 0;
    for (ContainerRuntime.ContainerInfo container : runtime.listAgentContainers()) {
      if (!container.running()) {
        // Nothing to do and nothing to say: a stopped container picks the pin up on its next wake,
        // which is the whole mechanism this sweep exists to reach.
        continue;
      }
      String projectId = byContainerName.get(container.name());
      if (projectId == null) {
        // No live project answers to this name — the same skip AgentIdleSweep makes, for the same
        // reason: the stop below is addressed by a project id there is no longer one of.
        LOG.debugf(
            "Skipping the agent container %s: no live project is named by it", container.name());
        continue;
      }
      Optional<AgentDaemonRegistry.DaemonInfo> daemon = registry.lookup(projectId);
      if (daemon.isEmpty()) {
        // No connected daemon, so nothing has told us what this container is running. "Could not
        // ask" is not "behind" — the same four-answer discipline ContainerRuntime.inspect carries —
        // and judging here would stop a container for the crime of having a daemon that is still
        // booting or has briefly dropped.
        LOG.debugf(
            "Not judging the agent container of project %s: no daemon is connected to say what it"
                + " is running",
            projectId);
        continue;
      }
      String reported = daemon.get().daemonVersion();
      if (!isBehind(reported, pin)) {
        // On the pin. Nothing at all happens here — in particular no runtime.touch: that clock is
        // AgentIdleSweep's and wants exactly one writer.
        continue;
      }
      if (!isQuiet(projectId, quietSince)) {
        // Named once per pass, at WARN, deliberately. A container that is never quiet would
        // otherwise be stale for ever with nothing said about it, and silence is precisely how the
        // defect this sweep answers ran for a week. Stopping it by hand through the existing Stop
        // verb is the intended escape, and it is available to whoever reads this line.
        LOG.warnf(
            "The agent container of project %s reports daemon %s but the pin is %s, and it is not"
                + " quiet enough to stop — press Stop on it to pick the pinned image up",
            projectId, reported == null ? "no version" : reported, pin);
        continue;
      }
      LOG.infof(
          "Stopping the agent container of project %s: it reports daemon %s and the pin is %s. The"
              + " next wake replaces it; the checkout and the credential survive.",
          projectId, reported == null ? "no version" : reported, pin);
      agentContainers.stop(projectId);
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
   * appears is an operator's emergency override being taken back off, which is a moment the estate
   * should converge on the pin rather than keep whatever was newest.
   *
   * <p><b>A null or blank version is behind.</b> {@code daemonVersion} has been on this protocol's
   * wire since its first commit, so an image that announces none is older than the harness that would
   * read it. Reading the absence as "cannot tell" would make the very oldest containers the only ones
   * this sweep never reaches.
   */
  private static boolean isBehind(String reported, String pin) {
    return reported == null || reported.isBlank() || !reported.equals(pin);
  }

  /**
   * Whether this container is quiet enough to take away — <b>both</b> halves, because each catches
   * what the other cannot.
   *
   * <p>The <b>stamp</b> catches what produces no {@link
   * eu.wohlben.qits.projectsdaemon.protocol.AgentActivity} at all: an open terminal, a person reading
   * files through the proxy, a browser holding the panel. None of that is an agent session and none
   * of it would appear in the rollup, and all of it is somebody working in the container.
   *
   * <p>The <b>rollup</b> catches what the stamp ages out of: an agent thinking between two frames.
   * A long tool call is minutes of silence in the middle of a turn, and the stamp keeps ageing
   * through it — so a container could pass the window while an agent was mid-sentence. {@code BUSY}
   * and {@code WAITING} both say a session is live and neither is a moment to stop a container in;
   * {@code IDLE} and {@code ENDED} say it is not.
   *
   * <p><b>A container that has never been stamped is quiet.</b> Nothing has ever happened in it —
   * one that outlived a restart of this service, or whose daemon connected and did nothing since —
   * and that is the emptiest a container gets rather than an unknown to be cautious about. It is a
   * different answer from the idle sweep's stamp-on-sight, and it is different because that sweep
   * would otherwise reap on first sight while this one would leave a stale image running for ever.
   */
  private boolean isQuiet(String projectId, Instant quietSince) {
    Optional<Instant> lastStamp = registry.lastAgentActivityAt(projectId);
    if (lastStamp.isPresent() && lastStamp.get().isAfter(quietSince)) {
      return false;
    }
    String state = registry.agentActivity(projectId).orElse(null);
    return !DaemonProtocol.AgentState.BUSY.equals(state)
        && !DaemonProtocol.AgentState.WAITING.equals(state);
  }

  /**
   * Every live project's container name to its id.
   *
   * <p>{@code AgentIdleSweep.liveProjectsByContainerName} is the same eight lines and carries the
   * whole argument for the shape — one read per pass rather than one call per container, projects
   * enumerated rather than containers. It is duplicated rather than shared because the alternative is
   * one of these two sweeps depending on the other, or a third type existing to hold two map puts;
   * neither buys anything, and the two sweeps are deliberately independent of each other.
   */
  private Map<String, String> liveProjectsByContainerName() {
    Map<String, String> byName = new HashMap<>();
    for (Project project : liveProjects()) {
      if (project.slug != null && !project.slug.isBlank()) {
        byName.put(runtime.containerName(project.slug), project.id);
      }
    }
    return byName;
  }

  /**
   * The projects a container name may belong to. One method so the suite can supply them without a
   * database, which is what keeps this test plain JUnit — see {@link AgentIdleSweep#liveProjects}.
   */
  List<Project> liveProjects() {
    return projectService.list();
  }
}
