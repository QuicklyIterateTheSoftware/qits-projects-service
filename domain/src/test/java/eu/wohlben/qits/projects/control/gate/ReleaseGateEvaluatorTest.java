package eu.wohlben.qits.projects.control.gate;

import static eu.wohlben.qits.projects.control.gate.GateFixtures.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.control.ReleaseGates;
import eu.wohlben.qits.projects.dto.CommitBuildStatusDto;
import eu.wohlben.qits.projects.dto.ReleaseQualityGateDto;
import eu.wohlben.qits.projects.entity.ReleaseRequest.ApprovalState;
import eu.wohlben.qits.projects.entity.ReleasedTagPendingMerge;
import eu.wohlben.qits.projects.entity.ReleasedTagPendingMerge.PublishState;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The gate classes answer exactly what {@code ReleaseRequests.gateReport} answered before they
 * existed. {@link #before} is that method's evaluation, copied as it was; every combination of its
 * inputs must give the same kinds, order and states.
 */
class ReleaseGateEvaluatorTest {

  private static final List<ReleaseGate> GATES =
      List.of(
          new DeploymentRollbackGate(),
          new PublishRunGate(),
          new ApprovalGate(),
          new AutomationsGate(),
          new CiBuildGate());

  @Test
  void everyCombinationAnswersWhatTheOldReportAnswered() {
    List<ReleaseGates.GateSet> sets =
        List.of(
            unreadable(),
            configured(),
            configured(ReleaseGates.Kind.CI),
            configured(ReleaseGates.Kind.APPROVAL),
            configured(ReleaseGates.Kind.CI, ReleaseGates.Kind.DEPLOYMENT),
            configured(ReleaseGates.Kind.CI, ReleaseGates.Kind.APPROVAL, ReleaseGates.Kind.DEPLOYMENT));
    List<GateSubject.Approval> approvals = new ArrayList<>();
    approvals.add(notRequired());
    for (ApprovalState state : ApprovalState.values()) {
      approvals.add(approval(state));
    }
    List<GateSubject.Automations> automations = new ArrayList<>();
    automations.add(noAutomations());
    for (ReleaseGates.State state : ReleaseGates.State.values()) {
      automations.add(automations(state));
    }
    List<List<CommitBuildStatusDto>> verdicts =
        List.of(List.of(), List.of(green("g")), List.of(red("r")), List.of(green("g"), red("r")));
    List<ReleasedTagPendingMerge> releases = new ArrayList<>();
    releases.add(null);
    releases.add(released(null, false));
    releases.add(released(null, true));
    for (PublishState publish : PublishState.values()) {
      releases.add(released(publish, false));
      releases.add(released(publish, true));
    }
    int compared = 0;
    for (ReleaseGates.GateSet set : sets) {
      for (GateSubject.Approval approval : approvals) {
        for (GateSubject.Automations automation : automations) {
          for (List<CommitBuildStatusDto> verdict : verdicts) {
            for (ReleasedTagPendingMerge released : releases) {
              GateSubject subject = subject(set, approval, automation, verdict, released);
              List<ReleaseGates.Gate> now =
                  LegacyGates.of(ReleaseGateEvaluator.evaluate(GATES, subject), subject);
              assertEquals(before(subject), now, subject.toString());
              compared++;
            }
          }
        }
      }
    }
    assertTrue(compared > 1000);
  }

  @Test
  void anUnreadableSetSaysWhyOnEveryGateItHolds() {
    List<ReleaseGateEvaluator.Evaluated> answers =
        ReleaseGateEvaluator.evaluate(
            GATES,
            subject(
                unreadable(),
                notRequired(),
                automations(ReleaseGates.State.PASSED),
                List.of(),
                null));
    assertEquals(
        List.of("ci", "automations", "approval", "publish", "deployment-not-rolled-back"),
        answers.stream().map(answer -> answer.gate().kind()).toList());
    for (ReleaseGateEvaluator.Evaluated answer : answers) {
      if (answer.gate().kind().equals("automations")) {
        assertEquals(ReleaseGates.State.PASSED, answer.evaluation().state());
      } else {
        assertEquals(ReleaseGates.State.UNKNOWN, answer.evaluation().state());
        assertEquals("release-requests.yml does not parse", answer.evaluation().detail());
      }
    }
  }

  @Test
  void theWireShapeCarriesEverythingAPageDraws() {
    ReleaseGateEvaluator.Evaluated ci =
        ReleaseGateEvaluator.evaluate(
                GATES,
                subject(
                    configured(ReleaseGates.Kind.CI),
                    notRequired(),
                    noAutomations(),
                    List.of(red("r1")),
                    null))
            .get(0);
    ReleaseQualityGateDto dto = ReleaseGateEvaluator.toDto(ci);
    assertEquals("ci", dto.kind());
    assertEquals("CI build", dto.label());
    assertEquals("qa-publish", dto.position());
    assertEquals("FAILED", dto.state());
    assertEquals("r1", dto.runId());
    assertEquals(1, dto.checks().size());
    assertEquals("Run r1", dto.checks().get(0).name());
  }

  @Test
  void legacyKindsAreTheEnumNames() {
    for (ReleaseGates.Kind kind : ReleaseGates.Kind.values()) {
      assertEquals(
          Optional.of(kind),
          ReleaseGateEvaluator.legacyKind(kind.name().toLowerCase(java.util.Locale.ROOT)));
    }
    assertEquals(Optional.empty(), ReleaseGateEvaluator.legacyKind("security-scan"));
  }

  @Test
  void everyLegacyKindButDeploymentHasOneClassAndTheRollbackGateHasNone() {
    List<ReleaseGates.Kind> covered =
        GATES.stream()
            .flatMap(gate -> ReleaseGateEvaluator.legacyKind(gate.kind()).stream())
            .sorted()
            .toList();
    assertEquals(
        Arrays.stream(ReleaseGates.Kind.values())
            .filter(kind -> kind != ReleaseGates.Kind.DEPLOYMENT)
            .toList(),
        covered);
  }

  /** {@code ReleaseRequests.gateReport}'s evaluation before the gate classes, unchanged. */
  private static List<ReleaseGates.Gate> before(GateSubject subject) {
    ReleaseGates.GateSet set = subject.gateSet();
    GateSubject.Approval approval = subject.approval();
    GateSubject.Automations automations = subject.automations();
    List<CommitBuildStatusDto> verdicts = subject.verdicts();
    ReleasedTagPendingMerge released = subject.released();
    Map<ReleaseGates.Kind, ReleaseGates.State> states = new EnumMap<>(ReleaseGates.Kind.class);
    if (verdicts.stream().anyMatch(v -> !"SUCCESS".equals(v.status()))) {
      states.put(ReleaseGates.Kind.CI, ReleaseGates.State.FAILED);
    } else if (verdicts.stream().anyMatch(v -> "SUCCESS".equals(v.status()))) {
      states.put(ReleaseGates.Kind.CI, ReleaseGates.State.PASSED);
    }
    switch (approval.state()) {
      case APPROVED -> states.put(ReleaseGates.Kind.APPROVAL, ReleaseGates.State.PASSED);
      case DECLINED -> states.put(ReleaseGates.Kind.APPROVAL, ReleaseGates.State.FAILED);
      default -> {}
    }
    if (released != null && released.mergedAt != null) {
      states.put(ReleaseGates.Kind.DEPLOYMENT, ReleaseGates.State.PASSED);
    }
    ReleaseGates.GateSet reported =
        approval.required() ? set.with(ReleaseGates.Kind.APPROVAL) : set;
    if (automations.applies()) {
      reported = reported.with(ReleaseGates.Kind.AUTOMATIONS);
      states.put(ReleaseGates.Kind.AUTOMATIONS, automations.state());
    }
    if (released != null && released.publishState != null) {
      reported = reported.with(ReleaseGates.Kind.PUBLISH);
      states.put(
          ReleaseGates.Kind.PUBLISH,
          switch (released.publishState) {
            case PASSED -> ReleaseGates.State.PASSED;
            case FAILED -> ReleaseGates.State.FAILED;
            case PENDING -> ReleaseGates.State.PENDING;
          });
    }
    return ReleaseGates.report(reported, states).stream()
        .filter(gate -> gate.kind() != ReleaseGates.Kind.AUTOMATIONS || automations.applies())
        .map(
            gate ->
                gate.kind() == ReleaseGates.Kind.AUTOMATIONS
                    ? new ReleaseGates.Gate(gate.kind(), automations.state())
                    : gate)
        .toList();
  }
}
