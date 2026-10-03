package eu.wohlben.qits.entities.dto;

import java.time.Instant;

/**
 * @param projectId the owning project. New on this shape: a feature used to reach its project by
 *     walking up to its epic, and the merged row carries it outright — which is what lets a listing
 *     of features be qualified without a second walk.
 * @param number the per-project numeric id — the bare {@code long} as stored. <b>Drawn from the
 *     project's run and not the epic's</b>, so a feature and a ticket in one project can never
 *     share one; see {@link EpicDto#number} for why both it and the rendering travel.
 * @param qualifiedId {@code <project-slug>-<number>} — {@code qits-1337}. Null until the project
 *     slug is resolved, which happens in {@code projects/api/QualifiedEntityIds} and nowhere else;
 *     see {@link EpicDto#qualifiedId}.
 * @param implementingOn when the implementation of this feature was started, or null — the
 *     implementing marker (qits-749), stamped by {@code mark_task_implementing} on the first of its
 *     tasks. Skippable, so an implemented feature may never have had it; kept once {@code
 *     implementedOn} is set, which ranks over it.
 */
public record FeatureDto(
    String id,
    String epicId,
    String projectId,
    long number,
    String qualifiedId,
    String title,
    String slug,
    String description,
    String dependsOnFeatureId,
    Instant implementedOn,
    Instant implementingOn,
    Instant createdAt,
    Instant updatedAt) {

  /** The same feature, told what it is called in a commit subject. */
  public FeatureDto withQualifiedId(String rendered) {
    return new FeatureDto(
        id,
        epicId,
        projectId,
        number,
        rendered,
        title,
        slug,
        description,
        dependsOnFeatureId,
        implementedOn,
        implementingOn,
        createdAt,
        updatedAt);
  }
}
