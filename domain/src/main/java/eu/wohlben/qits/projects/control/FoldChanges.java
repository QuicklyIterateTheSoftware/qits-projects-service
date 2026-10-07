package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.dto.CommitFileChangeDto;
import java.util.List;

/**
 * What a release request's fold changed, read against the base the request's {@code …/changes}
 * view diffs it against — the newest release tag that does not contain the fold, resolved with
 * {@code merge-base}, or the empty tree for a repository that has never released (see {@link
 * CommitService#resolveDiffBase}).
 *
 * <p><b>A port only so that the suite can stand in for it.</b> {@link MirrorFoldChanges} is the one
 * implementation and is {@code @DefaultBean}; nearly every release-request test folds through a
 * recording merger that mints shas no git repository holds, so a real read there would answer
 * "unreadable" for every request — and {@link ApprovalPolicy} reads unreadable as "ask a person".
 *
 * <p><b>It throws when the changes cannot be read</b>, and that is the whole contract a caller has
 * to honour: there is no "could not ask" value that could be mistaken for "nothing changed".
 */
public interface FoldChanges {

  /**
   * Every entry {@code mergedSha} changed under {@code pathspec}, as {@link CommitService#listChanges}
   * answers it — renames carry {@code oldPath}, gitlinks carry their {@code 160000} modes.
   *
   * @throws RuntimeException when the fold, the base or the diff cannot be read
   */
  List<CommitFileChangeDto> changes(String repoId, String mergedSha, String pathspec);

  /**
   * Every path that changed between two folds of one request — {@link CommitService#listChanges}
   * from {@code previousFoldSha} to {@code foldSha} over the whole tree, a rename counting as both of
   * its paths. This is the {@code changedSincePrevious} a release-request automation is carried over
   * on (see {@link AutomationRefresh}), and the reason it is here rather than a call into the mirror
   * from there is this port's own: the suite folds through shas no git repository holds.
   *
   * @throws RuntimeException when either fold or the diff cannot be read — never an empty list,
   *     which would say "nothing changed" and carry every outcome over
   */
  List<String> pathsBetween(String repoId, String foldSha, String previousFoldSha);
}
