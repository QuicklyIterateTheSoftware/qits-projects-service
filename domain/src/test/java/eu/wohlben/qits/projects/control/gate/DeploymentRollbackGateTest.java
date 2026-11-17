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

  /**
   * A deploy held for a person (qits-2268) is PENDING, never FAILED, and names the command that
   * releases it — over a recorded failure of an earlier deployment too.
   */
  @Test
  void aHeldDeployWaitsAndNamesTheApproval() {
    GateEvaluation held =
        gate.evaluate(
            with(
                DEPLOYS,
                released(PublishState.PASSED, false),
                GateSubject.Deployment.newest("dr-7", null, true)));
    assertEquals(ReleaseGates.State.PENDING, held.state());
    assertEquals(
        "Deploy of 2026.1009.100000 held for approval: qits deployments requests approve"
            + " --request dr-7",
        held.detail());
    assertEquals("Deployment request dr-7", held.checks().get(0).name());

    GateEvaluation overAFailure =
        gate.evaluate(
            with(DEPLOYS, failed("FAILED: boom"), GateSubject.Deployment.newest("dr-8", null, true)));
    assertEquals(ReleaseGates.State.PENDING, overAFailure.state());
    assertTrue(overAFailure.detail().startsWith("Deploy of 2026.1009.100000 held for approval"));
  }

  /** Once it went live, a held redeploy does not take the PASSED gate back. */
  @Test
  void aLiveReleaseIsNotHeldBack() {
    assertEquals(
        ReleaseGates.State.PASSED,
        gate.evaluate(with(DEPLOYS, live(), GateSubject.Deployment.newest("dr-7", null, true)))
            .state());
  }

  /**
   * The read is lenient (qits-2268): the flag wins where qits-deployments answers it, and a detail
   * saying so stands in where it does not; a settled or met gate is never held.
   */
  @Test
  void aViewIsHeldOnlyWhileItsGateIsUnmetAndUnsettled() {
    java.time.Instant at = java.time.Instant.parse("2026-10-09T10:20:00Z");
    String detail = "held for manual approval on contract-app-rr-1";
    assertTrue(view("UNMET", null, null, true).held());
    assertTrue(view("UNMET", null, detail, null).held(), "an older far side: read off the detail");
    assertTrue(!view("UNMET", null, detail, false).held(), "the flag wins where it is answered");
    assertTrue(!view("UNMET", null, "waiting on the gate", null).held());
    assertTrue(!view("UNMET", at, detail, true).held(), "a settled gate is not held");
    assertTrue(!view("MET", null, detail, true).held());
    assertTrue(!new eu.wohlben.qits.projects.control.DeploymentRequests.DeploymentRequestView(
            "d", "ACTIVE", at)
        .held());
  }

  /**
   * A declined deploy (qits-2271) is FAILED, naming who declined it, ahead of the PENDING a settled
   * UNMET request read as before — on a single read that asked qits-deployments, and on a list read
   * from this service's own stamp on the tag row.
   */
  @Test
  void aDeclinedDeployFailsNamingWhoDeclinedIt() {
    java.time.Instant at = java.time.Instant.parse("2026-10-09T10:40:00Z");
    GateEvaluation asked =
        gate.evaluate(
            with(
                DEPLOYS,
                released(PublishState.PASSED, false),
                GateSubject.Deployment.newest("dr-9", null, false, at, "alice")));
    assertEquals(ReleaseGates.State.FAILED, asked.state());
    assertEquals("Deploy of 2026.1009.100000 declined by alice", asked.detail());
    assertEquals("Deployment request dr-9", asked.checks().get(0).name());
    assertEquals("FAILED", asked.checks().get(0).state());

    ReleasedTagPendingMerge stamped = released(PublishState.PASSED, false);
    stamped.declinedAt = at;
    GateEvaluation listRead =
        gate.evaluate(with(DEPLOYS, stamped, GateSubject.Deployment.NOT_ASKED));
    assertEquals(ReleaseGates.State.FAILED, listRead.state());
    assertEquals("Deploy of 2026.1009.100000 declined", listRead.detail());
  }

  /** The view reads declined off {@code declinedAt}, and the note off the far side's detail. */
  @Test
  void aViewIsDeclinedByItsStampAndCarriesItsNote() {
    java.time.Instant at = java.time.Instant.parse("2026-10-09T10:40:00Z");
    var declined =
        new eu.wohlben.qits.projects.control.DeploymentRequests.DeploymentRequestView(
            "d", null, null, "UNMET", at, "declined by alice: wrong change", true, "alice", at);
    assertTrue(declined.declined());
    assertTrue(!declined.held(), "settled, so no longer held");
    assertEquals("wrong change", declined.declineNote());
    var bare =
        new eu.wohlben.qits.projects.control.DeploymentRequests.DeploymentRequestView(
            "d", null, null, "UNMET", at, "declined by alice", true, "alice", at);
    assertEquals(null, bare.declineNote());
    assertTrue(!view("UNMET", null, null, true).declined());
  }

  private static eu.wohlben.qits.projects.control.DeploymentRequests.DeploymentRequestView view(
      String gate, java.time.Instant settled, String detail, Boolean hold) {
    return new eu.wohlben.qits.projects.control.DeploymentRequests.DeploymentRequestView(
        "d", null, null, gate, settled, detail, hold);
  }

  private static ReleasedTagPendingMerge failed(String recorded) {
    ReleasedTagPendingMerge tag = released(PublishState.PASSED, false);
    tag.deploymentFailedAt = java.time.Instant.parse("2026-10-09T10:20:00Z");
    tag.deploymentFailedDetail = recorded;
    return tag;
  }

  /**
   * qits-1690: a failure recorded on the released row is FAILED on the LIST read, which asks
   * qits-deployments nothing — rolled back or failed, with the first line and the remedy.
   */
  @Test
  void aRecordedFailureFailsEvenOnAListRead() {
    GateEvaluation rolledBack =
        gate.evaluate(
            with(
                DEPLOYS,
                failed("ROLLED_BACK: task: non-zero exit"),
                GateSubject.Deployment.NOT_ASKED));
    assertEquals(ReleaseGates.State.FAILED, rolledBack.state());
    assertTrue(
        rolledBack
            .detail()
            .startsWith("2026.1009.100000 was rolled back: task: non-zero exit — redeploy"),
        rolledBack.detail());
    assertTrue(
        rolledBack
            .detail()
            .endsWith(
                " — redeploy with `qits release-request rerun --request repo-1-rr-1 --phase"
                    + " DEPLOY`"),
        rolledBack.detail());

    GateSubject unknownRequest =
        new GateSubject(
            null,
            DEPLOYS,
            notRequired(),
            noAutomations(),
            List.of(),
            failed("ROLLED_BACK"),
            GateSubject.Deployment.NOT_ASKED);
    assertEquals(
        "2026.1009.100000 was rolled back — redeploy by re-running its DEPLOY phase",
        gate.evaluate(unknownRequest).detail());

    GateEvaluation failed =
        gate.evaluate(with(DEPLOYS, failed("FAILED: no node"), GateSubject.Deployment.NOT_ASKED));
    assertEquals(ReleaseGates.State.FAILED, failed.state());
    assertTrue(
        failed.detail().startsWith("2026.1009.100000 failed to deploy: no node"), failed.detail());

    GateEvaluation unreachable =
        gate.evaluate(with(DEPLOYS, failed("ROLLED_BACK"), GateSubject.Deployment.COULD_NOT_ASK));
    assertEquals(ReleaseGates.State.FAILED, unreachable.state(), "the record stands on its own");
  }

  @Test
  void aRecordedFailureYieldsToWentLiveAndToARedeployUnderWay() {
    ReleasedTagPendingMerge live = failed("ROLLED_BACK");
    live.deploymentActiveAt = java.time.Instant.parse("2026-10-09T10:30:00Z");
    assertEquals(
        ReleaseGates.State.PASSED,
        gate.evaluate(with(DEPLOYS, live, GateSubject.Deployment.NOT_ASKED)).state());
    assertEquals(
        ReleaseGates.State.PENDING,
        gate.evaluate(
                with(
                    DEPLOYS,
                    failed("ROLLED_BACK"),
                    GateSubject.Deployment.newest("d2", "STARTING")))
            .state(),
        "a redeploy under way is believed over the record");
    assertEquals(
        ReleaseGates.State.PENDING,
        gate.evaluate(
                with(
                    DEPLOYS,
                    released(PublishState.PASSED, false),
                    GateSubject.Deployment.NOT_ASKED))
            .state(),
        "no failure recorded: still waiting");
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
  // -----------------------------------------------------------------------------------------------
  // Variants (qits-2296)
  // -----------------------------------------------------------------------------------------------

  private static GateCheck variant(String state, String status) {
    return new GateCheck("qits-edge-private", state, status, null);
  }

  /** One check per declared variant, after the release's own; none for a release without any. */
  @Test
  void eachDeclaredVariantIsACheck() {
    GateEvaluation live =
        gate.evaluate(
            with(
                DEPLOYS,
                live(),
                GateSubject.Deployment.newest("d1", "ACTIVE")
                    .withVariants(List.of(variant("PASSED", "ACTIVE")))));
    assertEquals(ReleaseGates.State.PASSED, live.state());
    assertEquals(2, live.checks().size());
    assertEquals("Deployment request d1", live.checks().get(0).name());
    assertEquals("qits-edge-private", live.checks().get(1).name());
    assertEquals("PASSED", live.checks().get(1).state());
    assertEquals("ACTIVE", live.checks().get(1).detail());

    assertEquals(
        1,
        gate.evaluate(with(DEPLOYS, live(), GateSubject.Deployment.newest("d1", "ACTIVE")))
            .checks()
            .size(),
        "a release without variants answers the checks it always did");
  }

  /** The main is ACTIVE and the variant has no row yet: the gate waits and names the variant. */
  @Test
  void aPendingVariantHoldsTheGateAndIsNamed() {
    GateEvaluation waiting =
        gate.evaluate(
            with(
                DEPLOYS,
                released(PublishState.PASSED, false),
                GateSubject.Deployment.newest("d1", "ACTIVE")
                    .withVariants(List.of(variant("PENDING", "No deployment yet")))));
    assertEquals(ReleaseGates.State.PENDING, waiting.state());
    assertEquals(
        "Waiting for 2026.1009.100000 to go live in every variant it declares: qits-edge-private",
        waiting.detail());
  }

  /**
   * rr-165 on a single read: the variant failed and finalization recorded it — the main reads
   * ACTIVE, and that is not a redeploy under way. The detail names the variant.
   */
  @Test
  void aRecordedVariantFailureIsFailedBesideAnActiveMain() {
    GateEvaluation answer =
        gate.evaluate(
            with(
                DEPLOYS,
                failed("qits-edge-private FAILED: registry DNS lookup failed"),
                GateSubject.Deployment.newest("d1", "ACTIVE")
                    .withVariants(List.of(variant("FAILED", "FAILED")))));
    assertEquals(ReleaseGates.State.FAILED, answer.state());
    assertTrue(answer.detail().contains("qits-edge-private FAILED"), answer.detail());
    assertTrue(answer.detail().contains("registry DNS lookup failed"), answer.detail());
    assertEquals("FAILED", answer.checks().get(1).state());
  }

  /** A variant read failed before finalization recorded anything is FAILED on that read too. */
  @Test
  void anUnrecordedVariantFailureIsFailedOnASingleRead() {
    GateEvaluation answer =
        gate.evaluate(
            with(
                DEPLOYS,
                released(PublishState.PASSED, false),
                GateSubject.Deployment.newest("d1", "ACTIVE")
                    .withVariants(List.of(variant("FAILED", "IMAGE_MISSING")))));
    assertEquals(ReleaseGates.State.FAILED, answer.state());
    assertEquals(
        "2026.1009.100000 failed to deploy: qits-edge-private IMAGE_MISSING", answer.detail());
  }
}
