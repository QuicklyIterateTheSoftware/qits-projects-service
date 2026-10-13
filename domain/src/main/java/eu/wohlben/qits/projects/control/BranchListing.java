package eu.wohlben.qits.projects.control;

import java.util.List;

/**
 * The branches a repository has <b>on the git host</b>, as they stand now (qits-1218).
 *
 * <p>A seam rather than a direct call into {@link RepositoryService#listBranches} for {@link
 * FoldChanges}' reason: the one reader, the release a move into VERIFIED asks for, is exercised by a
 * suite whose repositories hold no branch the work items it walks would name, and whose git host is
 * a map ({@code RecordingReleaseGitHost}). A listing read off a real {@code ls-remote} there would
 * answer a different git host from the one every other read in that flow answers. The shipped
 * implementation is {@link RepositoryBranchListing}, a {@code @DefaultBean}, so a suite's fake wins
 * the injection simply by existing.
 */
public interface BranchListing {

  /**
   * Every branch {@code repoId} has on the git host, by name ({@code ticket/qits-1}, not {@code
   * refs/heads/…}).
   *
   * @throws RuntimeException when the repository or its git host cannot be read — never an empty
   *     list, which would say "this repository has no branch" and pass its work over
   */
  List<String> branches(String repoId);
}
