package eu.wohlben.qits.projects.releasehost;

import eu.wohlben.qits.projects.control.ActiveBuilds;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The suite's {@link ActiveBuilds}: an ordinary bean, so it wins the injection over the {@code
 * @DefaultBean} HTTP adapter. State is read and written through <b>methods</b> — the injected
 * reference is a CDI client proxy, the package convention.
 *
 * <p>Defaults to "zero active runs", which is the answer that lets a verdict-driven test move: the
 * interesting states (runs still active, could not ask) are staged per test.
 */
@ApplicationScoped
public class FakeActiveBuilds implements ActiveBuilds {

  private final AtomicReference<Optional<Integer>> answer =
      new AtomicReference<>(Optional.of(0));

  public void answer(Optional<Integer> value) {
    answer.set(value);
  }

  private final java.util.concurrent.atomic.AtomicBoolean configured =
      new java.util.concurrent.atomic.AtomicBoolean(true);

  /** Whether the probe is pointed at a qits-ci at all — false stages a tier with no ci-url. */
  public void configured(boolean value) {
    configured.set(value);
  }

  /**
   * A latch pair for the NEXT call alone (qits-1133's interleaving test): the call says it has been
   * entered and then waits to be let go. {@code evaluate()} asks this inside its gate transaction,
   * under its row lock and after it has stamped {@code qa_announced_sha}, so a held call is an
   * evaluation caught between its stamp and its commit.
   */
  public record Hold(
      java.util.concurrent.CountDownLatch entered, java.util.concurrent.CountDownLatch release) {}

  private final AtomicReference<Hold> hold = new AtomicReference<>();

  public Hold holdNext() {
    Hold next =
        new Hold(
            new java.util.concurrent.CountDownLatch(1), new java.util.concurrent.CountDownLatch(1));
    hold.set(next);
    return next;
  }

  public void reset() {
    answer.set(Optional.of(0));
    configured.set(true);
    Hold left = hold.getAndSet(null);
    if (left != null) {
      left.release().countDown();
    }
  }

  @Override
  public boolean configured() {
    return configured.get();
  }

  @Override
  public Optional<Integer> activeFor(String repoId, String commitSha) {
    Hold held = hold.getAndSet(null);
    if (held != null) {
      held.entered().countDown();
      try {
        held.release().await(30, java.util.concurrent.TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    return answer.get();
  }
}
