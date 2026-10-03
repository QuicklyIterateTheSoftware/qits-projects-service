package eu.wohlben.qits.projects.releasehost;

import eu.wohlben.qits.projects.entity.ReleaseRequest;
import io.quarkus.narayana.jta.QuarkusTransaction;
import java.util.List;
import java.util.function.Supplier;

/**
 * <b>Two unreleased requests on one repository</b> — a state the create door no longer makes. Since
 * qits-552 every ask converges on the repository's one open request, but a repository that was
 * accumulating a request per branch before that can still carry several, and the paths that walk
 * "every open request of the repository" (a push to {@code main}, a sibling's release, a pending tag
 * reaching {@code main}) still have to treat each of them as its own fold.
 *
 * <p>So the fixture is made the way such a repository came to be: the first request is parked in a
 * state convergence does not read while the second is asked for, then put back. Both are then made
 * through the real door, with real sources and a real fold each.
 */
final class LegacyOpenRequests {

  private LegacyOpenRequests() {}

  /** The first ask's id, then the second's — two distinct open requests of one repository. */
  static List<String> openTwo(Supplier<String> first, Supplier<String> second) {
    String earlier = first.get();
    ReleaseRequest.State was = setState(earlier, ReleaseRequest.State.WITHDRAWN);
    String later = second.get();
    setState(earlier, was);
    return List.of(earlier, later);
  }

  private static ReleaseRequest.State setState(String id, ReleaseRequest.State state) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              ReleaseRequest row = ReleaseRequest.findById(id);
              ReleaseRequest.State was = row.state;
              row.state = state;
              return was;
            });
  }
}
