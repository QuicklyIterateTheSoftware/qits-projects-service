package eu.wohlben.qits.projects.entitieshost;

import java.util.List;

/**
 * How a branch's newest commits fit the commit-message variants — {@link CommitSubjectCompliance}'s
 * answer, served by {@code GET /repositories/{repoId}/commit-subjects}.
 *
 * @param repositoryId the repository read
 * @param branch the branch read — the repository's main branch when none was asked for
 * @param limit how many commits were asked for; {@code counts.total} is fewer on a short history
 * @param counts how many commits fit each variant, and how many fit none
 * @param nonComplying every commit that fits no variant, newest first. Commits from before
 *     qits-githost refused them ordinarily land here; that is expected of an old history
 * @param qualifiedIds the distinct qualified ids the SUBJECT commits name, newest first. Syntax
 *     only: an id here is not checked to name an entity
 */
public record CommitSubjectsDto(
    String repositoryId,
    String branch,
    int limit,
    CommitSubjectCounts counts,
    List<NonComplyingCommit> nonComplying,
    List<String> qualifiedIds) {

  /**
   * One count per {@link CommitMessageVariants} variant, plus the commits that fit none.
   *
   * @param total commits read; {@code merge + bump + release + platform + subject + nonComplying}
   * @param merge commits with two or more parents, whatever their message
   * @param bump {@code bump(<group>): <n> dependencies}, qits-maintenance's bumps
   * @param release {@code release(<version>): …}, the release's version stamp
   * @param platform {@code chore(platform): …}, the platform's own housekeeping
   * @param subject {@code term(<project>-<n>): …}, a commit naming its work
   * @param nonComplying commits fitting no variant
   */
  public record CommitSubjectCounts(
      int total,
      int merge,
      int bump,
      int release,
      int platform,
      int subject,
      int nonComplying) {}

  /** A commit whose first line fits no commit-message variant. */
  public record NonComplyingCommit(
      String hash,
      String shortHash,
      String subject,
      String authorName,
      String authorEmail,
      String date) {}
}
