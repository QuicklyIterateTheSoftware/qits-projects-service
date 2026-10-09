package eu.wohlben.qits.projects.dto;

/**
 * One named part of a quality gate's answer — one CI run, one automation.
 *
 * @param name what a page prints for it
 * @param state {@code PENDING}, {@code PASSED}, {@code FAILED} or {@code UNKNOWN}, the gate words
 * @param detail one sentence, or null
 * @param runId the qits-ci run behind it, or null
 */
public record ReleaseGateCheckDto(String name, String state, String detail, String runId) {}
