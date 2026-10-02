package eu.wohlben.qits.projects.control;

import java.util.List;

/**
 * What qits-ci <b>decided</b> about each artifact of one release — the record {@link
 * ReleaseArtifacts} lists, in place of the declaration at the tag.
 *
 * <p><b>Why the declaration stopped being the answer</b> (qits-642). An artifact declared {@code
 * publish: if-changed}, or a contract package, is published at a release version only when its
 * content differs from the newest published one. {@code release.yml} at the tag says the artifact
 * exists, not that this version of it does, so a listing read from it names
 * {@code qits-projects-golden-masters} at versions the store never received. qits-ci keeps the
 * decision per (release run, artifact) and serves it from {@code GET
 * /ci/api/repositories/{repoId}/releases/{version}/artifacts}; that record is what was published.
 *
 * <p>A port in the house shape ({@link PublishRuns}' sibling): the implementation is {@code
 * service/…/releasehost}, resolved through {@code Instance} with absent supported, and it must not
 * throw — every failure is an {@link Answer#failed} carrying its reason.
 */
public interface ReleaseDecisions {

  /**
   * One artifact's row in qits-ci's record.
   *
   * @param type the artifact type as declared ({@code docker}, {@code maven}, {@code npm}, {@code
   *     docs}, …)
   * @param name the coordinate
   * @param decision qits-ci's decision, verbatim and lower-case: {@code pending}, {@code published},
   *     {@code unchanged}, {@code absent} or {@code unverified}. Kept as the wire's string, so a
   *     decision this service has not heard of is passed over rather than failing the panel
   * @param unchangedSince for {@code unchanged}, the version the content was last published at;
   *     otherwise null
   */
  record Decision(String type, String name, String decision, String unchangedSince) {

    public static final String PENDING = "pending";
    public static final String PUBLISHED = "published";
  }

  /**
   * The record, or why it could not be read. An empty list with no {@code failure} is an answer:
   * nothing was owed at that version.
   */
  record Answer(List<Decision> decisions, String failure) {

    public static Answer of(List<Decision> decisions) {
      return new Answer(List.copyOf(decisions), null);
    }

    public static Answer failed(String reason) {
      return new Answer(List.of(), reason == null ? "no reason was given" : reason);
    }

    public boolean ok() {
      return failure == null;
    }
  }

  /**
   * qits-ci's decision record for one release of one repository.
   *
   * @param repoId the repository, by the id qits-ci shares with this service
   * @param version the release version
   */
  Answer of(String repoId, String version);
}
