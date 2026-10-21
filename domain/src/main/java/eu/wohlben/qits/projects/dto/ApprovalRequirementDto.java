package eu.wohlben.qits.projects.dto;

import java.time.Instant;
import java.util.List;

/**
 * One approval requirement of a release request's current fold, as the API answers it (epic
 * qits-1038) — an entry of {@link ReleaseRequestDto#approvalRequirements()}.
 *
 * @param id stable per source: {@code manual-review}, {@code config-directory}, {@code
 *     artifact-declarations}, {@code no-changes}, {@code recorded} (a settled request's recorded
 *     approval), and later {@code automation:<kind>} and {@code contract:<consumer project slug>}
 * @param reason the sentence to print
 * @param admits the approval roles that may satisfy it, sorted: {@code person}, {@code agent} (an
 *     agent of the request's own project), {@code agent@<project slug>}
 * @param excludesAuthors whether the request's own authors may not satisfy it
 * @param detail the paths it is about, joined with {@code ", "}, or a fail-closed sentence; null
 *     where there is nothing to add
 * @param satisfied whether an approval at the current fold satisfies it
 * @param satisfiedBy that approval, or null while it is open
 */
public record ApprovalRequirementDto(
    String id,
    String reason,
    List<String> admits,
    boolean excludesAuthors,
    String detail,
    boolean satisfied,
    SatisfiedBy satisfiedBy) {

  /**
   * @param approver who approved — a person's verified name, an agent's {@code sub}
   * @param roles the roles they held, sorted
   * @param at when
   */
  public record SatisfiedBy(String approver, List<String> roles, Instant at) {}
}
