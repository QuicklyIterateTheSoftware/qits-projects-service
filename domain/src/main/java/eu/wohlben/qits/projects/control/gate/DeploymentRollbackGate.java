package eu.wohlben.qits.projects.control.gate;

import eu.wohlben.qits.projects.control.ReleaseGates;
import eu.wohlben.qits.projects.entity.ReleasedTagPendingMerge;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

/**
 * The released version went live and was not rolled back. Configured by {@code
 * .config/qits/deployments.yml} at {@code main}.
 *
 * <p><b>The tag reaching {@code main} is not this gate.</b> That is the finalize step after the
 * gates, answered by {@code mergedToMainAt}. The deprecated {@code DEPLOYMENT} entry of {@code
 * gates[]} still means "reached main" and is kept by {@link LegacyGates}, not by a class.
 *
 * <p>"Went live" is this service's own stamp, {@code deploymentActiveAt}. "Rolled back" is
 * qits-deployments' word {@code ROLLED_BACK} on the newest deployment request for the version,
 * which only a single-request read asks for. A list read reports a live release PASSED and says
 * it did not re-check.
 */
@ApplicationScoped
public class DeploymentRollbackGate implements ReleaseGate {

  public static final String KIND = "deployment-not-rolled-back";

  /** qits-deployments' word for a deployment that was rolled back. */
  static final String ROLLED_BACK = "ROLLED_BACK";

  @Override
  public String kind() {
    return KIND;
  }

  @Override
  public String label() {
    return "Deployment not rolled back";
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
    if (released == null || released.tagName == null || released.tagName.isBlank()) {
      return GateEvaluation.of(ReleaseGates.State.PENDING, "Nothing is released yet");
    }
    String version = released.tagName;
    GateSubject.Deployment deployment = subject.deployment();
    boolean wentLive = released.deploymentActiveAt != null;
    if (deployment.asked() && !deployment.answered()) {
      return GateEvaluation.of(
          ReleaseGates.State.UNKNOWN, "qits-deployments could not be asked about " + version);
    }
    ReleaseGates.State state;
    String detail;
    if (ROLLED_BACK.equals(deployment.status())) {
      state = ReleaseGates.State.FAILED;
      detail = version + " was rolled back";
    } else if (wentLive) {
      state = ReleaseGates.State.PASSED;
      detail =
          deployment.asked()
              ? version + " went live and is not rolled back"
              : version + " went live; a list read does not check for a rollback";
    } else {
      state = ReleaseGates.State.PENDING;
      detail = "Waiting for " + version + " to go live";
    }
    List<GateCheck> checks =
        deployment.requestId() == null
            ? List.of()
            : List.of(
                new GateCheck(
                    "Deployment request " + deployment.requestId(),
                    state.name(),
                    deployment.status() == null ? "No deployment yet" : deployment.status(),
                    null));
    return new GateEvaluation(state, detail, checks, null, null);
  }
}
