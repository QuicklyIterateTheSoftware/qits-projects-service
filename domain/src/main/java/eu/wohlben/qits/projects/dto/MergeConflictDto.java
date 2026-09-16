package eu.wohlben.qits.projects.dto;

import java.util.List;

/**
 * Why a CONFLICTED request could not be folded — qits-githost's own answer, forwarded rather than
 * reworded.
 *
 * <p>Null on every request that is not CONFLICTED, and cleared by the first fold that succeeds. It
 * is on the read because a conflict is the one state a person has to <b>act</b> on: the paths say
 * what to resolve and {@code head} says which participant introduced it, which is git's own answer
 * to "who broke it" and the only one a caller can do anything with.
 */
public record MergeConflictDto(String target, List<ConflictedPath> conflicts) {

  /**
   * One conflicting path. {@code head} is the source <b>as it was spelled to the git host</b>
   * ({@code refs/heads/feature/x}, {@code refs/tags/2026.903.1}), {@code headSha} what it pointed at,
   * and {@code reason} the git host's word for the kind of conflict ({@code content} and the
   * merger's own failure reasons). Named after the git host's own record on purpose — a nested
   * {@code Path} would land in the generated OpenAPI document under that name and mean nothing.
   *
   * <p>The last four say what the two sides actually hold, which is what makes a conflict something a
   * person can look at rather than only a path to go and open. {@code kind} is {@code gitlink} or
   * {@code file} and is never null; {@code base}, {@code ours} and {@code theirs} are 40-hex commit
   * shas or null, and a null side means that side does not have the path at all — a deletion, which
   * reads very differently from two sides disagreeing about content and is worth rendering
   * differently.
   *
   * <p><b>For a gitlink the three shas are commits of the SUBMODULE's repository</b>, not of the one
   * being released, so a client linking them has to link them there. That is also the whole reason
   * they are on the read: a submodule pin conflict is two versions of a sibling, and without the two
   * shas the screen can only say that a path somebody has never edited by hand is in conflict.
   */
  public record ConflictedPath(
      String path,
      String head,
      String headSha,
      String reason,
      String kind,
      String base,
      String ours,
      String theirs) {}
}
