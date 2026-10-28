package eu.wohlben.qits.projects.dto;

/**
 * The work item a release request's source names (qits-1673) — enough to show it and link to it.
 *
 * @param qualifiedId {@code <project slug>-<number>}, the id every {@code /work} door takes
 * @param title the item's title as it reads now
 */
public record ReleaseRequestWorkItemDto(String qualifiedId, String title) {}
