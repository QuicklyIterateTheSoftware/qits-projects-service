package eu.wohlben.qits.projects.control.gate;

import static eu.wohlben.qits.projects.control.gate.GateFixtures.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import eu.wohlben.qits.projects.control.ReleaseGates;
import java.util.List;
import org.junit.jupiter.api.Test;

class AutomationsGateTest {

  private final AutomationsGate gate = new AutomationsGate();

  @Test
  void appliesWhereQitsMaintenanceDoesEvenOnAnUnreadableSet() {
    assertEquals(
        GateApplicability.DOES_NOT_APPLY,
        gate.applicability(subject(configured(), notRequired(), noAutomations(), List.of(), null)));
    assertEquals(
        GateApplicability.APPLIES,
        gate.applicability(
            subject(unreadable(), notRequired(), automations(ReleaseGates.State.PENDING), List.of(), null)));
  }

  @Test
  void theStateIsTheOneAlreadyRead() {
    for (ReleaseGates.State state : ReleaseGates.State.values()) {
      assertEquals(
          state,
          gate.evaluate(subject(configured(), notRequired(), automations(state), List.of(), null)).state());
    }
  }

  @Test
  void eachAutomationIsACheckInGateWords() {
    GateEvaluation answer =
        gate.evaluate(
            subject(
                configured(),
                notRequired(),
                automations(
                    ReleaseGates.State.FAILED,
                    automation("estate-pins", "Estate pins", "FRESH", null),
                    automation("screenshots", "Screenshot baselines", "FAILED", "step 2 exited 1"),
                    automation("diagram", "Entity diagram", "RUNNING", null)),
                List.of(),
                null));
    assertEquals("Failed: Screenshot baselines", answer.detail());
    assertEquals(
        List.of("PASSED", "FAILED", "PENDING"), answer.checks().stream().map(GateCheck::state).toList());
    assertEquals("FAILED: step 2 exited 1", answer.checks().get(1).detail());
    assertEquals("run-screenshots", answer.checks().get(1).runId());
  }

  @Test
  void aWaiverSaysSo() {
    GateEvaluation answer =
        gate.evaluate(
            subject(
                configured(),
                notRequired(),
                automations(
                    ReleaseGates.State.PASSED, automation("screenshots", "Screenshots", "WAIVED", null)),
                List.of(),
                null));
    assertEquals("A person waived the automations for this fold", answer.detail());
    assertEquals("PASSED", answer.checks().get(0).state());
  }

  @Test
  void freshPassesQuietlyAndNothingOnRecordWaits() {
    assertNull(
        gate.evaluate(
                subject(
                    configured(),
                    notRequired(),
                    automations(ReleaseGates.State.PASSED, automation("a", "A", "FRESH", null)),
                    List.of(),
                    null))
            .detail());
    assertEquals(
        "Waiting for the automations to answer",
        gate.evaluate(
                subject(configured(), notRequired(), automations(ReleaseGates.State.PENDING), List.of(), null))
            .detail());
  }
}
