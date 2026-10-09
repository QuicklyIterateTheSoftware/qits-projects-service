package eu.wohlben.qits.projects.dto;

/**
 * One source of a release request with the commit its ref points at now — what a reader names a
 * lane of the fold after.
 *
 * @param name the source's name as {@code sources[].name} spells it on the request
 * @param kind {@code BRANCH} or {@code RELEASED_TAG}, as on the request
 * @param ref the full ref ({@code refs/heads/…}, {@code refs/tags/…})
 * @param tipSha the commit the ref points at in the repository now (an annotated tag answers its
 *     commit); null where the repository no longer holds the ref
 */
public record FoldSourceDto(String name, String kind, String ref, String tipSha) {}
