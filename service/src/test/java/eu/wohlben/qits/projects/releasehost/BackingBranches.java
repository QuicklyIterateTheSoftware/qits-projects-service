package eu.wohlben.qits.projects.releasehost;

import eu.wohlben.qits.projects.entity.ReleaseRequest;
import io.quarkus.narayana.jta.QuarkusTransaction;

/**
 * The stored backing branch of a request (qits-1158), for tests that used to compose {@code
 * release/<id>}: a request made now folds onto {@code release/<qualified id>}.
 */
final class BackingBranches {

  private BackingBranches() {}

  /** {@code release/<qualified id>}, read off the row. */
  static String of(String requestId) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                ReleaseRequest.<ReleaseRequest>findByIdOptional(requestId)
                    .map(ReleaseRequest::backingBranch)
                    .orElseThrow(() -> new AssertionError("no release request " + requestId)));
  }

  /** The request's qualified id, {@code <repo>-rr-<n>}. */
  static String qualifiedIdOf(String requestId) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                ReleaseRequest.<ReleaseRequest>findByIdOptional(requestId)
                    .map(row -> row.qualifiedId)
                    .orElseThrow(() -> new AssertionError("no release request " + requestId)));
  }

  /**
   * The backing branch of the newest request of {@code repoId} whose current fold is {@code
   * mergedSha} — the branch qits-ci builds that request's QA run on, so a verdict a test announces
   * for a fold is the request's OWN run and the CI gate reads it (qits-1266). A sha no request is
   * folded at answers {@code work}, an ordinary branch nobody's gate counts.
   */
  static String atFold(String repoId, String mergedSha) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                ReleaseRequest.<ReleaseRequest>find(
                        "repoId = ?1 and mergedSha = ?2 order by createdAt desc",
                        repoId,
                        mergedSha)
                    .firstResultOptional()
                    .map(ReleaseRequest::backingBranch)
                    .orElse("work"));
  }

  /** {@code refs/heads/<backing branch>}. */
  static String ref(String requestId) {
    return "refs/heads/" + of(requestId);
  }
}
