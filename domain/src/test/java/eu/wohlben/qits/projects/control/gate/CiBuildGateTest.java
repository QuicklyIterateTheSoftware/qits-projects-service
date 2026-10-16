package eu.wohlben.qits.projects.control.gate;

import static eu.wohlben.qits.projects.control.gate.GateFixtures.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.control.ReleaseGates;
import eu.wohlben.qits.projects.dto.CommitBuildStatusDto;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.entity.ReleasedTagPendingMerge;
import eu.wohlben.qits.projects.entity.ReleasedTagPendingMerge.PublishState;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class CiBuildGateTest {

  private static final ReleaseGates.GateSet CI = configured(ReleaseGates.Kind.CI);

  private final CiBuildGate gate = new CiBuildGate();

  private GateEvaluation evaluate(List<CommitBuildStatusDto> verdicts) {
    return gate.evaluate(subject(CI, notRequired(), noAutomations(), verdicts, null));
  }

  private static Instant at(String time) {
    return Instant.parse("2026-10-09T" + time + "Z");
  }

  @Test
  void appliesOnlyWhereMainConfiguresIt() {
    assertEquals(GateApplicability.APPLIES, gate.applicability(subject(CI)));
    assertEquals(GateApplicability.DOES_NOT_APPLY, gate.applicability(subject(configured())));
    assertEquals(GateApplicability.UNREADABLE, gate.applicability(subject(unreadable())));
  }

  @Test
  void noVerdictIsPending() {
    GateEvaluation answer = gate.evaluate(subject(CI));
    assertEquals(ReleaseGates.State.PENDING, answer.state());
    assertEquals("Waiting for a CI verdict for 0123456789", answer.detail());
    assertNull(answer.runId());
  }

  @Test
  void aGreenVerdictPasses() {
    GateEvaluation answer = evaluate(List.of(green("g1")));
    assertEquals(ReleaseGates.State.PASSED, answer.state());
    assertEquals("g1", answer.runId());
    assertEquals(1, answer.checks().size());
    assertEquals("PASSED", answer.checks().get(0).state());
  }

  @Test
  void aNewerRedFailsOverAnOlderGreen() {
    GateEvaluation answer = evaluate(List.of(green("g1", at("08:00:00")), red("r1", at("09:00:00"))));
    assertEquals(ReleaseGates.State.FAILED, answer.state());
    assertEquals("r1", answer.runId());
    assertEquals("Run r1 finished FAILED for 0123456789", answer.detail());
    // Newest first, and the older word is kept as a check, marked as answered.
    assertEquals(List.of("FAILED", "PASSED"), answer.checks().stream().map(GateCheck::state).toList());
    assertTrue(answer.checks().get(1).detail().endsWith("superseded by run r1"), answer.checks().toString());
  }

  /** The heart of qits-1266's second fault: a later green is an answer, not a second opinion. */
  @Test
  void aNewerGreenPassesOverAnOlderRed() {
    GateEvaluation answer = evaluate(List.of(red("r1", at("08:00:00")), green("g1", at("09:00:00"))));
    assertEquals(ReleaseGates.State.PASSED, answer.state());
    assertEquals("g1", answer.runId());
    assertEquals("Run g1 passed for 0123456789", answer.detail());
  }

  /** The order the list arrives in does not matter: the gate sorts by when each run finished. */
  @Test
  void theNewestIsFoundWhateverOrderTheVerdictsArriveIn() {
    CommitBuildStatusDto older = red("r1", at("08:00:00"));
    CommitBuildStatusDto newer = green("g1", at("09:00:00"));
    assertEquals("g1", evaluate(List.of(older, newer)).runId());
    assertEquals("g1", evaluate(List.of(newer, older)).runId());
  }

  /**
   * The rr-143 shape: the request's own QA run is green, and a red run on {@code main} at the same
   * commit (another request's automation) and another request's QA run are no part of its gate —
   * neither counted nor listed, however new.
   */
  @Test
  void foreignRunsOnTheSameCommitAreIgnored() {
    GateEvaluation answer =
        evaluate(
            List.of(
                verdict("9e73d843", "FAILED", "main", at("10:47:00")),
                verdict("98e33cf1", "FAILED", "release/repo-1-rr-2", at("10:40:00")),
                verdict("old-tag", "FAILED", "2026.1001.100000", at("10:30:00")),
                verdict("no-branch", "FAILED", null, at("10:20:00")),
                green("5f3d9844", at("08:00:00"))));
    assertEquals(ReleaseGates.State.PASSED, answer.state());
    assertEquals("5f3d9844", answer.runId());
    assertEquals(List.of("Run 5f3d9844"), answer.checks().stream().map(GateCheck::name).toList());
  }

  @Test
  void aForeignGreenVouchesForNothing() {
    GateEvaluation answer = evaluate(List.of(verdict("m1", "SUCCESS", "main", at("08:00:00"))));
    assertEquals(ReleaseGates.State.PENDING, answer.state());
    assertTrue(answer.checks().isEmpty());
  }

  /** A request made before V41 builds {@code release/<uuid>}; a ref-shaped branch is the same. */
  @Test
  void theLegacyBackingBranchAndAFullRefAreOwn() {
    assertEquals(
        ReleaseGates.State.FAILED,
        evaluate(List.of(verdict("r1", "FAILED", "release/rr-1", at("08:00:00")))).state());
    assertEquals(
        ReleaseGates.State.PASSED,
        evaluate(List.of(verdict("g1", "SUCCESS", "refs/heads/" + BACKING, at("08:00:00"))))
            .state());
  }

  /** The tag build is the request's own once it has released, and it is the newest word then. */
  @Test
  void theOwnTagBuildCounts() {
    ReleaseRequest released = request();
    released.state = ReleaseRequest.State.RELEASED;
    ReleasedTagPendingMerge tag = released(PublishState.PENDING, false);
    GateEvaluation answer =
        gate.evaluate(
            subject(
                released,
                CI,
                List.of(
                    green("qa", at("09:00:00")),
                    verdict("tag-build", "FAILED", TAG, at("10:10:00"))),
                tag,
                GateSubject.Deployment.NOT_ASKED));
    assertEquals(ReleaseGates.State.FAILED, answer.state());
    assertEquals("tag-build", answer.runId());

    GateEvaluation retried =
        gate.evaluate(
            subject(
                released,
                CI,
                List.of(
                    green("qa", at("09:00:00")),
                    verdict("tag-build", "FAILED", TAG, at("10:10:00")),
                    verdict("tag-retry", "SUCCESS", "refs/tags/" + TAG, at("10:20:00"))),
                tag,
                GateSubject.Deployment.NOT_ASKED));
    assertEquals(ReleaseGates.State.PASSED, retried.state());
    assertEquals("tag-retry", retried.runId());
  }

  /** Before the tag, a run on a version-shaped branch is another release's, never this one's. */
  @Test
  void noTagBuildIsOwnBeforeTheRelease() {
    GateEvaluation answer =
        evaluate(List.of(green("qa", at("09:00:00")), verdict("t", "FAILED", TAG, at("10:00:00"))));
    assertEquals(ReleaseGates.State.PASSED, answer.state());
  }

  /**
   * A concluded request reads its latest result exactly as an open one does — nothing is frozen
   * (qits-1266's thread). rr-143's shape: FINALIZED, its QA run and tag build green, a red on
   * {@code main} and another request's QA run at the same commit afterwards: PASSED on its tag
   * build. And should a run of its own come later, that is the newest word, red or green.
   */
  @Test
  void aConcludedRequestShowsItsLatestOwnResult() {
    ReleaseRequest finalized = request();
    finalized.state = ReleaseRequest.State.FINALIZED;
    ReleasedTagPendingMerge tag = released(PublishState.PASSED, true);
    List<CommitBuildStatusDto> rr143 =
        List.of(
            verdict("9e73d843", "FAILED", "main", at("12:47:00")),
            verdict("400174f1", "SUCCESS", "main", at("12:56:00")),
            verdict("98e33cf1", "SUCCESS", "release/repo-1-rr-2", at("12:30:00")),
            verdict("88c177db", "SUCCESS", TAG, at("10:20:00")),
            green("5f3d9844", at("09:00:00")));
    GateEvaluation answer =
        gate.evaluate(subject(finalized, CI, rr143, tag, GateSubject.Deployment.NOT_ASKED));
    assertEquals(ReleaseGates.State.PASSED, answer.state());
    assertEquals("88c177db", answer.runId());
    assertEquals(
        List.of("Run 88c177db", "Run 5f3d9844"),
        answer.checks().stream().map(GateCheck::name).toList());

    List<CommitBuildStatusDto> laterRed = new java.util.ArrayList<>(rr143);
    laterRed.add(verdict("rerun", "FAILED", TAG, at("13:00:00")));
    assertEquals(
        ReleaseGates.State.FAILED,
        gate.evaluate(subject(finalized, CI, laterRed, tag, GateSubject.Deployment.NOT_ASKED))
            .state());
  }

  /** RELEASED is open: its publish run is still moving and the gate must keep following it. */
  @Test
  void aReleasedRequestIsStillOpen() {
    ReleaseRequest released = request();
    released.state = ReleaseRequest.State.RELEASED;
    ReleasedTagPendingMerge tag = released(PublishState.PENDING, false);
    GateEvaluation answer =
        gate.evaluate(
            subject(
                released,
                CI,
                List.of(
                    green("qa", at("09:00:00")),
                    verdict("tag-build", "SUCCESS", TAG, at("15:00:00"))),
                tag,
                GateSubject.Deployment.NOT_ASKED));
    assertEquals("tag-build", answer.runId());
  }
}
