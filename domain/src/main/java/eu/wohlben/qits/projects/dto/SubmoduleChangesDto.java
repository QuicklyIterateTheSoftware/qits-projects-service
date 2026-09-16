package eu.wohlben.qits.projects.dto;

import java.util.List;

/**
 * One gitlink of a release request's fold, <b>expanded</b>: the sibling repository the path resolves
 * to, and that repository's own commits and changed files between the two pins.
 *
 * <p>This is the answer to the question a wrapper release actually poses. The wrapper's own diff
 * says a forty-character string became another forty-character string; what the release <em>is</em>
 * lives entirely in the sibling, and until this read existed the only way to see it was to leave the
 * page, find the right repository by hand and compare two shas nobody had written down.
 *
 * <p><b>Every failure along the way is a sentence on {@code detail}, never an exception</b>, and
 * that is the contract rather than a politeness. A wrapper fold is twenty-seven of these rows: a
 * sibling that has been renamed, one this project has no repository for, one whose git host is
 * momentarily unreachable, one whose pin was pruned — each is a fact about that one submodule, and a
 * throw would take the other twenty-six rows down with it. {@code repositoryId} null with a detail
 * set is the ordinary degraded answer and the caller renders the sentence.
 *
 * @param path the gitlink's path in the wrapper — the address the caller asked by, echoed back
 * @param repositoryId the sibling repository, or null when the chain stopped before resolving one
 * @param name the sibling's addressable name, or null when nothing in the manifest gave one
 * @param oldSha the pin on the base side, null when this release adds the gitlink
 * @param newSha the pin on the fold side, null when this release removes it
 * @param commits the sibling's commits in {@code oldSha..newSha}, newest first. Empty is a real
 *     answer — a pin moved backwards adds nothing, and {@code detail} says so.
 * @param files the sibling's changed files between the two pins, capped like every other change
 *     list here
 * @param truncated whether {@code files} was cut at the cap; {@code detail} then names the total
 * @param detail why this is all there is, in a sentence. Null only when the expansion is complete
 *     and unremarkable.
 */
public record SubmoduleChangesDto(
    String path,
    String repositoryId,
    String name,
    String oldSha,
    String newSha,
    List<CommitDto> commits,
    List<CommitFileChangeDto> files,
    boolean truncated,
    String detail) {}
