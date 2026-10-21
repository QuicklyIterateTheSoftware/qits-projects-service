package eu.wohlben.qits.projects.control.approval;

import java.util.List;
import java.util.Objects;

/**
 * <b>One thing a release request's fold needs approved, and who may approve it</b> (epic
 * qits-1038). A request has a list of these, gathered by {@code ApprovalPolicy} from every {@link
 * ApprovalRequirementSource}; an empty list is a request nobody has to approve.
 *
 * <p>A requirement is <b>satisfied</b> when some approver's newest decision at the request's
 * current fold is an approval, that approver held a role the requirement {@link #admits}, and — where
 * {@link #excludesAuthors} — is not one of the request's authors. One approval satisfies every
 * requirement it qualifies for.
 *
 * @param id stable per source: {@code manual-review}, {@code config-directory}, {@code
 *     artifact-declarations}, {@code no-changes}, {@code recorded}, and later {@code
 *     automation:<kind>} and {@code contract:<consumer project slug>}
 * @param reason the sentence the page and the gate detail print
 * @param admits the roles that may satisfy it — never empty
 * @param excludesAuthors whether the request's own authors (the principals that added its branch
 *     sources) are refused
 * @param detail what it is about — the paths, joined with {@code ", "}, or the sentence a fail-closed
 *     read gives; may be null
 */
public record ApprovalRequirement(
    String id, String reason, List<ApprovalRole> admits, boolean excludesAuthors, String detail) {

  /** The paths a requirement's {@link #detail} lists are joined with this. */
  public static final String PATH_SEPARATOR = ", ";

  public ApprovalRequirement {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(reason, "reason");
    if (admits == null || admits.isEmpty()) {
      throw new IllegalArgumentException("requirement " + id + " admits nobody");
    }
    admits = List.copyOf(admits);
  }

  /** A requirement only a person may satisfy, which every requirement before epic qits-1038 was. */
  public static ApprovalRequirement person(String id, String reason, String detail) {
    return new ApprovalRequirement(id, reason, List.of(ApprovalRole.PERSON), false, detail);
  }

  /**
   * The sentence the answer printed before requirements were a list: the reason, then the detail
   * after a colon where there is one — {@code changes .config/qits/: <paths>}, {@code configured by
   * manual-review}. The deprecated single {@code detail} of the approval gate is these, joined.
   */
  public String sentence() {
    return detail == null || detail.isBlank() ? reason : reason + ": " + detail;
  }

  /** Whether only a person may satisfy it. */
  public boolean personOnly() {
    return admits.stream().allMatch(role -> role.kind() == ApprovalRole.Kind.PERSON);
  }
}
