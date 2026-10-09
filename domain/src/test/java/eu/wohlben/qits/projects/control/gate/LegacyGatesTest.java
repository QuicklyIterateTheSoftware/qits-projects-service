package eu.wohlben.qits.projects.control.gate;

import static eu.wohlben.qits.projects.control.gate.GateFixtures.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.projects.control.ReleaseGates;
import eu.wohlben.qits.projects.entity.ReleasedTagPendingMerge.PublishState;
import java.util.List;
import org.junit.jupiter.api.Test;

class LegacyGatesTest {

  @Test
  void theDeprecatedDeploymentEntryStillMeansReachedMain() {
    ReleaseGates.GateSet set = configured(ReleaseGates.Kind.DEPLOYMENT);
    GateSubject merged =
        subject(set, notRequired(), noAutomations(), List.of(), released(PublishState.PASSED, true));
    GateSubject notMerged =
        subject(set, notRequired(), noAutomations(), List.of(), released(PublishState.PASSED, false));
    assertEquals(
        new ReleaseGates.Gate(ReleaseGates.Kind.DEPLOYMENT, ReleaseGates.State.PASSED),
        last(merged));
    assertEquals(
        new ReleaseGates.Gate(ReleaseGates.Kind.DEPLOYMENT, ReleaseGates.State.PENDING),
        last(notMerged));
  }

  private static ReleaseGates.Gate last(GateSubject subject) {
    List<ReleaseGates.Gate> gates =
        LegacyGates.of(
            ReleaseGateEvaluator.evaluate(List.of(new DeploymentRollbackGate()), subject), subject);
    return gates.get(gates.size() - 1);
  }
}
