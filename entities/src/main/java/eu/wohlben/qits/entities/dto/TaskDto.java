package eu.wohlben.qits.entities.dto;

import java.time.Instant;

/**
 * @param projectId the owning project. New on this shape, for {@link FeatureDto#projectId}'s
 *     reason: a task used to reach its project by two hops, and the merged row carries it outright.
 * @param number the per-project numeric id — the bare {@code long} as stored. <b>Drawn from the
 *     project's run and not the feature's</b>; see {@link EpicDto#number}.
 * @param qualifiedId {@code <project-slug>-<number>} — {@code qits-1337}. Null until the project
 *     slug is resolved, which happens in {@code projects/api/QualifiedEntityIds} and nowhere else;
 *     see {@link EpicDto#qualifiedId}.
 */
public record TaskDto(
    String id,
    String featureId,
    String projectId,
    long number,
    String qualifiedId,
    String repositoryId,
    String title,
    String slug,
    String description,
    String dependsOnTaskId,
    Instant implementedAt,
    Instant createdAt,
    Instant updatedAt) {

  /** The same task, told what it is called in a commit subject. */
  public TaskDto withQualifiedId(String rendered) {
    return new TaskDto(
        id,
        featureId,
        projectId,
        number,
        rendered,
        repositoryId,
        title,
        slug,
        description,
        dependsOnTaskId,
        implementedAt,
        createdAt,
        updatedAt);
  }
}
