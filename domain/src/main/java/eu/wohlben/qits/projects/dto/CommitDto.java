package eu.wohlben.qits.projects.dto;

import java.util.List;

/**
 * A single commit in a branch's log.
 *
 * @param hash the full commit SHA
 * @param shortHash the abbreviated commit SHA (git's default short form)
 * @param author the author's name
 * @param email the author's email
 * @param date the committer date in strict ISO-8601 form (git {@code %cI})
 * @param message the commit subject (first line)
 * @param files the paths the commit changed (empty for merge commits, which git omits under {@code
 *     --name-only})
 * @param parents the full shas of the commit's parents, in git's order: the first parent first.
 *     Empty for a root commit; two or more for a merge
 * @param fold true for a release request's own fold merges on its backing branch — set only by the
 *     release request's commits read, false everywhere else
 */
public record CommitDto(
    String hash,
    String shortHash,
    String author,
    String email,
    String date,
    String message,
    List<String> files,
    List<String> parents,
    boolean fold) {

  /** This commit, marked as a fold merge or not. */
  public CommitDto withFold(boolean value) {
    return new CommitDto(hash, shortHash, author, email, date, message, files, parents, value);
  }
}
