package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.entity.ReleasePipelineRun;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.persistence.ReleasePipelineRunRepository;
import eu.wohlben.qits.projects.persistence.ReleasedTagPendingMergeRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * The live position of a release pipeline's phase runs: what qits-ci has said about each run of each
 * phase, recorded from the bus and answered to the release-request API.
 *
 * <p><b>Nothing here decides anything.</b> A release request's gates are settled where they always
 * were, and a row this class writes is read only to say which phase is moving and how far it has
 * got. That is the property that lets this consumption be added without touching the state machine:
 * the whole of it is a mirror, and a mirror that fell behind would cost a surface rather than a
 * release.
 *
 * <p><b>{@code record} opens its own transaction</b>, for {@link BuildStatusLedger}'s measured
 * reason exactly: its caller is a durable bus listener running under the claim transaction, which
 * lives on the <em>eventstream</em> datasource, and one JTA transaction does not take two non-XA
 * datasources. The direction that can go wrong is a claim that commits after this write rolled back
 * — impossible, since a throw here propagates and rolls the claim back too — and a write that
 * commits under a claim that then rolls back, which the run-id upsert makes convergent: the
 * redelivered frame writes the same row again.
 *
 * <h2>Correlating a run to its release request</h2>
 *
 * <p><b>{@code BuildStatusChanged} does not carry a release request id, so this class derives one</b>
 * — and the derivation is not a guess, because each phase's run is anchored at a ref this service
 * itself chose:
 *
 * <ul>
 *   <li><b>QA</b> runs build the request's <em>backing branch</em>, {@code release/<id>}, which is
 *       derived from the request's id and stored nowhere ({@link ReleaseRequest#backingBranchOf}).
 *       The id is therefore literally in the branch name the event carries, and reading it back is
 *       an exact inverse rather than a lookup that can be wrong.
 *   <li><b>Publish</b> runs build the released <em>tag</em>, whose name is the version, and {@code
 *       released_tag_pending_merge} is the row that already joins {@code (repoId, tagName)} to the
 *       request that released it. That is the identical correlation {@code
 *       ReleaseFinalization.onPublishVerdict} makes for the publish gate, reused rather than
 *       re-derived, so the phase row and the gate can never disagree about which request a publish
 *       run belongs to.
 * </ul>
 *
 * <p><b>A {@code releaseRequestId} on the payload wins, always.</b> The field is bound leniently and
 * is absent today; if qits-ci ever carries it, the publisher's own answer is better than any
 * inference here and this class stops inferring for those frames with no further change. That is why
 * the derivation is a fallback rather than the rule.
 *
 * <p><b>A run that correlates to nothing is dropped, quietly.</b> A QA-phase run on a branch that is
 * not a backing branch, and a publish-phase run for a tag no row names, are both ordinary: the first
 * is a phase word on a run this service did not cause, the second is a release made before this
 * table existed. Neither is an error and neither may hold the watermark.
 *
 * <h2>Convergence</h2>
 *
 * <p><b>A frame is applied only when it is strictly newer than the row.</b> The bus is at-least-once
 * and catch-up pages the log, so the frames of one run can be offered out of order; {@link
 * ReleasePipelineRun#updatedAt} is the frame's own {@code occurredAt} and the comparison against it
 * is the whole of the guarantee that a terminal row is never walked backwards into {@code RUNNING}
 * by a late delivery. A frame carrying the same instant as the row is also refused — a redelivery of
 * the frame already applied, which has nothing to add.
 */
@ApplicationScoped
public class ReleasePipelineRuns {

  private static final Logger LOG = Logger.getLogger(ReleasePipelineRuns.class);

  /** qits-ci's word for the QA half of a release — the run a {@code ReleaseRequestChanged} caused. */
  public static final String PHASE_RELEASE_REQUEST = "RELEASE_REQUEST";

  /** qits-ci's word for the publish half — the run an {@code SCMRelease} caused at the tag. */
  public static final String PHASE_RELEASE = "RELEASE";

  /**
   * The statuses that settle a run. Used for one thing only — deciding whether a transition's
   * instant is this run's {@code finishedAt} — and deliberately not a closed judgement about
   * qits-ci's vocabulary: a word not in here simply does not stamp the column, which is the right
   * answer for a status this service has never heard of.
   */
  private static final Set<String> TERMINAL =
      Set.of("SUCCESS", "FAILED", "CANCELLED", "CONFIG_ERROR", "TIMED_OUT");

  /** The word a run reaches when a worker claims it — the one that stamps {@code startedAt}. */
  private static final String RUNNING = "RUNNING";

  @Inject ReleasePipelineRunRepository runs;

  @Inject ReleasedTagPendingMergeRepository pendingTags;

  /**
   * One transition of one run, as the listener hands it over — plain values, no wire types.
   *
   * @param releaseRequestId what the publisher said, or null where it said nothing; see the class
   *     javadoc's "Correlating a run to its release request"
   * @param branch the ref the run built, which is what the correlation falls back to
   * @param occurredAt the frame's own instant: the run row's timestamp for the state it just
   *     reached, which is where {@code startedAt}, {@code finishedAt} and the ordering all come from
   */
  public record Transition(
      String runId,
      String repoId,
      String releaseRequestId,
      String phase,
      String status,
      String branch,
      Instant occurredAt,
      UUID causationId) {}

  /**
   * Record one transition, in a transaction of this datasource's own. Idempotent per run id, and a
   * no-op for a frame that is not newer than the row or that correlates to no request.
   */
  public void record(Transition transition) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              String requestId = correlate(transition);
              if (requestId == null) {
                // Ordinary rather than exceptional: see the class javadoc. DEBUG, because a WARN
                // here would be a line per transition of every run this service did not cause.
                LOG.debugf(
                    "Run %s (%s at %s) names no release request this service knows; not mirrored",
                    transition.runId(), transition.phase(), transition.branch());
                return;
              }
              ReleasePipelineRun row = runs.findById(transition.runId());
              if (row == null) {
                row = new ReleasePipelineRun();
                row.runId = transition.runId();
                row.causationId = transition.causationId();
              } else if (!transition.occurredAt().isAfter(row.updatedAt)) {
                // A frame older than, or equal to, what the row already holds. Applying it would let
                // a catch-up sweep walk a settled run back into RUNNING; a redelivery of the newest
                // frame has nothing to add either. Both are silent no-ops.
                return;
              }
              row.releaseRequestId = requestId;
              row.repoId = transition.repoId();
              row.phase = transition.phase();
              row.status = transition.status();
              row.updatedAt = transition.occurredAt();
              if (RUNNING.equals(transition.status())) {
                row.startedAt = transition.occurredAt();
              }
              if (TERMINAL.contains(transition.status())) {
                row.finishedAt = transition.occurredAt();
              }
              runs.persist(row);
            });
  }

  /**
   * The request this run is a phase of, or null where none can be named.
   *
   * <p>The publisher's own answer first; then the per-phase inverse of the ref this service chose
   * when it asked for the run. Read inside the caller's already-open transaction, because the
   * publish arm is one indexed query on this same datasource.
   */
  private String correlate(Transition transition) {
    if (transition.releaseRequestId() != null && !transition.releaseRequestId().isBlank()) {
      return transition.releaseRequestId();
    }
    String branch = transition.branch();
    if (branch == null || branch.isBlank()) {
      return null;
    }
    if (PHASE_RELEASE_REQUEST.equals(transition.phase())) {
      // The exact inverse of ReleaseRequest.backingBranchOf. A branch that does not carry the
      // prefix is not a fold of ours, whatever phase word rode with it.
      if (!branch.startsWith(ReleaseRequest.BACKING_BRANCH_PREFIX)) {
        return null;
      }
      String id = branch.substring(ReleaseRequest.BACKING_BRANCH_PREFIX.length());
      return id.isBlank() ? null : id;
    }
    if (PHASE_RELEASE.equals(transition.phase())) {
      // The publish gate's own correlation, reused: a release run's branch IS the version, which is
      // the tag name released_tag_pending_merge is keyed on beside the repository.
      return pendingTags
          .find(transition.repoId(), branch)
          .map(tag -> tag.releaseRequestId)
          .orElse(null);
    }
    return null;
  }

  /** Every phase run of one request, newest transition first. Empty means "no phase has run". */
  public List<ReleasePipelineRun> runsOf(String releaseRequestId) {
    return runs.findByRequest(releaseRequestId);
  }

  /**
   * The same for many requests in one query, keyed by request id — the listing's read, where asking
   * per row would be a query per row on the busiest read this service has. A request with no phase
   * run is absent from the map rather than present with an empty list, which is the same distinction
   * the block itself rests on: absent is "nothing has run", never "nothing to run".
   */
  public Map<String, List<ReleasePipelineRun>> runsForEach(Set<String> releaseRequestIds) {
    if (releaseRequestIds.isEmpty()) {
      return Map.of();
    }
    Map<String, List<ReleasePipelineRun>> out = new LinkedHashMap<>();
    for (ReleasePipelineRun row : runs.findByRequests(List.copyOf(releaseRequestIds))) {
      out.computeIfAbsent(row.releaseRequestId, key -> new ArrayList<>()).add(row);
    }
    return out;
  }
}
