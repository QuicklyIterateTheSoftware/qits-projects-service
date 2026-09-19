package eu.wohlben.qits.projects.startup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The boot-time report on a deployment that has narrowed the agent activity horizon past the point
 * where it can still veto anything — ticket {@code e319ed55}, and the invariant ticket {@code
 * 5f52c45b}'s second TTL introduced.
 *
 * <p>{@code ReservedSlugAuditTest}'s shape, with one deliberate difference: that audit reads a table,
 * so it needs an application to read it from. This one reads two configuration values and nothing
 * else, so the whole of it is reachable with the two fields set by hand — which is what keeps it plain
 * JUnit. A {@code @QuarkusTest} here would boot an application to assert a comparison, and a
 * {@code @TestProfile} per pair of numbers would be a whole Quarkus app each inside a 4 GB step cap.
 */
class AgentActivityHorizonAuditTest {

  /** The state of a healthy deployment, and the shipped one: four hours against thirty minutes. */
  @Test
  void theShippedPairReportsNothing() {
    assertTrue(audit(Duration.ofHours(4), Duration.ofMinutes(30)).audit().isEmpty());
  }

  /** A narrowed horizon is found, and the sentence names BOTH values. */
  @Test
  void aHorizonShorterThanTheQuietWindowIsReported() {
    Optional<String> reported = audit(Duration.ofMinutes(15), Duration.ofMinutes(30)).audit();

    String sentence = reported.orElseThrow();
    assertTrue(sentence.contains(AgentActivityHorizonAudit.TTL_KEY), sentence);
    assertTrue(sentence.contains(AgentActivityHorizonAudit.QUIET_WINDOW_KEY), sentence);
    assertTrue(sentence.contains("900000"), "the horizon's own value: " + sentence);
    assertTrue(sentence.contains("PT30M"), "and the window's: " + sentence);
  }

  /**
   * Equal is a violation, because the invariant is strictly longer. At exactly the window an entry is
   * pruned in the same instant the sweep would have read it, so the rollup half decides nothing.
   */
  @Test
  void equalIsAViolation() {
    assertTrue(audit(Duration.ofMinutes(30), Duration.ofMinutes(30)).audit().isPresent());
  }

  /**
   * A zero or negative quiet window is the stale-image sweep's kill switch — the pass returns having
   * done nothing — so there is no veto left to preserve and nothing to report. Reporting it would be
   * an ERROR on every boot of a deployment that switched the sweep off deliberately.
   */
  @Test
  void theSweepsKillSwitchIsNotAViolation() {
    assertEquals(
        Optional.empty(), AgentActivityHorizonAudit.violation(1_000L, Duration.ZERO));
    assertEquals(
        Optional.empty(), AgentActivityHorizonAudit.violation(1_000L, Duration.ofMinutes(-30)));
    assertEquals(Optional.empty(), AgentActivityHorizonAudit.violation(1_000L, null));
  }

  /**
   * The boot path is answered rather than thrown out of, even for a window this process could not
   * resolve at all. An audit is a report about a deployment; one that cannot be read is not a finding,
   * and nothing here may ever reach a {@code StartupEvent} observer.
   */
  @Test
  void anUnresolvableWindowIsAnsweredAndNeverThrown() {
    AgentActivityHorizonAudit audit = new AgentActivityHorizonAudit();
    audit.staleActivityTtlMs = Duration.ofMinutes(1).toMillis();
    audit.quietWindow = null;

    audit.auditQuietly();

    assertTrue(audit.audit().isEmpty());
  }

  private static AgentActivityHorizonAudit audit(Duration staleTtl, Duration quietWindow) {
    AgentActivityHorizonAudit audit = new AgentActivityHorizonAudit();
    audit.staleActivityTtlMs = staleTtl.toMillis();
    audit.quietWindow = quietWindow;
    return audit;
  }
}
