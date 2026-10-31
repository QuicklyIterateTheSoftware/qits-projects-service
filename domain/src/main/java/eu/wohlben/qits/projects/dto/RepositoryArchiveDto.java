package eu.wohlben.qits.projects.dto;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * What the archive and unarchive doors answer (qits-1784).
 *
 * @param outcome {@code requested} — the edit was committed onto {@code branch} and that branch is
 *     a source of the repository's release request — or {@code unchanged}: main already says what
 *     was asked, nothing was opened, and {@code branch}, {@code commit}, {@code releaseRequestId}
 *     and {@code qualifiedId} are null
 * @param archived what the action asks {@code repository.archived} to be once released
 * @param branch {@code archive/<repository name>} or {@code unarchive/<repository name>}
 * @param commit the branch's tip carrying the edit
 * @param releaseRequestId the id of the release request the branch is a source of
 * @param qualifiedId that request's qualified id, {@code <repoName>-rr-<n>}
 * @param detail one sentence saying what happened
 */
// The doors return a plain Response (202 or 200), so the native image cannot see this type from
// their signatures: without the registration every answer would serialize to {} or fail.
@RegisterForReflection
public record RepositoryArchiveDto(
    String outcome,
    boolean archived,
    String branch,
    String commit,
    String releaseRequestId,
    String qualifiedId,
    String detail) {

  public static final String REQUESTED = "requested";
  public static final String UNCHANGED = "unchanged";
}
