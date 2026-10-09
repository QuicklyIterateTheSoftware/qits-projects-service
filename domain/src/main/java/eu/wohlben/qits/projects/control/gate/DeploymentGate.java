package eu.wohlben.qits.projects.control.gate;

import eu.wohlben.qits.projects.control.ReleaseGates;
import eu.wohlben.qits.projects.entity.ReleasedTagPendingMerge;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The release is deployed and its tag reached {@code main}. Configured by {@code
 * .config/qits/deployments.yml} at {@code main}. PASSED once the tag merged, PENDING until then.
 */
@ApplicationScoped
public class DeploymentGate implements ReleaseGate {

  public static final String KIND = "deployment";

  @Override
  public String kind() {
    return KIND;
  }

  @Override
  public String label() {
    return "Deployment";
  }

  @Override
  public ReleaseGatePosition position() {
    return ReleaseGatePosition.DEPLOY_FINALIZED;
  }

  @Override
  public int order() {
    return 500;
  }

  @Override
  public GateApplicability applicability(GateSubject subject) {
    if (!subject.gateSet().known()) {
      return GateApplicability.UNREADABLE;
    }
    return subject.gateSet().requires(ReleaseGates.Kind.DEPLOYMENT)
        ? GateApplicability.APPLIES
        : GateApplicability.DOES_NOT_APPLY;
  }

  @Override
  public GateEvaluation evaluate(GateSubject subject) {
    ReleasedTagPendingMerge released = subject.released();
    if (released != null && released.mergedAt != null) {
      return GateEvaluation.of(ReleaseGates.State.PASSED, "Deployed; " + released.tagName + " reached main");
    }
    return GateEvaluation.of(
        ReleaseGates.State.PENDING,
        released == null || released.tagName == null
            ? "Nothing is released yet"
            : "Waiting for " + released.tagName + " to deploy and reach main");
  }
}
