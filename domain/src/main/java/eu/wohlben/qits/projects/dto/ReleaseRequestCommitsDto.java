package eu.wohlben.qits.projects.dto;

import java.util.List;

/**
 * What one release request folded in — the commits its octopus merge brought onto the backing
 * branch, which is the answer to "what is actually in this release".
 *
 * <p><b>The base is the released tags, not the fold's parents.</b> The list is everything reachable
 * from {@code mergedSha} minus every release tag that does not contain it: {@code main} only ever
 * advances by merging released tags, so "already shipped" is "reachable from a release tag". A
 * parent-derived range would under-report a re-folded request down to its last re-fold, and a first
 * fold that fast-forwarded has no fold commit to have parents at all. The tag base stays the same
 * answer after the release reaches {@code main} — later tags contain the fold and are never
 * subtracted — which is why the question can still be asked of a request that concluded weeks ago.
 *
 * <p><b>The version bump is deliberately not in it.</b> The release commits the rewritten manifests
 * <em>onto</em> the fold and tags that commit, so {@code releasedSha} is a child of {@code
 * mergedSha} and outside the range. What is listed here is the work; the bump is bookkeeping.
 *
 * @param mergedSha the fold this list is about, null on a request whose first fold has not landed
 * @param commits the commits the fold brought in, newest first — <b>the fold itself leads them</b>,
 *     and the backing branch's own fold merges carry {@code fold: true},
 *     because a range ending at a commit contains it. That is the honest shape: the merge is the
 *     newest thing in the release and its message is the request's own summary. Empty is a real
 *     answer.
 * @param detail why the list is empty, where the emptiness needs a sentence — nothing folded yet,
 *     the fold pruned out of the repository's history, or a fold that genuinely added nothing. Null
 *     whenever the list stands on its own.
 * @param foldParents the fold commit's parents, in its own parent order (the same shas as the
 *     leading commit's {@code parents}), each named after the source it stands for — see {@link
 *     FoldParentDto}. Empty when the fold is not a merge (a first fold that fast-forwarded onto its
 *     one effective source: {@code mergedSha} is then that source's own commit) or there is no
 *     fold to read. On a re-folded request it names the newest fold's parents only: the previous
 *     fold, then the sources that moved.
 * @param sources every source of the request in source order ({@code main}, the named branches as
 *     they were added, then the released tags oldest first), each with the commit its ref points at
 *     now. A reader names a lane after the first source, in this order, whose tip reaches it. Empty
 *     on every empty answer.
 */
public record ReleaseRequestCommitsDto(
    String mergedSha,
    List<CommitDto> commits,
    String detail,
    List<FoldParentDto> foldParents,
    List<FoldSourceDto> sources) {

  /** Nothing to draw lanes for: every empty answer. */
  public ReleaseRequestCommitsDto(String mergedSha, List<CommitDto> commits, String detail) {
    this(mergedSha, commits, detail, List.of(), List.of());
  }
}
