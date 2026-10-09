package eu.wohlben.qits.projects.control.gate;

import static eu.wohlben.qits.projects.control.gate.GateFixtures.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import eu.wohlben.qits.projects.control.ReleaseGates;
import eu.wohlben.qits.projects.entity.ReleasedTagPendingMerge;
import eu.wohlben.qits.projects.entity.ReleasedTagPendingMerge.PublishState;
import java.util.List;
import org.junit.jupiter.api.Test;

class PublishRunGateTest {

  private final PublishRunGate gate = new PublishRunGate();

  private GateSubject with(ReleaseGates.GateSet set, ReleasedTagPendingMerge released) {
    return subject(set, notRequired(), noAutomations(), List.of(), released);
  }

  @Test
  void appliesOnlyOnceTheReleasedTagDeclaredAPublishRun() {
    assertEquals(GateApplicability.DOES_NOT_APPLY, gate.applicability(with(configured(), null)));
    assertEquals(GateApplicability.DOES_NOT_APPLY, gate.applicability(with(configured(), released(null, false))));
    assertEquals(
        GateApplicability.APPLIES, gate.applicability(with(configured(), released(PublishState.PENDING, false))));
    assertEquals(
        GateApplicability.UNREADABLE,
        gate.applicability(with(unreadable(), released(PublishState.PENDING, false))));
  }

  @Test
  void thePublishStateIsTheState() {
    GateEvaluation failed = gate.evaluate(with(configured(), released(PublishState.FAILED, false)));
    assertEquals(ReleaseGates.State.FAILED, failed.state());
    assertEquals("Run red", failed.detail());
    assertEquals("publish-run", failed.runId());

    GateEvaluation passed = gate.evaluate(with(configured(), released(PublishState.PASSED, true)));
    assertEquals(ReleaseGates.State.PASSED, passed.state());
    assertNull(passed.detail());

    assertEquals(
        ReleaseGates.State.PENDING,
        gate.evaluate(with(configured(), released(PublishState.PENDING, false))).state());
  }
}
