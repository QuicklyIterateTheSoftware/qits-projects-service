package eu.wohlben.qits.projects.deploymenthost;

import eu.wohlben.qits.projects.control.DeploymentRequests;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The suite's {@link DeploymentRequests}: an ordinary bean, so it wins the injection over the
 * {@code @DefaultBean} HTTP adapter and no test reaches a qits-deployments. State through
 * <b>methods</b>, the package convention ({@code releasehost/FakePublishRuns}'s shape).
 *
 * <p><b>It defaults to "could not be asked", for {@code FakePublishRuns}' reason.</b> A test that
 * reaches this port without meaning to gets a deployment phase in state {@code UNKNOWN} — visible,
 * and exactly what a real outage would draw — rather than a quietly plausible answer. The thing this
 * port exists to keep apart is "could not see it" from "there is none", so the unstaged answer is
 * the one that says nothing.
 *
 * <p>It records what it was asked, so a test can assert the negative that matters more than any
 * positive here: <b>a list read asks qits-deployments nothing at all.</b>
 */
@ApplicationScoped
public class FakeDeploymentRequests implements DeploymentRequests {

  /** One question, as it was asked. */
  public record Asked(String repoId, String version) {}

  private final AtomicReference<Optional<List<DeploymentRequestView>>> answer =
      new AtomicReference<>(Optional.empty());

  private final List<Asked> asked = new CopyOnWriteArrayList<>();

  /**
   * The with-variants listing (qits-2296), or null while unstaged. <b>Unstaged answers an empty
   * listing</b> — asked, and no main row declaring a variant — rather than "could not be asked":
   * finalization reads it on every {@code DeploymentActive}, and a release with no variants must go
   * live there exactly as every test before qits-2296 expects.
   */
  private final AtomicReference<Optional<List<DeploymentRequestView>>> withVariants =
      new AtomicReference<>();

  private final List<Asked> askedWithVariants = new CopyOnWriteArrayList<>();

  /** What the next answers are — empty being "could not be asked". */
  public void answer(Optional<List<DeploymentRequestView>> value) {
    answer.set(value);
  }

  /** One deployment request in the given status, which is the ordinary staging. */
  public void answerStatus(String id, String status) {
    answer.set(
        Optional.of(
            List.of(new DeploymentRequestView(id, status, Instant.parse("2026-09-16T11:00:00Z")))));
  }

  /** qits-deployments answered, and it has nothing for this version. Not the same as the above. */
  public void answerNothingYet() {
    answer.set(Optional.of(List.of()));
  }

  /** qits-deployments could not be asked at all: unset, unreachable, refused, unreadable. */
  public void answerCouldNotAsk() {
    answer.set(Optional.empty());
  }

  public List<Asked> asked() {
    return List.copyOf(asked);
  }

  /** What the next with-variants reads answer (qits-2296) — empty being "could not be asked". */
  public void answerWithVariants(Optional<List<DeploymentRequestView>> value) {
    withVariants.set(value);
  }

  /** The with-variants reads asked so far. */
  public List<Asked> askedWithVariants() {
    return List.copyOf(askedWithVariants);
  }

  public void reset() {
    answer.set(Optional.empty());
    asked.clear();
    withVariants.set(null);
    askedWithVariants.clear();
  }

  @Override
  public Optional<List<DeploymentRequestView>> forRelease(String repoId, String version) {
    asked.add(new Asked(repoId, version));
    return answer.get();
  }

  @Override
  public Optional<List<DeploymentRequestView>> forReleaseWithVariants(
      String repoId, String version) {
    askedWithVariants.add(new Asked(repoId, version));
    Optional<List<DeploymentRequestView>> staged = withVariants.get();
    return staged == null ? Optional.of(List.of()) : staged;
  }
}
