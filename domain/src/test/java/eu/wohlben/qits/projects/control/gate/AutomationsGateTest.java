package eu.wohlben.qits.projects.control.gate;

import static eu.wohlben.qits.projects.control.gate.GateFixtures.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import eu.wohlben.qits.projects.control.ReleaseGates;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
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

  /**
   * NOT_APPLICABLE (qits-1178) is listed on the request and is no check here. DEFERRED is listed as
   * a DEFERRED check with the regenerate command, and the gate still passes (qits-1920).
   */
  @Test
  void aKindThatDoesNotApplyIsNoCheckAndOneQaChecksIsADeferredCheck() {
    ReleaseRequest row = request();
    row.projectId = "qits";
    row.repoName = "qits-landing-app";
    GateEvaluation answer =
        gate.evaluate(
            new GateSubject(
                row,
                configured(),
                notRequired(),
                automations(
                    ReleaseGates.State.PASSED,
                    automation("estate-pins", "Estate pins", "FRESH", null),
                    automation("screenshots", "Screenshot baselines", "DEFERRED", "checked by QA"),
                    automation("diagram", "Entity diagram", "NOT_APPLICABLE", "no entities")),
                List.of(),
                null,
                GateSubject.Deployment.NOT_ASKED));
    assertEquals(ReleaseGates.State.PASSED, answer.state());
    assertEquals(
        List.of("Estate pins", "Screenshot baselines"),
        answer.checks().stream().map(GateCheck::name).toList());
    assertEquals(
        List.of("PASSED", "DEFERRED"), answer.checks().stream().map(GateCheck::state).toList());
    assertEquals(
        "checked by QA: regenerate with qits release-request regenerate --project qits --repository"
            + " qits-landing-app --kind screenshots if QA reports it stale",
        answer.checks().get(1).detail());
    assertEquals("QA checks: screenshots", answer.detail());
  }

  /** A row with no address pair names the kind alone (qits-1920). */
  @Test
  void aDeferredCheckWithNoAddressNamesTheKindAlone() {
    GateEvaluation answer =
        gate.evaluate(
            subject(
                configured(),
                notRequired(),
                automations(
                    ReleaseGates.State.PASSED,
                    automation("cli-tapes", "CLI tapes", "DEFERRED", null),
                    automation("screenshots", "Screenshots", "WAIVED", null)),
                List.of(),
                null));
    assertEquals(
        "checked by QA: regenerate with qits release-request regenerate --kind cli-tapes if QA"
            + " reports it stale",
        answer.checks().get(0).detail());
    assertEquals(
        "A person waived the automations for this fold. QA checks: cli-tapes", answer.detail());
  }

  /** DEFERRED is its own check word, never PASSED (qits-1920). */
  @Test
  void deferredIsNeverPassed() {
    assertEquals("DEFERRED", AutomationsGate.checkState("DEFERRED"));
  }

  /** A pending gate whose only listed kind QA checks still waits for the automations to answer. */
  @Test
  void aDeferredKindIsNoPreRunKindToWaitFor() {
    assertEquals(
        "Waiting for the automations to answer",
        gate.evaluate(
                subject(
                    configured(),
                    notRequired(),
                    automations(
                        ReleaseGates.State.PENDING,
                        automation("cli-tapes", "CLI tapes", "DEFERRED", null)),
                    List.of(),
                    null))
            .detail());
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
