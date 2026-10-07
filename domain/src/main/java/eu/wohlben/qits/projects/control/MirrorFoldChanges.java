package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.dto.CommitFileChangeDto;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * {@link FoldChanges} read out of the repository's mirror, through exactly the two calls {@link
 * ReleaseRequests#foldChanges} makes: {@link CommitService#resolveDiffBase} and {@link
 * CommitService#listChanges}. One base, so the files a person is shown on the changes page and the
 * files the approval gate counts can never disagree.
 *
 * <p><b>A fold the mirror does not hold yet is fetched for once.</b> The fold is written by the git
 * host, not pushed through this mirror, so a mirror fetched inside its freshness window can simply
 * not have it — the moment right after a fold is exactly when the gate is first asked. A fold still
 * absent after the fetch is a failure to read, never "changes nothing".
 */
@ApplicationScoped
@DefaultBean
public class MirrorFoldChanges implements FoldChanges {

  @Inject CommitService commits;

  @Inject GitMirrorRegistry gitMirrors;

  @Override
  public List<CommitFileChangeDto> changes(String repoId, String mergedSha, String pathspec) {
    CommitService.MergeDiffBase base = commits.resolveDiffBase(repoId, mergedSha);
    if (!base.present()) {
      gitMirrors.of(repoId).markStale();
      base = commits.resolveDiffBase(repoId, mergedSha);
    }
    if (!base.present()) {
      throw new IllegalStateException("the fold " + mergedSha + " is not in the repository's mirror");
    }
    return commits.listChanges(repoId, mergedSha, base.base(), pathspec).files();
  }

  /**
   * The two folds' difference, with the same one fetch {@link #changes} allows itself: the newer
   * fold is written by the git host and is exactly the commit a mirror inside its freshness window
   * has not got yet.
   */
  @Override
  public List<String> pathsBetween(String repoId, String foldSha, String previousFoldSha) {
    List<CommitFileChangeDto> files;
    try {
      files = commits.listChanges(repoId, foldSha, previousFoldSha, null).files();
    } catch (RuntimeException first) {
      gitMirrors.of(repoId).markStale();
      files = commits.listChanges(repoId, foldSha, previousFoldSha, null).files();
    }
    Set<String> paths = new LinkedHashSet<>();
    for (CommitFileChangeDto file : files) {
      if (file.oldPath() != null) {
        paths.add(file.oldPath());
      }
      paths.add(file.path());
    }
    return List.copyOf(paths);
  }
}
