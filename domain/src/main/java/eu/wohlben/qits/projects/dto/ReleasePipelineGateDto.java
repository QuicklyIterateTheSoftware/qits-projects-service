package eu.wohlben.qits.projects.dto;

/**
 * One gate of a release pipeline, placed between the two phases it separates.
 *
 * <p><b>A gate is the condition between two phases, and a gate DELAYS rather than fails.</b> An
 * unmet gate holds the release where it is — the next phase does not start and the request stays
 * open — and that is true of all four kinds, including the two that live past the tag. Nothing here
 * ends a release; the only thing that ends one is every gate having passed and the tag reaching
 * {@code main}.
 *
 * <p><b>This is {@link ReleaseGateDto} PLACED, never re-decided.</b> Same kinds, same states, same
 * answers, out of the same read — {@code ReleaseGates} for which gates the repository configures,
 * the build ledger for CI, the approval table for a person's yes, the released tag's row for publish
 * and deployment. What this record adds is exactly one fact, {@link #between}, and a caller that
 * finds the two lists disagreeing has found a bug rather than a nuance. Both are answered on the
 * wire because the flat list is a published surface older readers still draw.
 *
 * <p><b>{@code between} is {@code QA_PUBLISH}, {@code PUBLISH_DEPLOY} or {@code DEPLOY_FINALIZED}</b>
 * and the placement is fixed: {@code CI} and {@code APPROVAL} stand between QA and publish — both
 * are asked of the fold, before anything is tagged — {@code PUBLISH} stands between publish and
 * deploy, and {@code DEPLOYMENT} stands between deploy and the end of the pipeline. Two gates share
 * a slot, which is why this is not a field on the phase: {@code CI} and {@code APPROVAL} are both in
 * front of the publish phase and are answered by different things, so neither is "the" gate there.
 *
 * <p><b>{@code kind} is {@code ReleaseGates.Kind}'s name and {@code state} is {@code
 * ReleaseGates.State}'s</b> — {@code CI}, {@code APPROVAL}, {@code PUBLISH}, {@code DEPLOYMENT} and
 * {@code PENDING}, {@code PASSED}, {@code FAILED}, {@code UNKNOWN} — words rather than closed sets,
 * because the vocabulary may grow. {@code UNKNOWN} is not {@code PENDING} here either: it is the
 * repository's gate configuration having been unreadable, so neither which gates apply nor whether
 * any has passed is known, and the two must never be collapsed.
 *
 * <p><b>{@code detail} is a sentence where the existing path already has one, and null otherwise.</b>
 * It is sourced, never invented: the publish gate's is {@code released_tag_pending_merge}'s {@code
 * publish_detail} — which is where a publish that never reports is made audible — and an {@code
 * UNKNOWN} gate's is the gate set's own reason for not having been readable. No gate is evaluated to
 * produce one, so a gate whose existing answer carries no sentence carries none here.
 */
public record ReleasePipelineGateDto(
    String between, String kind, String state, String detail) {}
