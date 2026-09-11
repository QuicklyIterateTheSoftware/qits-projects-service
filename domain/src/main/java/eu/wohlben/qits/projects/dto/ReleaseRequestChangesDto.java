package eu.wohlben.qits.projects.dto;

import java.util.List;

/**
 * What one release request's fold <b>changed</b> — the files, beside {@link
 * ReleaseRequestCommitsDto}'s commits, and the answer to "what does this release actually do to the
 * tree".
 *
 * <p><b>The base is the newest release tag that does not contain the fold</b>, resolved to one
 * commit with {@code merge-base}. Not {@code mergedSha^1} (a re-fold's first parent is the previous
 * fold, and a fast-forwarded fold is not a merge commit at all), and not {@code merge-base(…,
 * main)}, which answers the same until this release reaches {@code main} and then collapses to the
 * fold itself and reports nothing. A repository that has never released is diffed against the empty
 * tree, which is honestly what its first release adds.
 *
 * @param mergedSha the fold these files are about, null on a request whose first fold has not landed
 * @param base the commit the diff was taken against, null when it was taken against the empty tree
 * @param baseTag the release tag {@code base} was resolved from, so the page can say "since
 *     2026.910.180413" rather than print a sha. Null exactly when {@code base} is.
 * @param files the paths the fold touched, relative to that base. Empty is a real answer.
 * @param truncated whether the list was cut at the cap; {@code detail} then names the true total
 * @param detail why the list is empty or short, where that needs a sentence — nothing folded yet,
 *     the fold pruned out of the repository's history, a fold that genuinely changed nothing, or
 *     the cap. Null whenever the list stands on its own.
 */
public record ReleaseRequestChangesDto(
    String mergedSha,
    String base,
    String baseTag,
    List<CommitFileChangeDto> files,
    boolean truncated,
    String detail) {}
