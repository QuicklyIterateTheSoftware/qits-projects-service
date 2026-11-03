package eu.wohlben.qits.projects.dto;

import eu.wohlben.qits.projects.entity.RepositoryArchetype;

/**
 * One repository's public coordinates — the flat catalogue entry a machine enumerates the platform
 * with.
 *
 * <p>It is still smaller than {@link RepositoryDto}: a caller listing every repository is choosing
 * which ones to act on, not drawing one, so the backup status and the wrapper-derived component
 * stay out. What it carries is the pair that addresses a repository publicly, the branch to read,
 * the archetype — and whether the project's wrapper still declares it.
 *
 * <p><b>The archetype is here because a consumer is keyed on it.</b> This DTO argued the opposite
 * until the release trains landed: archetype was called noise a lister would have to skip past. That
 * reasoning predates qits-maintenance's release-train reader, which decides what a train does with a
 * repository from its kind — a library is bumped into its consumers, a frontend follows its shared
 * library, a service is released on its own — so it needs every repository's archetype before it
 * picks any of them. Reading the kind at the far end would be a second reader of what this service
 * stores (the stated kind, never one derived from the name), free to drift from the column; asking
 * {@code GET /repositories/{repoId}} once per row would be a request per repository to learn one
 * enum.
 *
 * @param id the opaque row id — the storage key this service and qits-githost share. Never shown to
 *     a person and never an address.
 * @param projectId the project the repository belongs to; the first half of the public identity
 * @param name the addressable name from the alias table — the second half. <b>Null for a row that
 *     owns no alias</b>, and such a row is still listed: it is a fact about this service's state,
 *     and a caller that cannot address it skips it. Omitting it would hide the row instead.
 * @param mainBranch the branch a caller reads by default
 * @param archetype what kind of component this is, serialized <b>by name</b> ({@code SERVICE},
 *     {@code LIBRARY}, …). <b>Null, and the key may be absent</b>, for a row nobody has stated a kind
 *     for — neither its creator nor its own {@code .config/qits/repository.yml} — and this service
 *     stores null rather than guessing. A consumer parses it leniently and treats an absent
 *     key exactly as a null one; it must never key a lookup on it without an answer for the null.
 * @param declared whether the project's wrapper names this repository — the same reading as {@code
 *     declared} on {@code GET /projects/{projectId}/repositories}, from the one rule {@code
 *     WrapperReconcileService.declares}. <b>False is a row no wrapper entry names</b>: it is no
 *     longer part of its project, and qits-maintenance stops bumping it (qits-1695). True for
 *     everything membership does not apply to — the wrapper itself, an archetype that is not a
 *     component of its project, a project with no wrapper, and a manifest that is unreadable or
 *     declares nothing.
 */
public record RepositoryCoordinatesDto(
    String id,
    String projectId,
    String name,
    String mainBranch,
    RepositoryArchetype archetype,
    boolean declared) {}
