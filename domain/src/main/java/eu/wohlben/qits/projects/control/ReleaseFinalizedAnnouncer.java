package eu.wohlben.qits.projects.control;

import java.time.Instant;

/**
 * Tells the platform that <b>the default branch has this release</b>: a released tag reached {@code
 * main}, and {@code main} now names the released content.
 *
 * <p>A port, for the reason every other reach out of {@code domain} is one: the announcement leaves
 * over {@code qits-eventstream}'s bus, and {@code domain} does not know the event bus exists. The
 * one implementation is {@code service/…/bus/SCMReleaseFinalizedAnnouncer}, which publishes {@code
 * SCMReleaseFinalized}.
 *
 * <p><b>Beside {@link ReleaseAnnouncer}, never folded into it.</b> That port fires when qits-githost
 * accepts the TAG, which is before the release pipeline has run and long before the deployment that
 * gates this one. Two ports because they are two moments: everything downstream of a release
 * happens between them, and a consumer that needs "main changed" cannot get it from the earlier
 * statement without reading a branch that has not moved yet.
 *
 * <p><b>Absent is a supported configuration</b>, like every port here: with no implementation a tag
 * still reaches main and simply announces nothing. Injected as an {@code Instance<T>} for that
 * reason. <b>Nothing here may throw</b>, and nothing here may be called inside a transaction the
 * caller needs — the ref has moved the instant the git host accepts the merge, so an announcement
 * conditional on the bookkeeping after it would be silent about a merge that really happened.
 */
public interface ReleaseFinalizedAnnouncer {

  /**
   * A released tag reached the default branch.
   *
   * @param projectId the project the repository belongs to, or null where it has none
   * @param repoId the repository's storage id — the git host's key
   * @param repoName the repository's registered name, or null. The coordinate a consumer keyed by
   *     name can address, which the id is not: a row id is minted per platform instance
   * @param target the ref that moved, fully qualified ({@code refs/heads/main})
   * @param version the release stamp whose tag was merged, which is also the tag's name
   * @param mergedSha what {@code target} names now — <b>the merge's answer, not the released
   *     sha</b>: a fold onto a branch that moved underneath is a merge commit, and a target that
   *     already contained the release answers the sha it already had
   * @param occurredAt when the git host accepted the merge
   */
  void onReleaseFinalized(
      String projectId,
      String repoId,
      String repoName,
      String target,
      String version,
      String mergedSha,
      Instant occurredAt);
}
