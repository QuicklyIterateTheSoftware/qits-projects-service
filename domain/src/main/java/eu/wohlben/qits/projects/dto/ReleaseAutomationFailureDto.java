package eu.wohlben.qits.projects.dto;

/**
 * Why a release-request automation's newest run went red, as qits-maintenance read it off the run —
 * carried on {@link ReleaseAutomationDto#failure()} so a page can say which step failed and how,
 * without a second request.
 *
 * @param stepIndex the failing step's position in the run, zero-based
 * @param image the failing step's image
 * @param exitCode its exit code, or null where it did not exit (killed, never started)
 * @param excerpt the tail of its log, or null where none was kept
 */
public record ReleaseAutomationFailureDto(
    int stepIndex, String image, Integer exitCode, String excerpt) {}
