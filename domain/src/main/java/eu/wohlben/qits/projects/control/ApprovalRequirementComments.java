package eu.wohlben.qits.projects.control;

import java.util.List;

/**
 * <b>Tells the work item behind a release request that an approval requirement opened</b> (epic
 * qits-1038, the owner's decision 4): what changed, where to read it, and the exact command that
 * approves it, with the roles that may run it — once per (request, fold, requirement).
 *
 * <p>The work item is named by a branch source: {@code ticket/<qualified id>} or {@code
 * epic/<qualified id>}. A source naming none gets no comment. The adapter lives in the service,
 * where the entities module is ({@code entitieshost/WorkItemApprovalComments}, {@code
 * @DefaultBean}); it never throws, and a comment that could not be written is a WARN.
 */
public interface ApprovalRequirementComments {

  /**
   * @param projectId the request's project, which the Changes link is addressed under
   * @param admits the roles that may approve it, rendered and sorted
   * @param detail the requirement's detail — the paths, joined with {@code ", "}, or a sentence
   * @param branches the request's branch sources, in order
   */
  record Opened(
      String releaseRequestId,
      String releaseRequestQualifiedId,
      String projectId,
      String repoName,
      String mergedSha,
      String requirementId,
      String reason,
      List<String> admits,
      String detail,
      List<String> branches) {}

  void opened(Opened opened);
}
