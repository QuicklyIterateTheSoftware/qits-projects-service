package eu.wohlben.qits.projects.releasehost;

import eu.wohlben.qits.projects.control.PublishRuns;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The suite's {@link PublishRuns}: an ordinary bean, so it wins the injection over the {@code
 * @DefaultBean} HTTP adapter and no test reaches a qits-ci. State through <b>methods</b>, the
 * package convention ({@link FakeActiveBuilds}'s shape).
 *
 * <p><b>It defaults to "could not ask", unlike {@link FakeActiveBuilds}</b>, and the difference is
 * deliberate: this port is only ever reached by a released tree that declares {@code
 * .config/qits/release.yml}, which is a thing a test stages on purpose, and an unstaged answer
 * merges nothing at all. So a test that reaches this port without meaning to fails visibly instead
 * of quietly finalizing a release on a gate nobody asked about — which is the exact defect the port
 * exists to close.
 *
 * <p>It records what it was asked, so a test can assert the negative that matters more than any
 * positive: <b>a tree carrying its own release recipe asks qits-ci nothing.</b>
 */
@ApplicationScoped
public class FakePublishRuns implements PublishRuns {

  /** One question, as it was asked. */
  public record Asked(String repoId, String rev) {}

  private final AtomicReference<Optional<Boolean>> answer = new AtomicReference<>(Optional.empty());

  private final List<Asked> asked = new CopyOnWriteArrayList<>();

  /** What the next answers are: declared, not declared, or "could not ask". */
  public void answer(Optional<Boolean> value) {
    answer.set(value);
  }

  public List<Asked> asked() {
    return List.copyOf(asked);
  }

  public void reset() {
    answer.set(Optional.empty());
    asked.clear();
  }

  @Override
  public Optional<Boolean> declaredFor(String repoId, String rev) {
    asked.add(new Asked(repoId, rev));
    return answer.get();
  }
}
