package eu.wohlben.qits.projects.control.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.control.ReleaseGates;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Gate 3 over a {@link GateSubject} (epic qits-897, qits-913). */
class UpstreamInFlightGateTest {

  private final UpstreamInFlightGate gate = new UpstreamInFlightGate();

  private static GateSubject.Upstream building(String qualifiedId, String runId) {
    return new GateSubject.Upstream(
        "id-" + qualifiedId,
        qualifiedId,
        "repo-" + qualifiedId,
        "QA run " + runId + " is RUNNING at abcdef1234",
        runId,
        true,
        true,
        null);
  }

  private static GateSubject.Upstream idle(String qualifiedId) {
    return new GateSubject.Upstream(
        "id-" + qualifiedId,
        qualifiedId,
        "repo-" + qualifiedId,
        "Pending, with no QA run going",
        null,
        false,
        true,
        null);
  }

  private static GateSubject.Upstream unreadable(String qualifiedId) {
    return new GateSubject.Upstream(
        "id-" + qualifiedId, qualifiedId, "repo-" + qualifiedId, "Ready to release x", null, true,
        false, null);
  }

  private static GateSubject subject(
      ReleaseRequest request,
      List<GateSubject.Upstream> upstream,
      boolean wrapper,
      List<eu.wohlben.qits.projects.dto.CommitBuildStatusDto> verdicts) {
    return new GateSubject(
        request,
        GateFixtures.configured(ReleaseGates.Kind.CI),
        GateFixtures.notRequired(),
        GateFixtures.noAutomations(),
        verdicts,
        null,
        GateSubject.Deployment.NOT_ASKED,
        GateSubject.Upstreams.of(wrapper, upstream));
  }

  private static GateSubject subject(List<GateSubject.Upstream> upstream) {
    return subject(GateFixtures.request(), upstream, false, List.of());
  }

  @Test
  void itStandsBetweenThePreRunAndQaAfterTheAutomations() {
    assertEquals("upstream-in-flight", gate.kind());
    assertEquals("Upstream releases", gate.label());
    assertEquals(ReleaseGatePosition.PRE_RUN_QA, gate.position());
    assertTrue(gate.order() > new AutomationsGate().order());
    assertTrue(gate.order() < new CiBuildGate().order());
  }

  @Test
  void anActiveUpstreamHoldsAndIsNamedOnACheck() {
    GateEvaluation answer =
        gate.evaluate(subject(List.of(building("fe-rr-12", "run-1"), idle("lib-rr-3"))));

    assertEquals(ReleaseGates.State.PENDING, answer.state());
    assertEquals("Waiting for upstream releases: fe-rr-12", answer.detail());
    assertEquals(1, answer.checks().size(), "one check per active upstream, none for an idle one");
    GateCheck check = answer.checks().get(0);
    assertEquals("fe-rr-12", check.name());
    assertEquals("PENDING", check.state());
    assertEquals("run-1", check.runId());
    assertTrue(UpstreamInFlightGate.holds(answer));
  }

  @Test
  void nothingActivePasses() {
    GateEvaluation answer = gate.evaluate(subject(List.of(idle("fe-rr-12"))));

    assertEquals(ReleaseGates.State.PASSED, answer.state());
    assertNull(answer.detail());
    assertTrue(answer.checks().isEmpty());
    assertFalse(UpstreamInFlightGate.holds(answer));
  }

  @Test
  void noUpstreamAtAllPasses() {
    assertEquals(ReleaseGates.State.PASSED, gate.evaluate(subject(List.of())).state());
  }

  @Test
  void anUnreadableClosureIsUnknownAndDoesNotHold() {
    GateEvaluation answer = gate.evaluate(subject(List.of(unreadable("fe-rr-12"))));

    assertEquals(ReleaseGates.State.UNKNOWN, answer.state());
    assertEquals("Could not read what is upstream", answer.detail());
    assertEquals("UNKNOWN", answer.checks().get(0).state());
    assertFalse(UpstreamInFlightGate.holds(answer), "UNKNOWN never holds");
  }

  @Test
  void aKnownActiveUpstreamOutweighsAnUnreadableOne() {
    GateEvaluation answer =
        gate.evaluate(subject(List.of(unreadable("lib-rr-1"), building("fe-rr-12", "run-1"))));

    assertEquals(ReleaseGates.State.PENDING, answer.state());
    assertEquals("Waiting for upstream releases: fe-rr-12", answer.detail());
    assertEquals(2, answer.checks().size());
  }

  @Test
  void anOverriddenUpstreamPassesAndSaysWho() {
    GateSubject.Upstream overridden =
        new GateSubject.Upstream(
            "id-fe",
            "fe-rr-12",
            "repo-fe",
            "Ready to release x",
            null,
            true,
            true,
            new GateSubject.UpstreamOverride("wohlben", Instant.parse("2026-10-23T10:00:00Z"), null));

    GateEvaluation answer = gate.evaluate(subject(List.of(overridden)));

    assertEquals(ReleaseGates.State.PASSED, answer.state());
    assertEquals("Overridden: fe-rr-12 (wohlben)", answer.detail());
    assertEquals("PASSED", answer.checks().get(0).state());
    assertEquals("Overridden by wohlben; Ready to release x", answer.checks().get(0).detail());
  }

  @Test
  void itAppliesToAPendingFoldWithNoVerdict() {
    assertEquals(GateApplicability.APPLIES, gate.applicability(subject(List.of())));
  }

  @Test
  void aFoldWithAQaVerdictOfItsOwnIsNotAsked() {
    GateSubject withVerdict =
        subject(
            GateFixtures.request(),
            List.of(building("fe-rr-12", "run-1")),
            false,
            List.of(GateFixtures.green("run-own")));

    assertEquals(GateApplicability.DOES_NOT_APPLY, gate.applicability(withVerdict));
  }

  @Test
  void anotherRequestsVerdictOnTheSameCommitIsNoVerdict() {
    GateSubject withForeign =
        subject(
            GateFixtures.request(),
            List.of(),
            false,
            List.of(
                GateFixtures.verdict("run-main", "SUCCESS", "main", GateFixtures.FINISHED)));

    assertEquals(GateApplicability.APPLIES, gate.applicability(withForeign));
  }

  @Test
  void theEstateWrapperIsExempt() {
    GateSubject wrapper =
        subject(GateFixtures.request(), List.of(building("fe-rr-12", "run-1")), true, List.of());

    assertEquals(GateApplicability.DOES_NOT_APPLY, gate.applicability(wrapper));
  }

  @Test
  void onlyAPendingRequestWithAFoldIsAsked() {
    ReleaseRequest ready = GateFixtures.request();
    ready.state = ReleaseRequest.State.READY;
    ReleaseRequest unfolded = GateFixtures.request();
    unfolded.mergedSha = null;

    assertEquals(
        GateApplicability.DOES_NOT_APPLY,
        gate.applicability(subject(ready, List.of(), false, List.of())));
    assertEquals(
        GateApplicability.DOES_NOT_APPLY,
        gate.applicability(subject(unfolded, List.of(), false, List.of())));
  }

  @Test
  void aSubjectWithNoUpstreamFactsLeavesTheGateOut() {
    assertEquals(
        GateApplicability.DOES_NOT_APPLY,
        gate.applicability(GateFixtures.subject(GateFixtures.configured(ReleaseGates.Kind.CI))));
  }

  // An interrupted fold (qits-1872)

  private static ReleaseRequest interruptedBy(String recorded) {
    ReleaseRequest row = GateFixtures.request();
    row.qaInterruptedSha = row.mergedSha;
    row.qaAnnouncedSha = null;
    row.qaInterruptedBy = recorded;
    return row;
  }

  @Test
  void anInterruptedFoldIsHeldOnlyByTheUpstreamsThatInterruptedIt() {
    GateEvaluation answer =
        gate.evaluate(
            subject(
                interruptedBy("id-fe-rr-12"),
                List.of(building("fe-rr-12", "run-1"), building("lib-rr-3", "run-2")),
                false,
                List.of()));

    assertEquals(ReleaseGates.State.PENDING, answer.state());
    assertEquals("Waiting for upstream releases: fe-rr-12", answer.detail());
    assertEquals(
        List.of("PENDING", "PASSED"), answer.checks().stream().map(GateCheck::state).toList());
    assertTrue(answer.checks().get(1).detail().startsWith("Did not interrupt this fold's QA"));
  }

  @Test
  void anInterruptedFoldWhoseInterruptersAreIdlePassesWhileOthersBuild() {
    GateEvaluation answer =
        gate.evaluate(
            subject(
                interruptedBy("id-fe-rr-12"),
                List.of(idle("fe-rr-12"), building("lib-rr-3", "run-2")),
                false,
                List.of()));

    assertEquals(ReleaseGates.State.PASSED, answer.state());
    assertFalse(UpstreamInFlightGate.holds(answer));
  }

  @Test
  void anInterruptedFoldWithNoRecordedInterruptersIsNotHeld() {
    for (String legacy : new String[] {null, ""}) {
      GateEvaluation answer =
          gate.evaluate(
              subject(
                  interruptedBy(legacy), List.of(building("fe-rr-12", "run-1")), false, List.of()));
      assertFalse(UpstreamInFlightGate.holds(answer), "recorded: " + legacy);
    }
  }

  @Test
  void anAnnouncedOrNewFoldIsHeldByEveryActiveUpstream() {
    ReleaseRequest announced = interruptedBy("id-fe-rr-12");
    announced.qaAnnouncedSha = announced.mergedSha;
    ReleaseRequest refolded = interruptedBy("id-fe-rr-12");
    refolded.mergedSha = "a-newer-fold";
    List<GateSubject.Upstream> both =
        List.of(building("fe-rr-12", "run-1"), building("lib-rr-3", "run-2"));

    for (ReleaseRequest row : List.of(announced, refolded)) {
      assertEquals(
          "Waiting for upstream releases: fe-rr-12, lib-rr-3",
          gate.evaluate(subject(row, both, false, List.of())).detail());
    }
  }

  @Test
  void theHoldingIdsAreTheActiveKnownNotOverriddenOnes() {
    GateSubject.Upstream overridden =
        new GateSubject.Upstream(
            "id-ov",
            "ov-rr-1",
            "repo-ov",
            "Ready",
            null,
            true,
            true,
            new GateSubject.UpstreamOverride("wohlben", Instant.parse("2026-10-23T10:00:00Z"), null));

    assertEquals(
        List.of("id-fe-rr-12"),
        UpstreamInFlightGate.holdingIds(
            List.of(
                building("fe-rr-12", "run-1"),
                idle("lib-rr-3"),
                unreadable("x-rr-1"),
                overridden)));
  }
}
