package eu.wohlben.qits.projects.dto;

import java.util.List;

/**
 * What one tag collection judged, removed, kept and could not do — the answer of {@code POST
 * /projects/api/gc/tags}.
 *
 * <p>A dry run answers the same shape: {@link #deleted} is then what <em>would</em> go, judged
 * exactly as a real run judges it.
 *
 * @param dryRun whether anything was actually deleted
 * @param repositories how many repositories were read and judged; a repository whose mirror could
 *     not be read is not counted, and is named in {@link #errors}
 * @param examined how many calver tags were judged: every one on the git host, plus every one the
 *     twin holds that the host does not
 * @param deleted every tag removed, or that a dry run would remove, and on which side
 * @param kept how many examined tags were kept, by the first rule that kept each one
 * @param errors one sentence per repository or side that was skipped, in the order they happened
 */
public record TagCollectionReportDto(
    boolean dryRun,
    int repositories,
    int examined,
    List<DeletedTag> deleted,
    KeptTags kept,
    List<String> errors) {

  /**
   * One tag that left, or would leave.
   *
   * @param repository the repository's name
   * @param tag the tag's name, a calver
   * @param host whether it was (or would be) deleted on the git host
   * @param twin whether it was (or would be) deleted on the backup twin
   */
  public record DeletedTag(String repository, String tag, boolean host, boolean twin) {}

  /**
   * The kept tags, counted once each under the first rule that kept them, in this order.
   *
   * @param newest among the repository's newest calver tags on the git host
   * @param pinnedVersion its name is a version some pin source names
   * @param gitlink its commit is a gitlink in a tree that is itself kept
   * @param inFlight released and not yet on {@code main}, or its request not yet finalized
   * @param young created within the minimum age
   */
  public record KeptTags(int newest, int pinnedVersion, int gitlink, int inFlight, int young) {}
}
