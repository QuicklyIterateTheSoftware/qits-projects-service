package eu.wohlben.qits.projects.control.approval;

import eu.wohlben.qits.projects.control.ReleaseGates;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

/**
 * {@code manual-review: true} in the repository's {@code .config/qits/release-requests.yml}, read at
 * its {@code main} — never at the fold under review, so a change cannot loosen the rule it is judged
 * by. A person only. The one configuration source: it reads no git and is asked on settled requests
 * too.
 */
@ApplicationScoped
public class ManualReviewSource implements ApprovalRequirementSource {

  public static final String ID = "manual-review";

  /** The sentence, unchanged since the approval question was one boolean. */
  public static final String REASON = "configured by manual-review";

  @Override
  public List<ApprovalRequirement> requirementsFor(ApprovalSubject subject) {
    return subject.gateSet().requires(ReleaseGates.Kind.APPROVAL)
        ? List.of(ApprovalRequirement.person(ID, REASON, null))
        : List.of();
  }

  @Override
  public int order() {
    return 100;
  }

  @Override
  public boolean readsFold() {
    return false;
  }
}
