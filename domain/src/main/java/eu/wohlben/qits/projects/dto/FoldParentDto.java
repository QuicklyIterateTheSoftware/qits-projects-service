package eu.wohlben.qits.projects.dto;

/**
 * One parent of a release request's fold commit, named after what it stands for — so a reader can
 * draw each lane of the fold under its source's name.
 *
 * <p><b>How the fold orders its parents</b> (qits-githost's octopus primitive): the backing branch's
 * previous tip first, when the branch existed; then the request's sources in source order —
 * {@code main}, the named branches in the order they were added, the released tags oldest first. A
 * source whose commit another head already contains is dropped, and two sources on one commit are
 * one parent. So the position of a parent does not say which source it is; this record does.
 *
 * @param sha the parent's full sha, in the fold's own parent order
 * @param role {@code SOURCE} — a source's ref points at this commit now; {@code PREVIOUS_FOLD} —
 *     the first parent, matching no source and itself a merge: the backing branch as the previous
 *     fold left it; {@code UNMATCHED} — anything else, typically a source pushed again after this
 *     fold (the next fold will name it)
 * @param source the source's name exactly as {@code sources[].name} spells it on the request
 *     ({@code main}, {@code feature/x}, {@code 2026.903.1}); null unless {@code role} is {@code
 *     SOURCE}. Where two sources point at the same commit, the first in source order
 * @param ref the full ref: the source's ({@code refs/heads/…}, {@code refs/tags/…}) for {@code
 *     SOURCE}, {@code refs/heads/release/<id>} for {@code PREVIOUS_FOLD}, null for {@code UNMATCHED}
 */
public record FoldParentDto(String sha, String role, String source, String ref) {}
