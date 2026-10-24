package eu.wohlben.qits.projects.dto;

/**
 * Where a release request stands against its project's <b>soft feature freeze</b> (epic qits-1580).
 *
 * <p>{@code features} is how many of its named branch sources carry a feature — a ticket that is not
 * a bug, an epic, a task — as classified when each was added; {@code threshold} is the project's
 * {@code release_requests.feature_freeze} (1 to 20, default 3). {@code frozen} says the request is
 * the active one and has reached the threshold, so the next feature branch asked for its repository
 * lands in the request queued behind it; fixes, maintenance branches and {@code main} still join it.
 * Derived on every read, never stored. A queued request is never frozen — it has no limit — and
 * neither is one that has left the unreleased states.
 */
public record FeatureFreezeDto(int features, int threshold, boolean frozen) {}
