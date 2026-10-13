package eu.wohlben.qits.projects.testsupport;

import eu.wohlben.qits.projects.control.BranchListing;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A TEST-SCOPE {@link BranchListing}: the branches each repository has on the git host, as a test
 * staged them, instead of an {@code ls-remote}.
 *
 * <p>{@code @Alternative @Priority} for {@link RecordingWorkspaceAgentDispatch}'s reason — the port
 * returns a result and has a shipped {@code @DefaultBean} ({@code RepositoryBranchListing}) to
 * displace. <b>A repository nobody staged lists no branch</b>, which is what every work item in the
 * suite that is not about this listing expects: its agents' push logs are then the only candidates,
 * as they were before the listing existed. {@link #failFor} scripts the port's other answer, a
 * listing that cannot be read. Nothing in {@code src/main} references this class.
 */
@Alternative
@Priority(1)
@ApplicationScoped
public class RecordingBranchListing implements BranchListing {

  private final Map<String, List<String>> staged = new LinkedHashMap<>();

  private final Map<String, String> failures = new LinkedHashMap<>();

  private final List<String> listed = new ArrayList<>();

  @Override
  public synchronized List<String> branches(String repoId) {
    listed.add(repoId);
    String failure = failures.get(repoId);
    if (failure != null) {
      throw new IllegalStateException(failure);
    }
    return staged.getOrDefault(repoId, List.of());
  }

  /** {@code repoId} lists {@code branches} on the git host, from now until the next reset. */
  public synchronized void willList(String repoId, String... branches) {
    failures.remove(repoId);
    staged.put(repoId, List.of(branches));
  }

  /** Listing {@code repoId} throws with {@code detail}, from now until the next reset. */
  public synchronized void failFor(String repoId, String detail) {
    failures.put(repoId, detail);
  }

  /** Every repository listed since the last reset, in order. */
  public synchronized List<String> listed() {
    return List.copyOf(listed);
  }

  public synchronized void reset() {
    staged.clear();
    failures.clear();
    listed.clear();
  }
}
