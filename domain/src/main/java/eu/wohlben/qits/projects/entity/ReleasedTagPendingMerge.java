package eu.wohlben.qits.projects.entity;

import eu.wohlben.qits.eventstream.Uncaused;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One released tag of a repository, and whether it has reached {@code main} yet.
 *
 * <p><b>Why this table exists.</b> A release is a tag; {@code main} is finalized only after the
 * deployment succeeds. Between those two moments the released commit sits on no branch, so a
 * release request opened in that window would be a step <em>backwards</em> from what is already
 * shipping — unless it folds that tag in too. The rows here with {@link #mergedAt} null are exactly
 * the repository's <b>implicit sources</b>: every open request of that repository merges them
 * alongside its named branches, which is what makes each release a superset of the releases still
 * in flight.
 *
 * <p><b>Rows are kept, never deleted.</b> A merged row is the record of which release reached
 * {@code main} and when, and it is the only place that fact lives. Deleting it would make "no row"
 * mean both "never released" and "long since merged".
 *
 * <p>{@link Uncaused}, and the reason is the same one {@code RepositoryName} gives. The insert
 * happens on the release worker — off every request thread, after a door call — where no {@code
 * CausationScope} stands, so a stamp would record null forever. The request that caused it is one
 * column away ({@link #releaseRequestId}) and <em>is</em> a caused row.
 */
@Entity
@Table(name = "released_tag_pending_merge")
@Uncaused
public class ReleasedTagPendingMerge extends PanacheEntityBase {

  /**
   * How the <b>publish</b> gate stands for this release: the release run of the tag itself — the
   * pipeline the released tree declares — having gone green.
   *
   * <p>Stored, unlike every derived answer in {@code ReleaseRequest}, and it has to be: the fact is
   * a verdict that arrived once, on a run nobody can be asked about again, and there is no table
   * anywhere correlating a tag name to a run's outcome. Null is a fourth answer and the commonest
   * one — the released tree declares no release pipeline at all, so there is no gate here.
   */
  public enum PublishState {
    /** A release pipeline is declared and no verdict has come for the tag yet. */
    PENDING,
    /** The tag's release run finished green. */
    PASSED,
    /**
     * It finished red. <b>The request stays RELEASED and open</b>: the tag is cut and cannot be
     * taken back, so this is a failed gate to be retried ({@code qits ci retry}) and never a state
     * the request leaves.
     */
    FAILED
  }

  @Id public String id;

  @Column(name = "repo_id", nullable = false)
  public String repoId;

  /** The tag's own name — the calver the release answered with, never a ref. */
  @Column(name = "tag_name", nullable = false)
  public String tagName;

  /** What the tag points at: the merged sha that was released. */
  @Column(name = "released_sha", nullable = false)
  public String releasedSha;

  /** Which request produced it, where one did. Null for a tag recorded by any other path. */
  @Column(name = "release_request_id")
  public String releaseRequestId;

  @Column(name = "released_at", nullable = false)
  public Instant releasedAt;

  /** Null while the tag is still in flight; stamped when the post-deployment merge lands it. */
  @Column(name = "merged_at")
  public Instant mergedAt;

  /**
   * When <b>every</b> post-release gate this release configures had passed and the tag became owed a
   * merge to {@code main} (V13, widened by V23).
   *
   * <p>It used to be the deployment's own stamp, because the deployment was the only gate. There are
   * two now — the publish run and the deployment — and they pass in either order, so each keeps its
   * own fact ({@link #publishState}, {@link #deploymentActiveAt}) and this column is what {@code
   * ReleaseFinalization} writes once both are satisfied. The sweep's selection is unchanged by that
   * widening, which is the point of keeping the column rather than deriving it: a row with this set
   * and {@link #mergedAt} null is a merge somebody is owed, whatever passed to get it there.
   *
   * <p><b>It is the sweep's whole selection</b>, together with a null {@link #mergedAt}. A row with
   * neither set is a release still waiting on its deployment and must never be swept: merging it
   * would put the commit on {@code main} before the deployment that justifies it, which is the
   * shape this epic removed. Stamped once and left alone, so a replayed gate is not a second ask.
   */
  @Column(name = "merge_requested_at")
  public Instant mergeRequestedAt;

  /**
   * Why the last attempt at that merge did not apply, in the git host's own words; null when none
   * has failed. Cleared by the attempt that lands.
   *
   * <p>Its presence beside a null {@link #mergedAt} is the loud state. {@code main} only ever
   * advances through these merges and every release folds the repository's pending tags in, so a
   * conflict here is an anomaly rather than ordinary traffic.
   */
  @Column(name = "merge_detail", length = 4000)
  public String mergeDetail;

  /**
   * The publish gate's answer for this tag, or null where the released tree declares no release
   * pipeline and there is therefore no such gate (V23). See {@link PublishState}.
   */
  @Enumerated(EnumType.STRING)
  @Column(name = "publish_state", length = 32)
  public PublishState publishState;

  /** What the publish gate has to say, in a sentence — which run, and how it went. */
  @Column(name = "publish_detail", length = 4000)
  public String publishDetail;

  /**
   * The qits-ci run that decided {@link #publishState}. Kept beside the word rather than folded into
   * the sentence, because a retry is addressed by run and a person acting on a red gate needs the
   * id rather than a paragraph containing it.
   */
  @Column(name = "publish_run_id")
  public String publishRunId;

  /**
   * When a {@code DeploymentActive} first named this version — the deployment gate's own fact, kept
   * apart from {@link #mergeRequestedAt} since V23 because the two post-release gates pass in either
   * order and "the deployment happened" has to survive a publish run that has not finished yet.
   *
   * <p>The <b>first</b> environment wins and later ones change nothing: a version reaching {@code
   * dev} is the same immutable coordinate that reaches every other tier.
   */
  @Column(name = "deployment_active_at")
  public Instant deploymentActiveAt;

  /**
   * When a later release of the same repository overtook this one, so nothing will ever finalize it
   * (V23). The successor folds this tag in and supersedes it whole, which is what makes abandoning
   * it safe: its content is not lost, it simply reaches {@code main} under the successor's name.
   *
   * <p>An abandoned row leaves {@code listOwedMerges} and {@code listUngated} — the sweep must stop
   * trying to merge a tag whose request is OBSOLETE — and deliberately <b>stays</b> in {@code
   * listPending}: it is still a released tag that is not on {@code main}, so every open request must
   * still fold it in or it would be a step backwards from what shipped.
   */
  @Column(name = "abandoned_at")
  public Instant abandonedAt;
}
