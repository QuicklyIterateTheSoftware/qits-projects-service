package eu.wohlben.qits.projects.startup;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.time.Duration;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Says, once per boot and loudly, whether the two horizons behind {@code deskhost/FrontDeskSpecRoll}'s
 * quiet rule and {@code agenthost/AgentDaemonRegistry}'s activity rollup still stand in the order the
 * rollup needs.
 *
 * <p>{@code qits.projects.agent.stale-activity-ttl-ms} ({@code
 * AgentDaemonRegistry.staleActivityTtlMs}) must stay <b>strictly longer than</b> {@code
 * qits.projects.agent-stale-quiet-window} ({@code FrontDeskSpecRoll.quietWindow}). At equal values, or with the TTL
 * shorter, the activity rollup can never veto a roll the heartbeat-free stamp has not already
 * vetoed — every entry old enough to survive as rollup evidence would also be a stamp inside the
 * quiet window, so the rollup's whole reason for existing (catching an agent thinking silently
 * between two frames) is deleted, silently and with every test still green. The symptom is a desk
 * recreated mid-turn under an agent that is still working, just not between heartbeats.
 *
 * <p>A quiet window of zero or negative is the kill switch documented on both config keys: it
 * disables the quiet rule outright, so there is nothing left for the rollup to veto and no breach to
 * report.
 *
 * <h2>Why this reads both keys itself rather than injecting the two beans</h2>
 *
 * <p>{@code FrontDeskSpecRoll} carries no required configuration of its own, but constructing it
 * reaches {@code FrontDeskSpecs}, whose configuration is required with no defaults — the same
 * mistake {@code agenthost/RetiredImageVersionKeys}' javadoc records a different bean making. A bean
 * observing {@link StartupEvent} is built at boot in every {@code @QuarkusTest} that boots this
 * application, so pulling either collaborator in here would force every such test in this module to
 * satisfy configuration it has nothing to do with. Reading the two {@code @ConfigProperty} values
 * directly, under their own names and their own defaults, keeps this bean constructible anywhere —
 * every property here is read with a default, so there is nothing a test would have to supply.
 *
 * <h2>Why it carries no launch-mode gate and no thread of its own</h2>
 *
 * <p>{@link ReservedSlugAudit} moves to a virtual thread because its query goes through the patient
 * postgres driver; this audit reaches no database and no network — it compares two
 * already-resolved configuration values — so it runs inline in the startup observer and costs
 * nothing. For the same reason it has no launch-mode gate, as {@link ReservedSlugAudit} has none:
 * there is nothing here for a {@code quarkus:dev} session or a suite to set off, and a gate would
 * mean the check is never exercised except in production.
 *
 * <h2>Why a boot-time ERROR and never a refusal</h2>
 *
 * <p>The two keys are configuration, not a request, so refusing to start over a misconfigured pair
 * would turn a tidy-up into an outage on every deployment carrying the bad values at once, for a
 * mistake that degrades one gate rather than stopping anything from working. Logging it loudly at boot, once, is what turns a silent invariant into one somebody actually reads.
 */
@ApplicationScoped
public class AgentActivityHorizonAudit {

  private static final Logger LOG = Logger.getLogger(AgentActivityHorizonAudit.class);

  @ConfigProperty(name = "qits.projects.agent-stale-quiet-window", defaultValue = "PT30M")
  Duration quietWindow;

  @ConfigProperty(name = "qits.projects.agent.stale-activity-ttl-ms", defaultValue = "14400000")
  long staleActivityTtlMs;

  void onStart(@Observes StartupEvent event) {
    try {
      audit();
    } catch (RuntimeException e) {
      LOG.warn("The agent activity horizon audit failed to run — retried on the next boot.", e);
    }
  }

  /**
   * Compares the two configured horizons and logs the finding: one ERROR on a breach, a DEBUG line
   * otherwise.
   *
   * @return the violation, if any, so a caller (the suite) can assert on it
   */
  public Optional<String> audit() {
    Optional<String> violation = violation(quietWindow, staleActivityTtlMs);
    if (violation.isPresent()) {
      LOG.error(violation.get());
    } else {
      LOG.debug("Agent activity horizon audit: the stale-activity TTL outlives the quiet window.");
    }
    return violation;
  }

  /**
   * The pure half: whether {@code staleActivityTtlMs} fails to stay strictly longer than {@code
   * quietWindow}.
   *
   * <p>A null, zero or negative {@code quietWindow} is the quiet rule's kill switch — nothing is
   * protected, so there is nothing to find a breach of.
   */
  static Optional<String> violation(Duration quietWindow, long staleActivityTtlMs) {
    if (quietWindow == null || quietWindow.isZero() || quietWindow.isNegative()) {
      return Optional.empty();
    }
    if (staleActivityTtlMs <= quietWindow.toMillis()) {
      return Optional.of(
          String.format(
              "AGENT ACTIVITY HORIZON MISCONFIGURED: qits.projects.agent.stale-activity-ttl-ms"
                  + " (%d ms, %s) is not strictly longer than"
                  + " qits.projects.agent-stale-quiet-window (%s). The activity rollup can no"
                  + " longer veto a roll the heartbeat-free stamp has not already vetoed; the TTL"
                  + " must be strictly longer than the window.",
              staleActivityTtlMs, Duration.ofMillis(staleActivityTtlMs), quietWindow));
    }
    return Optional.empty();
  }
}
