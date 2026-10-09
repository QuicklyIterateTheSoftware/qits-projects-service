package eu.wohlben.qits.projects.dto;

import java.util.List;

/**
 * One quality gate of a release request, in a shape a page renders with no code per kind.
 *
 * <p>It replaces {@link ReleaseGateDto} and {@link ReleasePipelineGateDto}, which stay on the
 * answer, unchanged, for the readers that already draw them. Same gates, same states: the three are
 * one evaluation.
 *
 * @param kind the gate's id, {@code [a-z0-9-]+}: {@code ci}, {@code automations}, {@code approval},
 *     {@code publish}, {@code deployment} today; more may come
 * @param label what a page prints: {@code CI build}
 * @param position the pipeline slot: {@code qa-publish}, {@code publish-deploy} or {@code
 *     deploy-finalized}; more may come
 * @param state {@code PENDING}, {@code PASSED}, {@code FAILED} or {@code UNKNOWN}. {@code UNKNOWN}
 *     means the repository's gate configuration could not be read, and {@code detail} says why
 * @param detail one sentence, or null
 * @param checks the named parts of the answer; empty where the gate has none
 * @param runId the qits-ci run the answer rests on, or null
 * @param link where a person reads more, or null
 */
public record ReleaseQualityGateDto(
    String kind,
    String label,
    String position,
    String state,
    String detail,
    List<ReleaseGateCheckDto> checks,
    String runId,
    String link) {}
