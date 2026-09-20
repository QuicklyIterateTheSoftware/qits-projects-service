package eu.wohlben.qits.projects.entity;

import eu.wohlben.qits.eventstream.CausationStamp;
import eu.wohlben.qits.eventstream.CausedRow;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One CI run's terminal verdict about a commit — the per-commit build-status ledger, fed from
 * qits-ci's {@code BuildSuccessful}/{@code BuildFailed} events by {@code
 * service/…/bus/BuildStatusListener}.
 *
 * <p>Keyed on the <b>run</b>, not the commit: a commit can carry several runs (a push pipeline and
 * event pipelines), and what a reader wants is the set of verdicts, which it folds by its own
 * policy. Cancelled and superseded runs never publish, so every row here is a
 * build that genuinely ran — or genuinely could not run — against exactly that commit; what is
 * <em>not</em> knowable from this table is a run that is still queued or running, because only
 * terminal runs announce. The release quality gate this ledger exists for reads it accordingly.
 *
 * <p><b>A row is nonetheless REMOVED when a retry supersedes it</b> (ticket qits-309), and that is
 * the one exception to "every run that announced has a row here". {@code qits ci retry} mints a new
 * run at the same fold carrying {@link #retryOfRunId}, so the two runs are not two opinions about
 * the commit — the second is the answer to the first. Leaving both would make the reader's
 * any-red-wins fold permanently red however green the retry was, which is precisely what a retry
 * exists to undo. So the superseded run's row goes and the retry's takes its place, and what the
 * ledger holds for a fold stays "the verdicts still standing" rather than "everything ever said".
 *
 * <p><b>{@code runId} is a key, never a relation.</b> The run row lives in qits-ci's own database;
 * no foreign key can span a context boundary and none is coming. Same for {@code repoId}: rows
 * outlive repositories deliberately, because a verdict about a commit does not stop having happened
 * when the repository is archived.
 *
 * <p><b>A {@link CausedRow} whose cause is set explicitly</b>, from the consumed frame's id, the
 * qits-ci lesson applied: the write happens under the durable funnel's dispatch, and passing the id
 * as data is what survives every thread and restart the ambient scope does not.
 */
@Entity
@Table(name = "commit_build_status")
@EntityListeners(CausationStamp.class)
public class CommitBuildStatus extends PanacheEntityBase implements CausedRow {

  /** qits-ci's run id — the verdict's identity, and what makes a redelivered event an upsert. */
  @Id
  @Column(name = "run_id")
  public String runId;

  /** The repository's storage id, always set. A key, never a relation. */
  @Column(name = "repo_id", nullable = false)
  public String repoId;

  /** The public address pair, when the announcing push carried it. Null on id-addressed pushes. */
  @Column(name = "project_id")
  public String projectId;

  /** The other half of the pair. */
  @Column(name = "repo_name")
  public String repoName;

  /** The branch the run built. Informative — the sha is the coordinate a reader asks by. */
  @Column(name = "branch")
  public String branch;

  /** The commit the verdict is about. */
  @Column(name = "commit_sha", nullable = false)
  public String commitSha;

  /** The terminal status's own word: SUCCESS, FAILED, TIMED_OUT or CONFIG_ERROR today. */
  @Column(name = "status", nullable = false)
  public String status;

  /** When the run finished — the event's {@code occurredAt}, never this row's write time. */
  @Column(name = "finished_at", nullable = false)
  public Instant finishedAt;

  /**
   * The run this one was fired to replace — {@code qits ci retry}'s {@code retryOfRunId}, and null
   * on every run that is not a retry, which is almost all of them.
   *
   * <p><b>It is the lineage and not a relation</b>, the same reasoning {@link #runId} carries one
   * field up: the run it names lives in qits-ci's database, and by the time this row is written the
   * row it names <em>here</em> has normally just been deleted, because a retry supersedes what it
   * re-fires. It is nonetheless stored, and the reason is the only thing it is ever read for: a
   * retry of a retry names the immediately previous run only, so clearing the whole ancestry means
   * walking it, and the walk needs a link on each surviving row to follow. Without the column the
   * ledger could clear one generation and no more, and a red two retries back would hold a release
   * gate down for ever.
   *
   * <p>Null on every row written by a qits-ci that does not carry the field yet, which reads as
   * "not a retry" and behaves exactly as this ledger did before it existed.
   */
  @Column(name = "retry_of_run_id")
  public String retryOfRunId;

  /** The platform's uniform column, never part of any constraint. */
  @Column(name = "causation_id")
  public UUID causationId;

  @Override
  public UUID causationId() {
    return causationId;
  }

  @Override
  public void causationId(UUID id) {
    this.causationId = id;
  }
}
