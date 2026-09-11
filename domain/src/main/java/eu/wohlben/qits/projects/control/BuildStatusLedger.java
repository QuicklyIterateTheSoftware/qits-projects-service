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

  /** One run's terminal verdict, as the listener hands it over — plain values, no wire types. */
  public record Verdict(
      String runId,
      String repoId,
      String projectId,
      String repoName,
      String branch,
      String commitSha,
      String status,
      boolean gating,
      Instant finishedAt,
      UUID causationId) {}

  /** Record one verdict, in a transaction of this datasource's own. Idempotent per run id. */
  public void record(Verdict verdict) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              CommitBuildStatus row = new CommitBuildStatus();
              row.runId = verdict.runId();
              row.repoId = verdict.repoId();
              row.projectId = verdict.projectId();
              row.repoName = verdict.repoName();
              row.branch = verdict.branch();
              row.commitSha = verdict.commitSha();
              row.status = verdict.status();
              row.gating = verdict.gating();
              row.finishedAt = verdict.finishedAt();
              row.causationId = verdict.causationId();
              statuses.put(row);
            });
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
          .add(
              new CommitBuildStatusDto(
                  row.runId, row.status, row.branch, row.gating, row.finishedAt));
    }
    return out;
  }

  /** One commit of one repository — what a verdict is correlated to, in both directions. */
  public record VerdictKey(String repoId, String commitSha) {}

  /** Every verdict for one commit, newest run first. Empty means "no verdict yet", not "no run". */
  public List<CommitBuildStatusDto> verdictsOf(String repoId, String commitSha) {
    return statuses.findByCommit(repoId, commitSha).stream()
        .map(
            row ->
                new CommitBuildStatusDto(
                    row.runId, row.status, row.branch, row.gating, row.finishedAt))
        .toList();
  }
}
