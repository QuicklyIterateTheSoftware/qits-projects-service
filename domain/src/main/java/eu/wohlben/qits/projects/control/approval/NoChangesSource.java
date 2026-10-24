package eu.wohlben.qits.projects.control.approval;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

/**
 * <b>The fold adds nothing to {@code main}</b> (qits-760). A request whose branches carry nothing
 * folds, by fast-forward, to {@code main}'s own head: a commit the last release already tagged and
 * built, so the CI gate is vouched at once by that old run and the request could release before its
 * own QA run exists. Read as {@code git diff main...mergedSha} being empty ({@link
 * ApprovalSubject#addsNothingToMain()}). A person decides whether an empty release is wanted. A
 * {@code maintenance/*} bump is held by it only transiently: qits-maintenance opens a main-only
 * request ({@code BumpDispatcher.openMainOnly}) whose fold is main's head until the dependency-bump
 * pre-run commits its branch, and the refold then drops it. A project may admit its delivery desk
 * to it through {@code front_desk.delivery.approves} (qits-1568) — a wrapper request for a VERIFIED
 * item whose work lives only in submodules adds nothing — and the desk's rule then still refuses a
 * request naming no work item.
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
