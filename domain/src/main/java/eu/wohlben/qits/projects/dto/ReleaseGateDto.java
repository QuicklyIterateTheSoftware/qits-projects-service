package eu.wohlben.qits.projects.dto;

/**
 * One quality gate of a release request, and what it has to say about this request's current fold.
 *
 * <p><b>{@code kind} is {@code CI}, {@code APPROVAL}, {@code PUBLISH} or {@code DEPLOYMENT}</b> — a
 * word rather than a closed set, like {@code state} and {@code priority} on the request beside it,
 * because the vocabulary may grow. A gate is in the list because the repository <em>configures</em>
 * it: a CI recipe, a deployments manifest, {@code manual-review: true}, a released tree qits-ci says
 * it runs a release for. The one exception is {@code APPROVAL}, which a fold changing the
 * repository's own {@code .config/qits/} requires whatever {@code main} configures — see {@link
 * #detail}. A gate that applies neither way is simply absent and is never waited on.
 *
 * <p>({@code PUBLISH} was missing from that sentence for longer than it was missing from the wire:
 * the gate has been emitted since the request stopped ending at the tag, and this javadoc went on
 * naming three kinds. A reader who trusted it would have treated a real gate as an unknown word.)
 *
 * <p><b>A gate DELAYS; it does not fail a release.</b> An unmet gate holds the request where it is —
 * the next phase does not start and the request stays open — which is why a red publish verdict is a
 * {@code FAILED} gate on a {@code RELEASED} request rather than a state the request moves to. The
 * same four kinds are answered again on {@link ReleasePipelineGateDto}, <em>placed</em> between the
 * phases they separate; that list is this one positioned and never a second evaluation.
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
 *
 * <p><b>{@code detail} says why a person is being asked</b>, on the {@code APPROVAL} gate and nowhere
 * else: {@code configured by manual-review}, {@code changes .config/qits/: <paths>}, {@code no
 * changes against main: the fold adds nothing to main}, or several joined with {@code "; "} — {@code
 * ApprovalPolicy}'s own reason, never re-derived here. Null on every other kind, and on an approval
 * gate nobody has to be asked about. A repository whose main configures no approval still reports an
 * {@code APPROVAL} gate on a request whose fold changes its {@code .config/qits/} or adds nothing to
 * its {@code main}, and this is the field that says so.
 */
public record ReleaseGateDto(String kind, String state, String detail) {}
