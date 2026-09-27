package eu.wohlben.qits.entities.campaign;

import eu.wohlben.qits.entities.entity.WorkEntity;

/**
 * <b>The qualified id of a row — {@code qits-412} — for a sentence this module writes down.</b>
 *
 * <p>A port, optional like every one here, because the project slug lives in {@code domain}'s
 * database and this module depends on {@code domain} nowhere: the assembling service implements it
 * ({@code projects/api/ProjectSlugEntityQualifier}). {@link CampaignService} uses it for the evidence
 * summary a state-at-start latch stores and for the members a refusal names; with no implementation
 * it falls back to {@code #<number>}, which is still unambiguous inside one project.
 *
 * <p><b>Must never throw</b>: it decorates a sentence, and a sentence must not fail the write it is
 * part of. An implementation that cannot resolve the slug answers null and the fallback is used.
 */
public interface EntityQualifier {

  /** {@code <project-slug>-<number>}, or null when the project cannot be resolved. */
  String qualifiedId(WorkEntity entity);
}
