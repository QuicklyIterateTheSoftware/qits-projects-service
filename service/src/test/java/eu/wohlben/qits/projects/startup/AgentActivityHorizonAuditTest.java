package eu.wohlben.qits.projects.startup;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Plain JUnit 5, deliberately no {@code @QuarkusTest}: {@link AgentActivityHorizonAudit#violation}
 * is pure, and {@link AgentActivityHorizonAudit#audit()} needs only the two fields it already
 * declares package-private for exactly this. Booting the application to assert a comparison of two
 * longs would be the expensive way to ask this question.
 */
public class AgentActivityHorizonAuditTest {

  @Test
  public void theShippedPairIsFine() {
    assertTrue(
        AgentActivityHorizonAudit.violation(Duration.ofMinutes(30), 14_400_000L).isEmpty());
  }

  @Test
  public void equalValuesIsAViolation() {
    Optional<String> violation =
        AgentActivityHorizonAudit.violation(Duration.ofMinutes(30), 1_800_000L);

    assertTrue(violation.isPresent());
    assertTrue(violation.get().contains("qits.projects.agent.stale-activity-ttl-ms"));
    assertTrue(violation.get().contains("qits.projects.agent-stale-quiet-window"));
    assertTrue(violation.get().contains("PT30M"));
    assertTrue(violation.get().contains("1800000"));
  }

  @Test
  public void aShorterTtlIsAViolation() {
    assertTrue(
        AgentActivityHorizonAudit.violation(Duration.ofMinutes(30), 60_000L).isPresent());
  }

  @Test
  public void aZeroTtlIsAViolation() {
    assertTrue(AgentActivityHorizonAudit.violation(Duration.ofMinutes(30), 0L).isPresent());
  }

  @Test
  public void aZeroWindowIsTheKillSwitchAndReportsNothing() {
    assertTrue(AgentActivityHorizonAudit.violation(Duration.ZERO, 14_400_000L).isEmpty());
  }

  @Test
  public void aNegativeWindowIsTheKillSwitchAndReportsNothing() {
    assertTrue(
        AgentActivityHorizonAudit.violation(Duration.ofMinutes(-1), 14_400_000L).isEmpty());
  }

  @Test
  public void aNullWindowReportsNothing() {
    assertTrue(AgentActivityHorizonAudit.violation(null, 14_400_000L).isEmpty());
  }

  @Test
  public void theShippedDefaultsAuditClean() {
    AgentActivityHorizonAudit audit = new AgentActivityHorizonAudit();
    audit.quietWindow = Duration.ofMinutes(30);
    audit.staleActivityTtlMs = 14_400_000L;

    assertFalse(audit.audit().isPresent());
  }

  @Test
  public void aBreachAuditsPresent() {
    AgentActivityHorizonAudit audit = new AgentActivityHorizonAudit();
    audit.quietWindow = Duration.ofMinutes(30);
    audit.staleActivityTtlMs = 1_800_000L;

    assertTrue(audit.audit().isPresent());
  }
}
