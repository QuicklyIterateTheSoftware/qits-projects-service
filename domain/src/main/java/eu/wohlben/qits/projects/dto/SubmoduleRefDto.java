package eu.wohlben.qits.projects.dto;

/**
 * What a {@code 160000} tree entry actually names: the repository the wrapper's {@code .gitmodules}
 * resolves the path to, and the two commits of <em>that</em> repository the release moves between.
 *
 * <p>Without it a wrapper's release reads as a wall of opaque pairs of {@code Subproject commit}
 * lines — forty hex characters changing to forty other hex characters, in a repository whose name
 * the diff never mentions. The gitlink is the whole content of a wrapper release, so "which
 * repository, and which two commits" is not decoration on that page, it is the page.
 *
 * <p><b>Filling this in never touches the child repository.</b> Everything here comes from the
 * wrapper's own fold — the pins off the raw diff, the name and url off {@code .gitmodules} at that
 * same commit — and from one row lookup. A 27-submodule estate fold labels every row without a
 * single clone, fetch or {@code cat-file} against a sibling; expanding <em>one</em> row into its
 * commits is the separate, deliberately more expensive read.
 *
 * @param repositoryId the repository of the same project whose name the entry resolves to, or null
 *     when nothing answers to that name — the wrapper may declare a submodule this project has no
 *     repository for, which is drift rather than an error
 * @param name the sibling's addressable name, or null when the manifest declared neither a url nor
 *     a usable path
 * @param oldSha the pin on the base side, null when this release adds the gitlink
 * @param newSha the pin on the fold side, null when this release removes it
 * @param detail why this entry cannot be expanded into the sibling's own commits, in a sentence a
 *     person reads. Null exactly when it resolved to a repository and both pins are there to ask
 *     about.
 */
public record SubmoduleRefDto(
    String repositoryId, String name, String oldSha, String newSha, String detail) {}
