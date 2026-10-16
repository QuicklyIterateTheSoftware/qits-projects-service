package eu.wohlben.qits.projects.control.gate;

import eu.wohlben.qits.projects.control.ReleaseGates;
import eu.wohlben.qits.projects.dto.CommitBuildStatusDto;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

/**
 * The request's own newest CI run is green. Configured by {@code .config/qits/release.yml} at
 * {@code main} declaring a QA pipeline (see {@link ReleaseGates}).
 *
 * <p><b>Own and newest</b> (qits-1266) — both are {@link CiVerdicts}',
 * the reading the sweep decides on too, so this display and that decision answer from one rule:
 *
 * <ul>
 *   <li>only the request's own runs count — its QA runs on its backing branch and its tag build on
 *       its released version. A {@code main} build or another request's run on the same commit is
 *       neither listed nor counted, red or green;
 *   <li>the newest of them answers, on an open and a concluded request alike: red is FAILED (and,
 *       before the tag, has rejected the request), green is PASSED, none is PENDING. An older
 *       run's word is still listed as a check, marked superseded.
 * </ul>
 *
 * <p>The checks list only the request's own runs; every run of the commit, red ones included, is
 * still listed where runs are listed (the commit's build status and the Builds tab), which this
 * class does not touch.
 *
 * <p>Before qits-1266 this read every verdict of the commit with "any red wins", and so showed
 * {@code qits-qits-rr-143} FAILED — FINALIZED, its own runs green — on another request's
 * automation run on {@code main}. The active-run probe is still not asked: it narrows the gate in
 * the sweep, and a read must not make a call per row.
 */
@ApplicationScoped
public class CiBuildGate implements ReleaseGate {

  public static final String KIND = "ci";

  @Override
  public String kind() {
    return KIND;
  }

  @Override
  public String label() {
    return "CI build";
  }

  @Override
  public ReleaseGatePosition position() {
    return ReleaseGatePosition.QA_PUBLISH;
  }

  @Override
  public int order() {
    return 100;
  }

  @Override
  public GateApplicability applicability(GateSubject subject) {
    if (!subject.gateSet().known()) {
      return GateApplicability.UNREADABLE;
    }
    return subject.gateSet().requires(ReleaseGates.Kind.CI)
        ? GateApplicability.APPLIES
        : GateApplicability.DOES_NOT_APPLY;
  }

  @Override
  public GateEvaluation evaluate(GateSubject subject) {
    List<CommitBuildStatusDto> own =
        CiVerdicts.own(subject.verdicts(), subject.request(), subject.released());
    CommitBuildStatusDto newest = own.isEmpty() ? null : own.get(0);
    List<GateCheck> checks =
        own.stream()
            .map(
                v ->
                    new GateCheck(
                        "Run " + v.runId(),
                        CiVerdicts.success(v) ? "PASSED" : "FAILED",
                        v.status()
                            + (v.branch() == null ? "" : " on " + v.branch())
                            + (v == newest ? "" : "; superseded by run " + newest.runId()),
                        v.runId()))
            .toList();
    if (newest != null && !CiVerdicts.success(newest)) {
      return new GateEvaluation(
          ReleaseGates.State.FAILED,
          "Run " + newest.runId() + " finished " + newest.status() + " for " + subject.shortFold(),
          checks,
          newest.runId(),
          null);
    }
    if (newest != null) {
      return new GateEvaluation(
          ReleaseGates.State.PASSED,
          "Run " + newest.runId() + " passed for " + subject.shortFold(),
          checks,
          newest.runId(),
          null);
    }
    return new GateEvaluation(
        ReleaseGates.State.PENDING,
        subject.request() == null || subject.request().mergedSha == null
            ? "Nothing is folded yet"
            : "Waiting for a CI verdict for " + subject.shortFold(),
        checks,
        null,
        null);
  }
}
