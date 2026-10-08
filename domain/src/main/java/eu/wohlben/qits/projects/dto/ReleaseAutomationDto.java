package eu.wohlben.qits.projects.dto;

import java.time.Instant;

/**
 * One release-request automation on a request — a regeneration that runs on every fold and has to
 * be fresh for {@code mergedSha} before the request may release (epic qits-978).
 *
 * <p>{@code kind} and {@code state} are words, not closed sets: qits-maintenance owns the kinds and
 * the states, and adding a kind must cost this surface nothing. {@code state} is the far side's
 * {@code FRESH}, {@code REQUESTED}, {@code RUNNING}, {@code COMMITTED}, {@code FAILED}, {@code
 * UNKNOWN} or {@code SUPERSEDED}, plus this service's own {@code WAIVED}: a person waived the gate
 * for this row's fold, and the row was not fresh.
 *
 * @param foldSha the fold this row's outcome is about
 * @param runId the newest qits-ci run behind it, or null where it has not run
 * @param branch the branch it writes, or null
 * @param detail the far side's sentence, or null
 * @param failure why the newest run went red — the failing step, its image, its exit code and the
 *     tail of its log — or null where it did not fail or the far side did not say. A WAIVED row keeps
 *     it: the waiver changes what the gate decides, not why the run failed.
 */
public record ReleaseAutomationDto(
    String kind,
    String label,
    String state,
    String foldSha,
    String runId,
    String branch,
    String detail,
    Instant updatedAt,
    ReleaseAutomationFailureDto failure) {}
