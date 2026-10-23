package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.persistence.ReleaseRequestRepository;
import eu.wohlben.qits.projects.persistence.RepositoryNameRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import org.jboss.logging.Logger;

/**
 * <b>An upstream request moved, so its downstream requests are asked again</b> (epic qits-897, gate
 * 3). When an upstream's QA run changes status ({@code ReleasePipelineRuns.record}), it moves to
 * READY, REJECTED, RELEASED or FAILED ({@code ReleaseRequests.evaluate}, {@code settle}), or its
 * {@code publishState} is written ({@code ReleaseFinalization}), every PENDING request of every
 * repository in its <b>cached</b> closure is evaluated again, so a hold clears (or starts) within
 * seconds rather than at the next sweep.
 *
 * <p><b>Best effort, and the 30-second sweep is the floor.</b> It runs on a thread of its own after
 * the caller's commit — never inside a transaction, never on a bus listener's claim — and swallows
 * and logs every failure. A closure that is not on record triggers nothing: it is never asked for
 * here.
 */
@ApplicationScoped
public class DownstreamReevaluation {

  private static final Logger LOG = Logger.getLogger(DownstreamReevaluation.class);

  @Inject ReleaseRequestRepository requests;

  @Inject RepositoryNameRepository names;

  @Inject UpstreamClosures closures;

  @Inject ReleaseRequests releaseRequests;

  private ExecutorService worker;

  @PostConstruct
  void start() {
    worker = Executors.newVirtualThreadPerTaskExecutor();
  }

  @PreDestroy
  void stop() {
    worker.shutdownNow();
  }

  /**
   * Re-evaluate the requests downstream of one upstream request, in the background. Call it after
   * the commit that moved the upstream; it never throws.
   */
  public void upstreamMoved(String upstreamRequestId) {
    if (upstreamRequestId == null) {
      return;
    }
    try {
      worker.submit(() -> run(upstreamRequestId));
    } catch (RejectedExecutionException e) {
      // Shutting down: the sweep on the next start is the floor.
      LOG.debugf("Not re-evaluating downstream of %s: shutting down", upstreamRequestId);
    }
  }

  private void run(String upstreamRequestId) {
    try {
      List<String> downstream =
          QuarkusTransaction.requiringNew().call(() -> pendingDownstreamOf(upstreamRequestId));
      for (String id : downstream) {
        try {
          releaseRequests.evaluate(id);
        } catch (RuntimeException e) {
          LOG.warnf(
              e,
              "Could not re-evaluate release request %s after its upstream %s moved",
              id,
              upstreamRequestId);
        }
      }
    } catch (RuntimeException e) {
      LOG.warnf(e, "Could not re-evaluate what is downstream of release request %s", upstreamRequestId);
    }
  }

  /** Every PENDING request with a fold in a repository the upstream's cached closure names. */
  private List<String> pendingDownstreamOf(String upstreamRequestId) {
    ReleaseRequest upstream = requests.findByIdOptional(upstreamRequestId).orElse(null);
    if (upstream == null || upstream.projectId == null) {
      return List.of();
    }
    Optional<List<String>> closure = closures.cached(upstream.repoId);
    if (closure.isEmpty() || closure.get().isEmpty()) {
      return List.of();
    }
    Set<String> repoIds = new LinkedHashSet<>();
    for (String name : closure.get()) {
      names
          .findRepositoryByProjectAndName(upstream.projectId, name)
          .ifPresent(repository -> repoIds.add(repository.id));
    }
    repoIds.remove(upstream.repoId);
    return repoIds.stream()
        .flatMap(repoId -> requests.listUnreleasedByRepo(repoId).stream())
        .filter(row -> row.state == ReleaseRequest.State.PENDING && row.mergedSha != null)
        .map(row -> row.id)
        .toList();
  }
}
