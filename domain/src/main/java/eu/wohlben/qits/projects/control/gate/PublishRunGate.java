package eu.wohlben.qits.projects.control.gate;

import eu.wohlben.qits.projects.control.ReleaseGates;
import eu.wohlben.qits.projects.entity.ReleasedTagPendingMerge;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The released tag's own release run (the publish run) is green. Configured by the released tag's
 * tree, not by {@code main}, so it applies only once a release recorded a publish state. See {@link
 * ReleaseGates}' "Configured at the tag".
 */
@ApplicationScoped
public class PublishRunGate implements ReleaseGate {

  public static final String KIND = "publish";

  @Override
  public String kind() {
    return KIND;
  }

  @Override
  public String label() {
    return "Publish run";
  }

  @Override
  public ReleaseGatePosition position() {
    return ReleaseGatePosition.PUBLISH_DEPLOY;
  }

  @Override
  public int order() {
    return 400;
  }

  @Override
  public GateApplicability applicability(GateSubject subject) {
    if (!subject.gateSet().known()) {
      return GateApplicability.UNREADABLE;
    }
    ReleasedTagPendingMerge released = subject.released();
    return released != null && released.publishState != null
        ? GateApplicability.APPLIES
        : GateApplicability.DOES_NOT_APPLY;
  }

  @Override
  public GateEvaluation evaluate(GateSubject subject) {
    ReleasedTagPendingMerge released = subject.released();
    ReleaseGates.State state =
        switch (released.publishState) {
          case PASSED -> ReleaseGates.State.PASSED;
          case FAILED -> ReleaseGates.State.FAILED;
          case PENDING -> ReleaseGates.State.PENDING;
        };
    String detail =
        released.publishDetail == null || released.publishDetail.isBlank()
            ? null
            : released.publishDetail;
    return new GateEvaluation(state, detail, null, released.publishRunId, null);
  }
}
