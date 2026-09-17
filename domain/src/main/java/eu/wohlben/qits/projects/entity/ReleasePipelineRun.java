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
 * Where one qits-ci run of one release phase stands right now — the live read model behind the
 * release pipeline's QA and publish phases, fed from qits-ci's {@code BuildStatusChanged} by {@code
 * service/…/bus/ReleasePipelineRunListener}.
 *
 * <p><b>This is not a verdict and nothing gates on it.</b> A release request's gates are decided
 * where they always were — {@code ReleaseGates}, the {@code commit_build_status} ledger, the
 * approval table and {@code released_tag_pending_merge} — and a row here is read only to say which
 * phase is moving and how far it has got. Deleting this whole table would cost a surface and no
 * decision, which is the property to preserve.
 *
 * <p><b>Why it is not {@link CommitBuildStatus} with two more columns.</b> That ledger is fed from
 * {@code BuildSuccessful}/{@code BuildFailed}, and qits-ci announces on those only when a run is
 * <em>terminal</em> — deliberately, because its reader wants verdicts and must never see a cancelled
 * or superseded run. {@code QUEUED} and {@code RUNNING} are exactly the states a live pipeline view
 * exists to show, so the two readers want opposite halves of the same run and qits-ci publishes a
 * third event for it. Two consumptions, two consumer ids, two tables; folding either into the other
 * would make one reader's policy the other's storage.
 *
 * <p><b>Keyed on the run, not on {@code (request, phase)}.</b> A phase can be re-run — the publish
 * half is retried with {@code qits ci retry} today and gains its own door later — and collapsing the
 * runs here would decide "the newest wins" in the schema. The fold is the DTO's, where it can be
 * changed without a migration.
 *
 * <p><b>{@link #phase} holds qits-ci's word and never the DTO's.</b> {@code RELEASE_REQUEST} and
 * {@code RELEASE} are that service's storage vocabulary, carried on the wire as a plain string
 * because a shared enum would be one context depending on another's model; {@code QA} and {@code
 * PUBLISH} are what a person reads. The translation is {@code ReleasePipelineAssembler}'s, at the
 * read, so a word this service has never heard of is stored honestly and is simply not drawn.
 *
 * <p><b>{@link #startedAt} and {@link #finishedAt} are derived from the TRANSITION.</b> {@code
 * BuildStatusChanged} carries neither: its {@code occurredAt} <em>is</em> the run row's own timestamp
 * for the state it just reached, and it rides the envelope rather than the payload. So the frame
 * that says {@code RUNNING} fills the first and the frame that settles the run fills the second,
 * both from that one instant. Either may stay null — a run first seen at {@code SUCCESS}, which is
 * what a catch-up sweep over a window this process was disconnected for delivers, has no start to
 * invent and must not be given one.
 *
 * <p><b>{@link #updatedAt} is the frame's {@code occurredAt} and it is the ordering fact.</b> The
 * bus is at-least-once and catch-up pages the log, so a {@code QUEUED} frame can be offered after
 * the {@code SUCCESS} frame of the same run. The writer refuses any frame that is not strictly newer
 * than this column, which is what makes the row converge on the newest transition however the frames
 * arrive — a terminal run can never be walked backwards into {@code RUNNING} by a late delivery.
 *
 * <p><b>A {@link CausedRow} whose cause is set explicitly</b>, from the consumed frame's id, for
 * {@link CommitBuildStatus}' reason exactly: the write happens under the durable funnel's dispatch,
 * where the ambient {@code CausationScope} does not stand, so passing the id as data is the only
 * thing that survives the thread hop.
 */
@Entity
@Table(name = "release_pipeline_run")
@EntityListeners(CausationStamp.class)
public class ReleasePipelineRun extends PanacheEntityBase implements CausedRow {

  /** qits-ci's run id — the row's identity, and what makes a redelivered frame an upsert. */
  @Id
  @Column(name = "run_id")
  public String runId;

  /**
   * The release request this run is a phase of. A key and never a relation, although the request row
   * is in this same database: a run is another context's fact and the account of what ran outlives
   * the request, exactly as a build verdict outlives its repository.
   */
  @Column(name = "release_request_id", nullable = false)
  public String releaseRequestId;

  /** The repository's storage id — the other half of every correlation this row takes part in. */
  @Column(name = "repo_id", nullable = false)
  public String repoId;

  /** qits-ci's own phase word: {@code RELEASE_REQUEST} or {@code RELEASE}. See the class javadoc. */
  @Column(name = "phase", nullable = false)
  public String phase;

  /** qits-ci's own status word: QUEUED, RUNNING, SUCCESS, FAILED, CANCELLED, CONFIG_ERROR, TIMED_OUT. */
  @Column(name = "status", nullable = false)
  public String status;

  /** When the run started, from the frame that said {@code RUNNING}. Null until one arrives. */
  @Column(name = "started_at")
  public Instant startedAt;

  /** When the run settled, from the frame that made it terminal. Null while it has not. */
  @Column(name = "finished_at")
  public Instant finishedAt;

  /** The newest transition this row has been told about — the frame's {@code occurredAt}. */
  @Column(name = "updated_at", nullable = false)
  public Instant updatedAt;

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
