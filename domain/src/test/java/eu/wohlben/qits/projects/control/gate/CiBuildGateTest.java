package eu.wohlben.qits.projects.control.gate;

import static eu.wohlben.qits.projects.control.gate.GateFixtures.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import eu.wohlben.qits.projects.control.ReleaseGates;
import java.util.List;
import org.junit.jupiter.api.Test;

class CiBuildGateTest {

  private final CiBuildGate gate = new CiBuildGate();

  @Test
  void appliesOnlyWhereMainConfiguresIt() {
    assertEquals(GateApplicability.APPLIES, gate.applicability(subject(configured(ReleaseGates.Kind.CI))));
    assertEquals(GateApplicability.DOES_NOT_APPLY, gate.applicability(subject(configured())));
    assertEquals(GateApplicability.UNREADABLE, gate.applicability(subject(unreadable())));
  }

  @Test
  void noVerdictIsPending() {
    GateEvaluation answer = gate.evaluate(subject(configured(ReleaseGates.Kind.CI)));
    assertEquals(ReleaseGates.State.PENDING, answer.state());
    assertEquals("Waiting for a CI verdict for 0123456789", answer.detail());
    assertNull(answer.runId());
  }

  @Test
  void aGreenVerdictPasses() {
    GateEvaluation answer =
        gate.evaluate(
            subject(configured(ReleaseGates.Kind.CI), notRequired(), noAutomations(), List.of(green("g1")), null));
    assertEquals(ReleaseGates.State.PASSED, answer.state());
    assertEquals("g1", answer.runId());
    assertEquals(1, answer.checks().size());
    assertEquals("PASSED", answer.checks().get(0).state());
  }

  @Test
  void aRedVerdictFailsEvenBesideAGreenOne() {
    GateEvaluation answer =
        gate.evaluate(
            subject(
                configured(ReleaseGates.Kind.CI),
                notRequired(),
                noAutomations(),
                List.of(green("g1"), red("r1")),
                null));
    assertEquals(ReleaseGates.State.FAILED, answer.state());
    assertEquals("r1", answer.runId());
    assertEquals("Run r1 finished FAILED for 0123456789", answer.detail());
    assertEquals(List.of("PASSED", "FAILED"), answer.checks().stream().map(GateCheck::state).toList());
  }
}
