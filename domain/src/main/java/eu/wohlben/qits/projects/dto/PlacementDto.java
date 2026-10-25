package eu.wohlben.qits.projects.dto;

/**
 * Where an ask for a release landed (epic qits-1580) — answered by both doors that put a branch on a
 * request, beside the request itself.
 *
 * <p>{@code outcome} is {@code CREATED} (a request was minted for the branch), {@code JOINED} (the
 * branch became a source of a request that already existed) or {@code PRESENT} (the branch was
 * already a source and nothing was added); a word rather than a closed set, like every state word on
 * this surface. {@code queued} says the answering request is queued behind the repository's active
 * request, which reached its feature freeze: it folds nothing and runs no gate until that request has
 * left the unreleased states. {@code detail} is one sentence saying where the branch went and why —
 * queued behind a frozen request, joined below the freeze, or joined a frozen request explicitly
 * through the sources door — built here so a caller prints it rather than reconstructs it.
 */
public record PlacementDto(String outcome, boolean queued, String detail) {}
