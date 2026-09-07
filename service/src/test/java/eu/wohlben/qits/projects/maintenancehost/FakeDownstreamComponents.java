package eu.wohlben.qits.projects.maintenancehost;

import eu.wohlben.qits.projects.control.DownstreamComponents;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * The suite's {@link DownstreamComponents}: an ordinary bean, which beats the {@code @DefaultBean}
 * {@link HttpDownstreamComponents} at the port's injection point, so no test dials qits-maintenance.
 *
 * <p><b>It answers "could not ask" until a test says otherwise</b>, which is deliberately the posture
 * every existing suite then runs in: the announcement carries a null closure, the event carries no
 * key, and nothing about the flow moves for the tests that are not about this field.
 */
@ApplicationScoped
public class FakeDownstreamComponents implements DownstreamComponents {

  /** One question, as the port declares it — so a test can assert what was asked and with what. */
  public record Asked(String repoId, String repoName) {}

  private final List<Asked> asked = Collections.synchronizedList(new ArrayList<>());

  private volatile Optional<List<String>> answer = Optional.empty();

  /** What the next lookups answer. {@code empty()} is "could not ask"; {@code of([])} is a leaf. */
  public void answer(Optional<List<String>> answer) {
    this.answer = answer;
  }

  public List<Asked> asked() {
    return List.copyOf(asked);
  }

  public void reset() {
    asked.clear();
    answer = Optional.empty();
  }

  @Override
  public Optional<List<String>> downstreamOf(String repoId, String repoName) {
    asked.add(new Asked(repoId, repoName));
    return answer;
  }
}
