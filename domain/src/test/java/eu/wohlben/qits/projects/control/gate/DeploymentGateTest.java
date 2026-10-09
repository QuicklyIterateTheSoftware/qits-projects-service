package eu.wohlben.qits.projects.control.gate;

import static eu.wohlben.qits.projects.control.gate.GateFixtures.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.projects.control.ReleaseGates;
import eu.wohlben.qits.projects.entity.ReleasedTagPendingMerge;
import eu.wohlben.qits.projects.entity.ReleasedTagPendingMerge.PublishState;
import java.util.List;
import org.junit.jupiter.api.Test;

class DeploymentGateTest {

  private final DeploymentGate gate = new DeploymentGate();

  private GateSubject with(ReleaseGates.GateSet set, ReleasedTagPendingMerge released) {
    return subject(set, notRequired(), noAutomations(), List.of(), released);
  }

  @Test
  void appliesOnlyWhereMainDeclaresADeployment() {
    assertEquals(
        GateApplicability.APPLIES, gate.applicability(with(configured(ReleaseGates.Kind.DEPLOYMENT), null)));
    assertEquals(GateApplicability.DOES_NOT_APPLY, gate.applicability(with(configured(), null)));
    assertEquals(GateApplicability.UNREADABLE, gate.applicability(with(unreadable(), null)));
  }

  @Test
  void passesOnceTheTagReachedMain() {
    ReleaseGates.GateSet set = configured(ReleaseGates.Kind.DEPLOYMENT);
    assertEquals(ReleaseGates.State.PENDING, gate.evaluate(with(set, null)).state());
    assertEquals("Nothing is released yet", gate.evaluate(with(set, null)).detail());
    GateEvaluation deploying = gate.evaluate(with(set, released(PublishState.PASSED, false)));
    assertEquals(ReleaseGates.State.PENDING, deploying.state());
    assertEquals("Waiting for 2026.1009.100000 to deploy and reach main", deploying.detail());
    assertEquals(
        ReleaseGates.State.PASSED, gate.evaluate(with(set, released(PublishState.PASSED, true))).state());
  }
}
