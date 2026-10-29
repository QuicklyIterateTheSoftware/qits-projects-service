package eu.wohlben.qits.projects.dto;

/**
 * <b>What one explicit source of a release request is about</b> (qits-1673): one entry per named
 * source other than the repository's default branch, in source order. It replaced the typed {@code
 * summary}, which repeated the work items a request already carries and is gone (qits-1673).
 *
 * <p>{@code label} is, by source: {@code <qualifiedId> <title>} for a branch naming a work item
 * ({@code ticket/}, {@code epic/}, {@code task/}, an agent's {@code <that branch>-<anything>}, and
 * {@code external/<project>-<n>-…}); the head commit subject of a {@code maintenance/*} branch as
 * the request's last fold read it ({@code bump(dependencies): 2 dependencies}), less a leading
 * {@code chore(<work-item ids>): } prefix ({@link
 * eu.wohlben.qits.projects.control.ReleaseRequestItems#maintenanceLabelOf}), or the branch name
 * before any fold has; the branch name for anything else.
 *
 * @param source the source's branch name, as {@code sources[].name} spells it
 * @param label what the source is about, never null
 * @param workItem the work item the branch names, read live; null where it names none
 */
public record ReleaseRequestItemDto(
    String source, String label, ReleaseRequestWorkItemDto workItem) {}
