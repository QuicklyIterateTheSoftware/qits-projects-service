package eu.wohlben.qits.projects.control.gate;

import static eu.wohlben.qits.projects.control.gate.GateFixtures.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.control.ReleaseGates;
import eu.wohlben.qits.projects.entity.ReleasedTagPendingMerge;
import eu.wohlben.qits.projects.entity.ReleasedTagPendingMerge.PublishState;
import java.util.List;
import org.junit.jupiter.api.Test;

class DeploymentRollbackGateTest {

  private final DeploymentRollbackGate gate = new DeploymentRollbackGate();

  private static final ReleaseGates.GateSet DEPLOYS = configured(ReleaseGates.Kind.DEPLOYMENT);

  private GateSubject with(
      ReleaseGates.GateSet set, ReleasedTagPendingMerge released, GateSubject.Deployment deployment) {
    return new GateSubject(
        request(), set, notRequired(), noAutomations(), List.of(), released, deployment);
  }

  private static ReleasedTagPendingMerge live() {
    ReleasedTagPendingMerge tag = released(PublishState.PASSED, false);
    tag.deploymentActiveAt = java.time.Instant.parse("2026-10-09T10:30:00Z");
    return tag;
  }

  @Test
  void appliesOnlyWhereMainDeclaresADeployment() {
    assertEquals(GateApplicability.APPLIES, gate.applicability(with(DEPLOYS, null, null)));
    assertEquals(GateApplicability.DOES_NOT_APPLY, gate.applicability(with(configured(), null, null)));
    assertEquals(GateApplicability.UNREADABLE, gate.applicability(with(unreadable(), null, null)));
    assertEquals(ReleaseGatePosition.DEPLOY_FINALIZED, gate.position());
  }

  @Test
  void nothingReleasedOrNotLiveYetWaits() {
    assertEquals("Nothing is released yet", gate.evaluate(with(DEPLOYS, null, null)).detail());
    GateEvaluation waiting =
        gate.evaluate(
            with(
                DEPLOYS,
                released(PublishState.PASSED, false),
                GateSubject.Deployment.newest("d1", "STARTING")));
    assertEquals(ReleaseGates.State.PENDING, waiting.state());
    assertEquals("Waiting for 2026.1009.100000 to go live", waiting.detail());
    assertEquals("STARTING", waiting.checks().get(0).detail());
  }

  @Test
  void aRollbackFails() {
    GateEvaluation answer =
        gate.evaluate(with(DEPLOYS, live(), GateSubject.Deployment.newest("d1", "ROLLED_BACK")));
    assertEquals(ReleaseGates.State.FAILED, answer.state());
    assertEquals("2026.1009.100000 was rolled back", answer.detail());
    assertEquals("Deployment request d1", answer.checks().get(0).name());
    assertEquals("FAILED", answer.checks().get(0).state());
  }

  @Test
  void liveAndNotRolledBackPasses() {
    GateEvaluation asked =
        gate.evaluate(with(DEPLOYS, live(), GateSubject.Deployment.newest("d1", "ACTIVE")));
    assertEquals(ReleaseGates.State.PASSED, asked.state());
    assertEquals("2026.1009.100000 went live and is not rolled back", asked.detail());
    GateEvaluation listRead = gate.evaluate(with(DEPLOYS, live(), GateSubject.Deployment.NOT_ASKED));
    assertEquals(ReleaseGates.State.PASSED, listRead.state());
    assertTrue(listRead.detail().contains("does not check"));
    assertTrue(listRead.checks().isEmpty());
  }

  @Test
  void anUnreachableDeploymentServiceIsUnknown() {
    assertEquals(
        ReleaseGates.State.UNKNOWN,
        gate.evaluate(with(DEPLOYS, live(), GateSubject.Deployment.COULD_NOT_ASK)).state());
  }

  @Test
  void reachingMainIsNotThisGate() {
    ReleasedTagPendingMerge merged = released(PublishState.PASSED, true);
    merged.deploymentActiveAt = null;
    assertEquals(
        ReleaseGates.State.PENDING,
        gate.evaluate(with(DEPLOYS, merged, GateSubject.Deployment.NOT_ASKED)).state());
  }
}
