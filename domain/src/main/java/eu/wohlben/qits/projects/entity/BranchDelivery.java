package eu.wohlben.qits.projects.entity;

import eu.wohlben.qits.eventstream.Uncaused;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * <b>Where one branch stands in one repository on its way to production</b> (qits-1719, V67): the
 * last delivery step it reached there, and the request that carried it at that step. One row per
 * (project, repository, branch); the key is the rule that a branch has one position per
 * repository.
 *
 * <p>Written only by {@code control/BranchDeliveries}, in the transaction that took the step, and
 * read by it to deduplicate (a step equal to the row's step, request, fold and fold attempt is not
 * published again) and to compute a branch's {@code everywhere} — its least advanced step across every
 * repository of the project. The read door ({@code GET /projects/api/branch-deliveries}) answers
 * these rows as they stand.
 *
 * <p>Rows are kept after a release: a LIVE row is the record that the branch reached production in
 * that repository. A push that reuses the name resets it to {@link #step} {@code PUSHED}; a
 * deletion removes only a row that never got further than {@code PUSHED}.
 *
 * <p>{@link Uncaused}: machine bookkeeping written beside the release request's own caused writes,
 * on whatever thread took the step — the release worker included, where no causation scope stands.
 */
@Entity
@Table(name = "branch_delivery")
@IdClass(BranchDelivery.Key.class)
@Uncaused
public class BranchDelivery extends PanacheEntityBase {

  /**
   * The steps, in the words the ledger and {@code BranchDeliveryChanged} spell them. {@link
   * #rank} orders them for {@code everywhere}.
   */
  public enum Step {
    /** The branch exists in the repository and no request carries it. Ledger-only: never published. */
    PUSHED(0),
    /** The branch was put on a request. */
    JOINED(1),
    /** The branch was carried over from one request to another: handed on, or superseded. */
    MOVED(1),
    /**
     * A fold carrying the branch failed its CI, its automations, or its release — or could not be
     * made, because a source ref is missing on the git host (qits-1725).
     */
    FAILED(1),
    /** The request carrying the branch could not fold its sources. */
    CONFLICTED(1),
    /** The request carrying the branch was withdrawn, or the branch left it by its deletion. */
    WITHDRAWN(0),
    /** A tag carrying the branch was cut. */
    RELEASED(2),
    /** That tag's request is FINALIZED: deployed and on {@code main}, or a library's release. */
    LIVE(3);

    private final int rank;

    Step(int rank) {
      this.rank = rank;
    }

    /**
     * How far along the step is, for {@code everywhere}: 0 for a branch nothing is delivering
     * (PUSHED, WITHDRAWN), 1 on a request, 2 released, 3 live.
     */
    public int rank() {
      return rank;
    }
  }

  @Id
  @Column(name = "project_id", nullable = false)
  public String projectId;

  @Id
  @Column(name = "repo_id", nullable = false)
  public String repoId;

  /** The branch's own name, never a ref — {@code ticket/qits-1615}. */
  @Id
  @Column(nullable = false)
  public String branch;

  /** The repository's name as people read it, {@code qits-projects-service}. */
  @Column(name = "repo_name", nullable = false)
  public String repoName;

  /** One of {@link Step}'s names. */
  @Column(nullable = false, length = 32)
  public String step;

  /** The request carrying the branch at {@link #step}; null on {@code PUSHED}. */
  @Column(name = "request_id")
  public String requestId;

  @Column(name = "qualified_id")
  public String qualifiedId;

  /** The request's merged sha at the step; null before its first fold. */
  @Column(name = "fold_sha")
  public String foldSha;

  /**
   * {@code CONFLICTED}, and {@code FAILED} for a missing source: which fold attempt it is about
   * (qits-1725, V67) — see {@code control/FoldAttempt}. Null on every other step.
   */
  @Column(name = "fold_attempt")
  public String foldAttempt;

  /** {@code FAILED} only: the CI run or the newest automation run that failed the fold. */
  @Column(name = "run_id")
  public String runId;

  /** {@code RELEASED} and {@code LIVE}: the tag. */
  @Column(length = 64)
  public String version;

  @Column(name = "changed_at", nullable = false)
  public Instant changedAt;

  /** The composite key. */
  public static class Key implements Serializable {
    public String projectId;
    public String repoId;
    public String branch;

    @Override
    public boolean equals(Object other) {
      return other instanceof Key key
          && Objects.equals(projectId, key.projectId)
          && Objects.equals(repoId, key.repoId)
          && Objects.equals(branch, key.branch);
    }

    @Override
    public int hashCode() {
      return Objects.hash(projectId, repoId, branch);
    }
  }
}
