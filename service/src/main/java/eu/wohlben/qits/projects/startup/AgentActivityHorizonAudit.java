package eu.wohlben.qits.projects.startup;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.time.Duration;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Says, once per boot and loudly, whether this deployment has narrowed the agent activity horizon
 * past the point where it can still veto anything — ticket {@code e319ed55}.
 *
 * <p>The invariant is one comparison between two keys a deployment sets independently:
 *
 * <pre>    qits.projects.agent.stale-activity-ttl-ms &gt; qits.projects.agent-stale-quiet-window</pre>
 *
 * <p>{@code AgentStaleImageSweep} calls a container quiet only when <b>both</b> halves agree — the
 * heartbeat-free stamp is older than the quiet window, <em>and</em> the rolled-up agent state is not
 * {@code BUSY}/{@code WAITING}. The rollup half exists for the one case the stamp gets wrong: an agent
 * in the middle of a long tool call says nothing for minutes, so the stamp ages straight through a
 * live turn. But an entry older than {@code stale-activity-ttl-ms} is pruned on read
 * ({@code AgentDaemonRegistry.agentActivity}), so if that horizon is <b>shorter</b> than the quiet
 * window, every entry that could still veto has already been pruned by the time the sweep looks: the
 * rollup can only ever agree with the stamp, its whole reason for existing is gone, and <b>every test
 * in this repository still passes</b> — each one drives the two windows itself. That is exactly the
 * kind of silent loss only a boot-time reading of the live configuration can catch.
 *
 * <p>With the shipped defaults — four hours against {@code PT30M} — the margin is wide and this finds
 * nothing. It is for the deployment that narrows one of them, which nothing else would notice.
 *
 * <p><b>A zero or negative quiet window is not a violation.</b> That value is the stale-image sweep's
 * documented kill switch: the pass returns having done nothing, so there is no veto left to preserve
 * and nothing here to report. Reporting it would be an ERROR on every boot of a deployment that has
 * deliberately switched the sweep off.
 *
 * <p><b>It never fails boot, and it never blocks it.</b> A narrowed horizon is a reading of a live
 * deployment rather than a reason to refuse to serve one — the sweep keeps working, it just decides on
 * one half of the evidence instead of two — and the operator who set the value is the only one who can
 * choose. It runs on a virtual thread after startup, the shape {@link ReservedSlugAudit} and {@link
 * StartupSelfSeed} carry, so an audit can never be on the path readiness waits for.
 *
 * <p>Like {@link ReservedSlugAudit} it carries <b>no launch-mode gate</b>: it reads two configuration
 * values and reaches neither a database nor a network, so there is nothing here for a {@code
 * quarkus:dev} session or a suite to set off — and a gate would mean the check is exercised nowhere
 * but production, which is the one place a wrong pair is expensive.
 */
@ApplicationScoped
public class AgentActivityHorizonAudit {

  private static final Logger LOG = Logger.getLogger(AgentActivityHorizonAudit.class);

  static final String TTL_KEY = "qits.projects.agent.stale-activity-ttl-ms";

  static final String QUIET_WINDOW_KEY = "qits.projects.agent-stale-quiet-window";

  /** The horizon {@code AgentDaemonRegistry} prunes every state at; its default is four hours. */
  @ConfigProperty(name = TTL_KEY, defaultValue = "14400000")
  long staleActivityTtlMs;

  /** The window {@code AgentStaleImageSweep} measures quiet over; its default is thirty minutes. */
  @ConfigProperty(name = QUIET_WINDOW_KEY, defaultValue = "PT30M")
  Duration quietWindow;

  void onStart(@Observes StartupEvent event) {
    Thread.ofVirtual().name("qits-agent-activity-horizon-audit").start(this::auditQuietly);
  }

  /**
   * {@link #audit()} with its own failures swallowed. An audit is a report about a deployment; one
   * that cannot be read is not a finding, and the next boot asks again.
   */
  void auditQuietly() {
    try {
      audit();
    } catch (RuntimeException e) {
      LOG.warn(
          "The agent activity horizon audit could not read its configuration — retried on the next"
              + " boot.",
          e);
    }
  }

  /**
   * Reads the pair and logs the violation.
   *
   * @return the sentence logged at ERROR, or empty when the pair is sound — so a caller (the suite)
   *     can assert on it
   */
  public Optional<String> audit() {
    Optional<String> violation = violation(staleActivityTtlMs, quietWindow);
    if (violation.isEmpty()) {
      LOG.debugf(
          "Agent activity horizon audit: %s (%d ms) is longer than %s (%s).",
          TTL_KEY, staleActivityTtlMs, QUIET_WINDOW_KEY, quietWindow);
      return violation;
    }
    LOG.error(violation.get());
    return violation;
  }

  /**
   * The pure half: the sentence to report, or empty when there is nothing to report. Both values are
   * named in it, because "the horizon is too short" without the two numbers leaves the operator
   * hunting for which of two keys their deployment moved.
   */
  static Optional<String> violation(long staleActivityTtlMs, Duration quietWindow) {
    if (quietWindow == null || quietWindow.isZero() || quietWindow.isNegative()) {
      return Optional.empty();
    }
    if (staleActivityTtlMs > quietWindow.toMillis()) {
      return Optional.empty();
    }
    return Optional.of(
        String.format(
            "AGENT ACTIVITY HORIZON TOO SHORT: %s is %d ms (%s) and %s is %s (%d ms), so the"
                + " activity horizon is not strictly longer than the quiet window. Every session"
                + " entry that could veto a stop is pruned before the stale-image sweep reads it, so"
                + " the rollup half of its quietness check can only ever agree with the"
                + " heartbeat-free stamp and a container can be stopped in the middle of a long"
                + " turn. Nothing here can correct it — both values are this deployment's — so set"
                + " %s above %s.",
            TTL_KEY,
            staleActivityTtlMs,
            Duration.ofMillis(staleActivityTtlMs),
            QUIET_WINDOW_KEY,
            quietWindow,
            quietWindow.toMillis(),
            TTL_KEY,
            QUIET_WINDOW_KEY));
  }
}
