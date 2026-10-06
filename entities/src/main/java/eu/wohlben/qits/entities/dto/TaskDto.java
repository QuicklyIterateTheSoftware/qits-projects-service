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
 * @param status where this task stands — one of the entity lifecycle's nine words, as an epic's
 *     and a ticket's (qits-763). Its own, not its epic's: a task is verified on its own, and an
 *     epic's move carries it only REPORTED → REFINED, back, and to IMPLEMENTED. The markers below
 *     say when it got where this says it is
 * @param implementingAt when the implementation of this task was started, or null — the
 *     implementing marker (qits-749), stamped by {@code mark_task_implementing}. Skippable, so an
 *     implemented task may never have had it; kept once {@code implementedAt} is set, which ranks
 *     over it.
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
    String status,
    String dependsOnTaskId,
    Instant implementedAt,
    Instant implementingAt,
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
        status,
        dependsOnTaskId,
        implementedAt,
        implementingAt,
        createdAt,
        updatedAt);
  }
}
