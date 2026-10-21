package eu.wohlben.qits.projects.control.approval;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

/**
 * <b>The fold adds nothing to {@code main}</b> (qits-760). A request whose branches carry nothing
 * folds, by fast-forward, to {@code main}'s own head: a commit the last release already tagged and
 * built, so the CI gate is vouched at once by that old run and the request could release before its
 * own QA run exists. Read as {@code git diff main...mergedSha} being empty ({@link
 * ApprovalSubject#addsNothingToMain()}). A person decides whether an empty release is wanted. A
 * {@code maintenance/*} bump is never held by it: its request is opened only for a branch ahead of
 * {@code main}.
 */
@ApplicationScoped
public class NoChangesSource implements ApprovalRequirementSource {

  public static final String ID = "no-changes";

  /** The sentence, unchanged since qits-760. */
  public static final String REASON = "no changes against main: the fold adds nothing to main";

  @Override
  public List<ApprovalRequirement> requirementsFor(ApprovalSubject subject) {
    return subject.addsNothingToMain()
        ? List.of(ApprovalRequirement.person(ID, REASON, null))
        : List.of();
  }

  @Override
  public int order() {
    return 400;
  }
}
