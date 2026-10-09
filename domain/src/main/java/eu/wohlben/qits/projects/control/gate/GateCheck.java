package eu.wohlben.qits.projects.control.gate;

/**
 * One named part of a gate's answer, such as one CI run or one automation.
 *
 * @param name what a page prints for it
 * @param state the gate vocabulary: {@code PENDING}, {@code PASSED}, {@code FAILED}, {@code
 *     UNKNOWN}
 * @param detail one sentence, or null
 * @param runId the qits-ci run behind it, or null
 */
public record GateCheck(String name, String state, String detail, String runId) {}
