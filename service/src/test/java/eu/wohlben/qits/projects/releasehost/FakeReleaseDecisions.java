package eu.wohlben.qits.projects.releasehost;

import eu.wohlben.qits.projects.control.ReleaseDecisions;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The suite's {@link ReleaseDecisions}: an ordinary bean, so it wins the injection over the {@code
 * @DefaultBean} HTTP adapter and no test reaches a qits-ci. State through <b>methods</b>, the package
 * convention ({@link FakePublishRuns}' shape).
 *
 * <p>It defaults to a <b>failure</b>, for {@link FakePublishRuns}' reason: only a released tree that
 * declares {@code .config/qits/release.yml} reaches this port, which a test stages on purpose, so an
 * unstaged answer shows up as a sentence on {@code detail} instead of a quietly empty list.
 */
@ApplicationScoped
public class FakeReleaseDecisions implements ReleaseDecisions {

  /** One question, as it was asked. */
  public record Asked(String repoId, String version) {}

  private static final Answer UNSTAGED = Answer.failed("FakeReleaseDecisions was not staged");

  private final AtomicReference<Answer> answer = new AtomicReference<>(UNSTAGED);

  private final List<Asked> asked = new CopyOnWriteArrayList<>();

  public void answer(Answer value) {
    answer.set(value);
  }

  public void decisions(Decision... decisions) {
    answer.set(Answer.of(List.of(decisions)));
  }

  public List<Asked> asked() {
    return List.copyOf(asked);
  }

  public void reset() {
    answer.set(UNSTAGED);
    asked.clear();
  }

  @Override
  public Answer of(String repoId, String version) {
    asked.add(new Asked(repoId, version));
    return answer.get();
  }
}
