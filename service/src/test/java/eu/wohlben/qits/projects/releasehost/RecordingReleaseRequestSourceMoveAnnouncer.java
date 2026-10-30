package eu.wohlben.qits.projects.releasehost;

import eu.wohlben.qits.projects.control.ReleaseRequestSourceMoveAnnouncer;
import eu.wohlben.qits.projects.entity.ReleaseRequestSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The suite's {@link ReleaseRequestSourceMoveAnnouncer} (qits-1665): records each move. An ordinary
 * bean over the {@code @DefaultBean} announcer, so no test reaches the bus.
 *
 * <p>It reads where the moved source stands in a transaction of its own before recording, as the
 * bus's outbox would write one: that proves the move is committed when it is announced.
 */
@ApplicationScoped
public class RecordingReleaseRequestSourceMoveAnnouncer implements ReleaseRequestSourceMoveAnnouncer {

  /** One move, and whether a fresh transaction found the source on its target when announced. */
  public record Recorded(SourceMove move, boolean committed) {}

  private final List<Recorded> recorded = Collections.synchronizedList(new ArrayList<>());

  public List<Recorded> recorded() {
    return List.copyOf(recorded);
  }

  /** The moves out of one request, in the order they were announced. */
  public List<SourceMove> movesFrom(String requestId) {
    return recorded().stream()
        .map(Recorded::move)
        .filter(move -> move.fromRequestId().equals(requestId))
        .toList();
  }

  public void reset() {
    recorded.clear();
  }

  @Override
  public void onSourceMoved(SourceMove move) {
    boolean committed =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    ReleaseRequestSource.count(
                            "requestId = ?1 and name = ?2", move.toRequestId(), move.branch())
                        > 0);
    recorded.add(new Recorded(move, committed));
  }
}
