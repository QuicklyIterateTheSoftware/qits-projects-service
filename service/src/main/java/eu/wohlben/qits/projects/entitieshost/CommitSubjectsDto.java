package eu.wohlben.qits.projects.entitieshost;

import java.util.List;

/**
 * The commit-subject compliance of a branch's newest commits — {@link CommitSubjectCompliance}'s
 * answer, served by {@code GET /repositories/{repoId}/commit-subjects} and the {@code
 * measureCommitSubjects} MCP tool.
 *
 * @param repositoryId the repository read
 * @param branch the branch read — the repository's main branch when none was asked for
 * @param limit how many commits were asked for; {@code counts.total} is fewer on a short history
 * @param guardEnabled whether {@code .config/qits/commit-subjects.yml} on that branch holds {@code
 *     enforce: true} — the file qits-githost's receive guard opts a repository in by. Absent is false
 * @param counts how many commits fell in each class
 * @param nonComplying every NON_COMPLYING commit, newest first
 * @param qualifiedIds the distinct qualified ids the complying subjects name, newest first. Syntax
 *     only: an id here is not checked to name an entity
 */
public record CommitSubjectsDto(
    String repositoryId,
    String branch,
    int limit,
    boolean guardEnabled,
    CommitSubjectCounts counts,
    List<NonComplyingCommit> nonComplying,
    List<String> qualifiedIds) {

  /**
   * @param total commits read; {@code complying + nonComplying + exempt}
   * @param exempt {@code exemptMerge + exemptMachine}
   * @param exemptMerge commits with more than one parent
   * @param exemptMachine commits authored by a configured machine identity
   */
  public record CommitSubjectCounts(
      int total,
      int complying,
      int nonComplying,
      int exempt,
      int exemptMerge,
      int exemptMachine) {}

  /** A commit whose subject names no {@code <project>-<n>}, by a non-machine author. */
  public record NonComplyingCommit(
      String hash,
      String shortHash,
      String subject,
      String authorName,
      String authorEmail,
      String date) {}
}
