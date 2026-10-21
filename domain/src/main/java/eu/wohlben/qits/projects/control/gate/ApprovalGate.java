package eu.wohlben.qits.projects.control.gate;

import eu.wohlben.qits.projects.control.ReleaseGates;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Every approval requirement of the current fold is satisfied (epic qits-1038). The requirements
 * come from {@code ApprovalPolicy} — {@code manual-review} at {@code main}, or the fold's own content
 * — and each names the approval roles that may satisfy it. The approve and decline doors write the
 * decisions; this class reads where they stand.
 *
 * <p>APPROVED is PASSED, DECLINED (an approver who qualified for a requirement declined) is FAILED,
 * anything else PENDING with each open requirement and the roles it admits as the detail.
 */
@ApplicationScoped
public class ApprovalGate implements ReleaseGate {

  public static final String KIND = "approval";

  @Override
  public String kind() {
    return KIND;
  }

  @Override
  public String label() {
    return "Approval";
  }

  @Override
  public ReleaseGatePosition position() {
    return ReleaseGatePosition.QA_PUBLISH;
  }

  @Override
  public int order() {
    return 300;
  }

  @Override
  public GateApplicability applicability(GateSubject subject) {
    if (!subject.gateSet().known()) {
      return GateApplicability.UNREADABLE;
    }
    boolean required = subject.approval() != null && subject.approval().required();
    return subject.gateSet().requires(ReleaseGates.Kind.APPROVAL) || required
        ? GateApplicability.APPLIES
        : GateApplicability.DOES_NOT_APPLY;
  }

  @Override
  public GateEvaluation evaluate(GateSubject subject) {
    GateSubject.Approval approval = subject.approval();
    if (approval == null || approval.state() == null) {
      return GateEvaluation.of(ReleaseGates.State.PENDING, null);
    }
    return switch (approval.state()) {
      case APPROVED ->
          GateEvaluation.of(ReleaseGates.State.PASSED, "Approved by " + approval.actor());
      case DECLINED ->
          GateEvaluation.of(
              ReleaseGates.State.FAILED,
              "Declined by "
                  + approval.actor()
                  + (approval.note() == null || approval.note().isBlank()
                      ? ""
                      : ": " + approval.note().trim()));
      default -> GateEvaluation.of(ReleaseGates.State.PENDING, approval.reason());
    };
  }
}
