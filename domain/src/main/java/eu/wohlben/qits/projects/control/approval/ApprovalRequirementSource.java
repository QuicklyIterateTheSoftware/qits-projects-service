package eu.wohlben.qits.projects.control.approval;

import java.util.List;

/**
 * <b>One reason a release request's fold may need approving</b> (epic qits-1038). Every
 * implementation is a CDI bean; {@code ApprovalPolicy} finds them all through {@code
 * Instance<ApprovalRequirementSource>}, asks them in {@link #order()} and answers the list. <b>A new
 * kind of requirement is one bean</b>: no gate, door, DTO or event changes — they all read the list.
 *
 * <p>A source answers what holds <em>now</em>, at the subject's fold, and remembers nothing: a
 * re-fold that drops the cause drops the requirement, and an approval counts only at the sha it
 * judged.
 *
 * <p><b>Two kinds of source</b>, told apart by {@link #readsFold()}:
 *
 * <ul>
 *   <li>a <b>configuration</b> source reads only the repository's gate set ({@link
 *       ApprovalSubject#gateSet()}) — {@code manual-review}. It is asked on every request, settled
 *       ones included, and on every display read.
 *   <li>a <b>content</b> source reads the fold ({@link ApprovalSubject#changes()} and its siblings).
 *       It is asked only for a request still before its tag, and its answers are what {@code
 *       ApprovalContentRecorder} stores for the list reads. A read it cannot make throws {@link
 *       FoldUnreadable} — never an empty answer — and the policy fails closed into a person.
 * </ul>
 */
public interface ApprovalRequirementSource {

  /**
   * What this source requires of the subject's fold; empty where nothing. A content source is only
   * asked where {@link ApprovalSubject#foldReadable()}.
   *
   * @throws FoldUnreadable when a read it needs could not be made
   */
  List<ApprovalRequirement> requirementsFor(ApprovalSubject subject);

  /** Where in the list its requirements stand; smaller first. Unique per source. */
  int order();

  /** Whether it reads the fold's content (a content source) or only configuration. */
  default boolean readsFold() {
    return true;
  }
}
