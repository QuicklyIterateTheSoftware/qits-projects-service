package eu.wohlben.qits.projects.control.gate;

import eu.wohlben.qits.projects.control.ReleaseGates;
import eu.wohlben.qits.projects.dto.CommitBuildStatusDto;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

/**
 * The QA build passed for the current fold. Configured by {@code .config/qits/release.yml} at
 * {@code main} declaring a QA pipeline (see {@link ReleaseGates}).
 *
 * <p>Any red verdict is FAILED (and has already rejected the request), else any green one is
 * PASSED, else PENDING. The active-run probe is not asked: it narrows the gate in the sweep, and a
 * read must not make a call per row.
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
    List<CommitBuildStatusDto> verdicts = subject.verdicts();
    List<GateCheck> checks =
        verdicts.stream()
            .map(
                v ->
                    new GateCheck(
                        "Run " + v.runId(),
                        success(v) ? "PASSED" : "FAILED",
                        v.status() + (v.branch() == null ? "" : " on " + v.branch()),
                        v.runId()))
            .toList();
    CommitBuildStatusDto red = verdicts.stream().filter(v -> !success(v)).findFirst().orElse(null);
    if (red != null) {
      return new GateEvaluation(
          ReleaseGates.State.FAILED,
          "Run " + red.runId() + " finished " + red.status() + " for " + subject.shortFold(),
          checks,
          red.runId(),
          null);
    }
    CommitBuildStatusDto green = verdicts.stream().filter(CiBuildGate::success).findFirst().orElse(null);
    if (green != null) {
      return new GateEvaluation(
          ReleaseGates.State.PASSED,
          "Run " + green.runId() + " passed for " + subject.shortFold(),
          checks,
          green.runId(),
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

  private static boolean success(CommitBuildStatusDto verdict) {
    return "SUCCESS".equals(verdict.status());
  }
}
