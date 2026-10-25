package eu.wohlben.qits.projects.dto;

/**
 * Where a release request stands against its repository's <b>soft feature freeze</b> (epic
 * qits-1580).
 *
 * <p>{@code features} is how many of its named branch sources <em>count</em> toward the freeze —
 * every branch naming a work item (an epic, a task, a ticket of any type, bug and maintenance
 * tickets included), as classified when each was added; {@code main}, {@code maintenance/*} and
 * {@code release/*} branches never count. {@code threshold} is the value in force and {@code source}
 * the level that set it: {@code REPOSITORY} ({@code feature_freeze} in the repository's own {@code
 * .config/qits/release-requests.yml}), {@code PROJECT} ({@code release_requests.feature_freeze} in
 * the wrapper's {@code .config/qits/project.yml}) or {@code PLATFORM} (3, where neither says) — the
 * most specific value set wins, and 0 turns the freeze off. {@code frozen} says the request is the
 * active one and has reached a threshold above 0: every further ask through the create door lands
 * in the request queued behind it, and only a join naming this request by id is admitted. Derived on
 * every read, never stored. A queued request is never frozen — it has no limit — and neither is one
 * that has left the unreleased states.
 */
public record FeatureFreezeDto(int features, int threshold, boolean frozen, String source) {}
