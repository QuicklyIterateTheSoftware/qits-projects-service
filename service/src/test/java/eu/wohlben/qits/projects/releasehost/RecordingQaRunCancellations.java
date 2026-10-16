package eu.wohlben.qits.projects.releasehost;

import eu.wohlben.qits.projects.control.QaRunCancellations;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The suite's {@link QaRunCancellations}: records who was asked to be cancelled and answers nothing,
 * which is the whole of the port. An ordinary bean over the {@code @DefaultBean} HTTP adapter, so no
 * test reaches a qits-ci; state through methods, the package convention.
 *
 * <p>The assertion this exists for is a <b>count and a scope</b>: one supersession is exactly one
 * cancellation, naming exactly the request whose fold moved. A cancellation naming a sibling — or
 * naming a repository and no request — would be a green build taken away from somebody else.
 *
 * <p>{@code cancelQaRunsOf} is overridden rather than left to the port's default, so a RELEASED
 * cancellation records distinctly from every other call site's "everything" (qits-1264) and a test
 * can assert which one fired.
 */
@ApplicationScoped
public class RecordingQaRunCancellations implements QaRunCancellations {

  public record Cancelled(String repoId, String releaseRequestId, boolean qaOnly) {}

  private final List<Cancelled> calls = Collections.synchronizedList(new ArrayList<>());

  public List<Cancelled> calls() {
    return List.copyOf(calls);
  }

  /** Just the requests named, which is what a scope assertion is about. */
  public List<String> cancelledRequests() {
    return calls().stream().map(Cancelled::releaseRequestId).toList();
  }

  /** The requests cancelled QA-only — the RELEASED call site, and nothing else. */
  public List<String> qaOnlyCancelledRequests() {
    return calls().stream().filter(Cancelled::qaOnly).map(Cancelled::releaseRequestId).toList();
  }

  public void reset() {
    calls.clear();
  }

  @Override
  public void cancelRunsOf(String repoId, String releaseRequestId) {
    calls.add(new Cancelled(repoId, releaseRequestId, false));
  }

  @Override
  public void cancelQaRunsOf(String repoId, String releaseRequestId) {
    calls.add(new Cancelled(repoId, releaseRequestId, true));
  }
}
