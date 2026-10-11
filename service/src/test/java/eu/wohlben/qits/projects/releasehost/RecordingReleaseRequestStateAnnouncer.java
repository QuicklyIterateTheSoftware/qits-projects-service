package eu.wohlben.qits.projects.releasehost;

import eu.wohlben.qits.projects.control.ReleaseRequestStateAnnouncer;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The suite's {@link ReleaseRequestStateAnnouncer} (qits-1153): records each state change. An
 * ordinary bean over the {@code @DefaultBean} announcer, so no test reaches the bus.
 *
 * <p>It also reads the request back in a transaction of its own before recording, as the bus's
 * outbox would write one. That proves two things the real announcer relies on: a transaction can be
 * opened where the announcement is made, and the state it announces is already committed.
 */
@ApplicationScoped
public class RecordingReleaseRequestStateAnnouncer implements ReleaseRequestStateAnnouncer {

  /** One change, and the state a fresh transaction read for the request when it was announced. */
  public record Recorded(StateChange change, String committedState) {}

  private final List<Recorded> recorded = Collections.synchronizedList(new ArrayList<>());

  public List<Recorded> recorded() {
    return List.copyOf(recorded);
  }

  /** The changes of one request, in the order they were announced. */
  public List<StateChange> changesOf(String releaseRequestId) {
    return recorded().stream()
        .map(Recorded::change)
        .filter(change -> change.releaseRequestId().equals(releaseRequestId))
        .toList();
  }

  /** "FROM>TO" for each change of one request — the shortest thing a test can assert on. */
  public List<String> movesOf(String releaseRequestId) {
    return changesOf(releaseRequestId).stream()
        .map(change -> change.previousState() + ">" + change.state())
        .toList();
  }

  public void reset() {
    recorded.clear();
  }

  @Override
  public void onStateChanged(StateChange change) {
    String committed =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    ReleaseRequest.<ReleaseRequest>findByIdOptional(change.releaseRequestId())
                        .map(row -> row.state.name())
                        .orElse(null));
    recorded.add(new Recorded(change, committed));
  }
}
