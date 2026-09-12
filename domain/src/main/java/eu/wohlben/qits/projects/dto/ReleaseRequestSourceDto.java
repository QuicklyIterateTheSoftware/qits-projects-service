package eu.wohlben.qits.projects.dto;

/**
 * One participant of a release request, as the API answers it.
 *
 * <p>Two kinds travel through the same shape on purpose, and {@code implicit} is what tells them
 * apart for a caller:
 *
 * <ul>
 *   <li>{@code kind=BRANCH}, {@code implicit=false} — a branch somebody put on the request. {@code
 *       main} is on every request (a create implies it) and is caller-visible like any other, so
 *       "what is in this release" needs no special case.
 *   <li>{@code kind=RELEASED_TAG}, {@code implicit=true} — a release of this repository that has not
 *       reached {@code main} yet. Derived, never caller-managed: it joins every open request of the
 *       repository the moment a sibling releases and leaves them all the moment that tag is merged.
 *       A caller cannot add or remove one, and an API that let it would let somebody release a step
 *       backwards from what is already shipping.
 * </ul>
 *
 * <p>{@code ref} is the fully qualified name the git host is given ({@code refs/heads/main},
 * {@code refs/tags/2026.903.1}); {@code name} is the same thing as a person spells it.
 *
 * <p><b>{@code priority} is the branch's own urgency</b> — {@code LOWEST}, {@code LOW}, {@code
 * MEDIUM}, {@code HIGH}, {@code HIGHER} or {@code BLOCKING}, and the vocabulary may grow, so a
 * caller reads it as a word rather than as a closed set. Never null on a named source ({@code
 * MEDIUM} is what a caller who stated nothing gets) and <b>always null on an implicit one</b>:
 * a released tag has no row here and no urgency of its own, and it counts towards nothing. The
 * request's own {@code priority} is the max over the named ones.
 *
 * <p><b>{@code addedBy} is who put this branch on the request</b>, and it is on the wire because a
 * request can be shared. A project's wrapper converges per repository rather than per branch, so
 * one request carries the asks of several workspaces at once and "whose branch is this" is a
 * question a reader of that request actually has — the request's own {@code requester} is only the
 * person who opened it. Null where nobody is recorded: the implied {@code main} of a machine-made
 * create, every implicit tag source, and any row written before this field existed.
 */
public record ReleaseRequestSourceDto(
    String kind, String name, String ref, boolean implicit, String priority, String addedBy) {}
