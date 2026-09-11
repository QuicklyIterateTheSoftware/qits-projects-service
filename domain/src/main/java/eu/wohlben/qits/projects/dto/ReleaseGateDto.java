package eu.wohlben.qits.projects.dto;

/**
 * One quality gate of a release request, and what it has to say about this request's current fold.
 *
 * <p><b>{@code kind} is {@code CI}, {@code APPROVAL} or {@code DEPLOYMENT}</b> — a word rather than a
 * closed set, like {@code state} and {@code priority} on the request beside it, because the
 * vocabulary may grow. A gate is in the list because the repository <em>configures</em> it: a CI
 * recipe, a deployments manifest, {@code manual-review: true}. A gate the repository does not
 * configure is simply absent and is never waited on.
 *
 * <p><b>{@code state} is {@code PENDING}, {@code PASSED}, {@code FAILED} or {@code UNKNOWN}.</b>
 *
 * <ul>
 *   <li>{@code PENDING} — configured, and nothing has answered yet.
 *   <li>{@code PASSED} — satisfied.
 *   <li>{@code FAILED} — red. For CI this has already rejected the request.
 *   <li>{@code UNKNOWN} — <b>the repository's gate configuration could not be read</b>, so neither
 *       which gates apply nor whether any has passed is known.
 * </ul>
 *
 * <p><b>{@code UNKNOWN} is not {@code PENDING} and the two must never be collapsed.</b> A gate set
 * that could not be read is not an empty one and not a gate quietly in progress: the request waits
 * and the surface has to be able to say why. An unreadable configuration therefore answers every
 * kind at {@code UNKNOWN} rather than an empty list, because an empty list reads as "this repository
 * is gated by nothing", which is the one thing it must not be mistaken for.
 *
 * <p><b>The deployment gate is reported on a request that has already released</b>, where it is the
 * only gate left: a release is a tag, and {@code main} is finalized when the deployment is live, so
 * "released, waiting on its deployment" is a real state today that the surface showed nothing for.
 */
public record ReleaseGateDto(String kind, String state) {}
