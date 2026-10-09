package eu.wohlben.qits.projects.control.gate;

import static eu.wohlben.qits.projects.control.gate.GateFixtures.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.projects.control.ReleaseGates;
import eu.wohlben.qits.projects.entity.ReleaseRequest.ApprovalState;
import java.util.List;
import org.junit.jupiter.api.Test;

class ApprovalGateTest {

  private final ApprovalGate gate = new ApprovalGate();

  private GateSubject with(ReleaseGates.GateSet set, GateSubject.Approval approval) {
    return subject(set, approval, noAutomations(), List.of(), null);
  }

  @Test
  void appliesWhereMainConfiguresItOrTheFoldRequiresIt() {
    assertEquals(
        GateApplicability.APPLIES,
        gate.applicability(with(configured(ReleaseGates.Kind.APPROVAL), notRequired())));
    assertEquals(
        GateApplicability.APPLIES, gate.applicability(with(configured(), approval(ApprovalState.WAITING))));
    assertEquals(GateApplicability.DOES_NOT_APPLY, gate.applicability(with(configured(), notRequired())));
    assertEquals(
        GateApplicability.UNREADABLE,
        gate.applicability(with(unreadable(), approval(ApprovalState.WAITING))));
  }

  @Test
  void theDecisionIsTheState() {
    GateEvaluation waiting = gate.evaluate(with(configured(), approval(ApprovalState.WAITING)));
    assertEquals(ReleaseGates.State.PENDING, waiting.state());
    assertEquals("manual-review is on", waiting.detail());

    GateEvaluation approved = gate.evaluate(with(configured(), approval(ApprovalState.APPROVED)));
    assertEquals(ReleaseGates.State.PASSED, approved.state());
    assertEquals("Approved by ada", approved.detail());

    GateEvaluation declined = gate.evaluate(with(configured(), approval(ApprovalState.DECLINED)));
    assertEquals(ReleaseGates.State.FAILED, declined.state());
    assertEquals("Declined by ada: not this week", declined.detail());

    assertEquals(
        ReleaseGates.State.PENDING,
        gate.evaluate(with(configured(ReleaseGates.Kind.APPROVAL), notRequired())).state());
  }
}
