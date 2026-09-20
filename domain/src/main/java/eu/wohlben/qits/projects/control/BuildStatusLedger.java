package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.dto.CommitBuildStatusDto;
import eu.wohlben.qits.projects.entity.CommitBuildStatus;
import eu.wohlben.qits.projects.persistence.CommitBuildStatusRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The per-commit build-status ledger: what qits-ci has said about each commit, recorded from the
 * bus and answered to the repositories API.
 *
 * <p>This is the foundation half of the release-quality-gates work, and that work landed: a release
 * request's CI gate reads {@link #verdictsOf} in {@code ReleaseRequests.evaluate}, and the listing
 * reports every request's CI gate out of {@link #verdictsForEach}. That is the reason the ledger
 * lives in this service at all, beside the repository aggregate and the request state machine,
 * rather than in the git host.
 *
 * <p><b>{@code record} opens its own transaction</b>, because its caller is a durable bus listener
 * running under the claim transaction — which lives on the <em>eventstream</em> datasource, and one
 * JTA transaction does not take two non-XA datasources (qits-ci measured it as {@code Enlisted
 * connection used without active transaction}; qits-deployments' subscriber makes the same
 * arrangement). The direction that can go wrong is a claim that commits after this write rolled
 * back — impossible, since a throw here propagates and rolls the claim back too — and a write that
 * commits under a claim that then rolls back, which the run-id upsert makes convergent: the
 * redelivered event writes the same row again.
 */
@ApplicationScoped
public class BuildStatusLedger {

  @Inject CommitBuildStatusRepository statuses;

  /**
   * One run's terminal verdict, as the listener hands it over — plain values, no wire types.
   *
   * @param retryOfRunId the run this one was fired to replace, or null where it is not a retry. See
   *     {@link #record}: it is what makes a retry an ANSWER to the run it re-fires rather than a
   *     second opinion beside it.
   */
  public record Verdict(
      String runId,
      String repoId,
      String projectId,
      String repoName,
      String branch,
      String commitSha,
      String status,
      Instant finishedAt,
      UUID causationId,
      String retryOfRunId) {}

  /**
   * How far back a retry chain is walked. Not a policy — a stop: the links are qits-ci's run ids
   * and a loop among them cannot be constructed by any path this service knows about, so the bound
   * is there so that a corrupted one costs a truncated walk rather than a consumption that never
   * returns. The {@code visited} set below is the real cycle guard; this is the belt under it.
   */
  private static final int ANCESTRY_LIMIT = 64;

  /**
   * Record one verdict, in a transaction of this datasource's own. Idempotent per run id, and
   * answers the run ids this verdict <b>superseded</b> — empty for the ordinary build, which is
   * almost every one.
   *
   * <p><b>A retry is an answer, not a second opinion (ticket qits-309).</b> {@code qits ci retry}
   * mints a new run at the same fold carrying {@code retryOfRunId}, and every reader of this table
   * folds a commit's rows with any-red-wins. Leaving the re-fired run's row behind therefore makes
   * a green retry change nothing at all: the CI gate goes on reading FAILED for a fold whose build
   * has since passed, with no push available to answer it. So the superseded row is deleted as the
   * retry's is written.
   *
   * <p><b>The whole ancestry goes, not one generation.</b> qits-ci chains retries — {@code
   * retryOfRunId} names the <em>immediately</em> previous run only — so red → red → green is a
   * three-link chain and clearing only the middle link would leave the first red standing and the
   * gate down. The walk follows {@link CommitBuildStatus#retryOfRunId} from row to row.
   *
   * <p><b>Why the link is stored at all, since the row it names is normally gone.</b> That is the
   * alternative considered and rejected: in the ordinary in-order case each write deletes its
   * predecessor, so the ancestry is exactly one row deep and no column is needed to walk it. It is
   * rejected because "exactly one row deep" is an invariant of a delivery order nothing guarantees.
   * A redelivered frame, or a frame this service was down for and caught up out of step with its
   * neighbours, leaves a gap, and a walk with no link to follow stops at the gap and leaves
   * everything behind it red for ever. One nullable column buys an ancestry that survives that; the
   * alternative that would also survive it — a table of supersessions, or a graph of runs — is a
   * store for a relation that is a single parent pointer, which is a column.
   *
   * <p><b>And the same write refuses to resurrect what a retry already answered.</b> If some row
   * already names the arriving run as the run <em>it</em> superseded, this verdict has been answered
   * and is not persisted: see {@link CommitBuildStatusRepository#isSuperseded}. That is what makes
   * the outcome independent of arrival order rather than merely correct in order.
   *
   * @return the run ids this verdict superseded — the whole ancestry, including any whose row was
   *     already gone, because "superseded by this run" is a fact about the lineage and not about
   *     what happened to be in the table. {@code ReleaseRequests.onVerdict} is what reads it: a
   *     request rejected by one of those runs has had its rejection answered.
   */
  public Set<String> record(Verdict verdict) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              if (statuses.isSuperseded(verdict.runId())) {
                // Already answered by a retry. Writing it again would put a verdict back that the
                // retry removed on purpose, and on the commonest shape that is a green gate going
                // red with nothing to push.
                return Set.<String>of();
              }
              Set<String> superseded = supersede(verdict.retryOfRunId());
              CommitBuildStatus row = new CommitBuildStatus();
              row.runId = verdict.runId();
              row.repoId = verdict.repoId();
              row.projectId = verdict.projectId();
              row.repoName = verdict.repoName();
              row.branch = verdict.branch();
              row.commitSha = verdict.commitSha();
              row.status = verdict.status();
              row.finishedAt = verdict.finishedAt();
              row.causationId = verdict.causationId();
              row.retryOfRunId = verdict.retryOfRunId();
              statuses.put(row);
              return superseded;
            });
  }

  /**
   * Delete the run named and everything it in turn superseded, answering the ids walked. A missing
   * row is not a stop-and-fail: it is either a run this ledger never saw or one an earlier retry
   * already cleared, and both mean "nothing left to remove here" — but the walk cannot follow a link
   * it has no row to read, which is the gap the {@code isSuperseded} check exists to make harmless.
   */
  private Set<String> supersede(String retryOfRunId) {
    Set<String> walked = new LinkedHashSet<>();
    String runId = retryOfRunId;
    while (runId != null && walked.size() < ANCESTRY_LIMIT && walked.add(runId)) {
      CommitBuildStatus ancestor = statuses.findById(runId);
      if (ancestor == null) {
        return walked;
      }
      runId = ancestor.retryOfRunId;
      statuses.delete(ancestor);
    }
    return walked;
  }

  /**
   * Whether one run's verdict is still standing — i.e. whether its row is still in this table.
   *
   * <p>Absent means superseded, and it means nothing else: {@link #record} is the only writer and a
   * retry is the only thing that deletes. That is what lets {@code ReleaseRequests.sweep} ask of a
   * REJECTED request "is the run that rejected it still saying so?" without holding a copy of the
   * lineage on the request row.
   */
  public boolean stands(String runId) {
    return runId != null && statuses.findById(runId) != null;
  }

  /**
   * The same, for many {@code (repoId, commitSha)} pairs in one query, keyed by that pair. The
   * release-request listing reports each request's CI gate and would otherwise ask per row.
   *
   * <p>The key carries the repository as well as the sha even though two repositories sharing a
   * commit sha is vanishingly unlikely: the single read's contract is a pair, and a batched form
   * that quietly answered on the sha alone would be a second, laxer correlation rule in a class
   * whose whole subject is correlation.
   */
  public Map<VerdictKey, List<CommitBuildStatusDto>> verdictsForEach(Set<VerdictKey> keys) {
    if (keys.isEmpty()) {
      return Map.of();
    }
    Map<VerdictKey, List<CommitBuildStatusDto>> out = new LinkedHashMap<>();
    for (CommitBuildStatus row :
        statuses.findByCommits(keys.stream().map(VerdictKey::commitSha).distinct().toList())) {
      VerdictKey key = new VerdictKey(row.repoId, row.commitSha);
      if (!keys.contains(key)) {
        continue;
      }
      out.computeIfAbsent(key, k -> new ArrayList<>())
          .add(new CommitBuildStatusDto(row.runId, row.status, row.branch, row.finishedAt));
    }
    return out;
  }

  /** One commit of one repository — what a verdict is correlated to, in both directions. */
  public record VerdictKey(String repoId, String commitSha) {}

  /** Every verdict for one commit, newest run first. Empty means "no verdict yet", not "no run". */
  public List<CommitBuildStatusDto> verdictsOf(String repoId, String commitSha) {
    return statuses.findByCommit(repoId, commitSha).stream()
        .map(row -> new CommitBuildStatusDto(row.runId, row.status, row.branch, row.finishedAt))
        .toList();
  }
}
