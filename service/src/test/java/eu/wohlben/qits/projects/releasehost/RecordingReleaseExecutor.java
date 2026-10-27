package eu.wohlben.qits.projects.releasehost;

import eu.wohlben.qits.projects.control.ReleaseExecutor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The suite's {@link ReleaseExecutor}: records what a READY request asked for and answers what the
 * test scripted. An ordinary bean over the {@code @DefaultBean} {@link GitHostReleaseExecutor}, so
 * it wins the injection simply by existing; state through methods, the package convention.
 *
 * <p><b>{@link #passThrough()} is the exception that makes the whole flow assertable.</b> Winning
 * the injection is what lets every state-machine test script an outcome without a git host — and it
 * is also what would leave the real executor reachable by nothing but a direct call, with {@code
 * ReleaseRequests}' half of the release (the pending-merge row, RELEASED, the siblings' re-fold)
 * never proved to run behind it. So the fake can delegate: the real executor is injected by its own
 * type, past its {@code @DefaultBean}, and a test that wants the whole chain turns it on. The
 * recording still happens, so the ask is assertable either way.
 */
@ApplicationScoped
public class RecordingReleaseExecutor implements ReleaseExecutor {

  /** One release, exactly as the domain asked for it. */
  public record Released(
      String requestId,
      String repoId,
      String projectId,
      String repoName,
      String branch,
      String expectedSha,
      String summary,
      String requester,
      List<String> namedSources,
      String defaultBranch,
      String priority) {}

  /** By its OWN type, past the {@code @DefaultBean} this bean is currently beating. */
  @Inject GitHostReleaseExecutor real;

  private final List<Released> calls = Collections.synchronizedList(new ArrayList<>());
  private final AtomicReference<Outcome> outcome =
      new AtomicReference<>(Outcome.released("2026.831.90000", "released-sha-0"));
  private final AtomicBoolean delegate = new AtomicBoolean(false);

  /**
   * A release that parks the worker inside {@link #release} until the test lets it go — the window
   * the real executor spends tagging outside every lock (~20 s for the wrapper's pin resolution),
   * drawn as wide as a test needs to join a branch or move one in it (qits-1615).
   *
   * @param entered counted down when the release arrives, so the test knows the worker is parked
   * @param released what the parked release waits on
   */
  public record Hold(CountDownLatch entered, CountDownLatch released) {

    /** Wait for the held release to arrive; false if it never did within the bound. */
    public boolean awaitEntered() throws InterruptedException {
      return entered.await(10, TimeUnit.SECONDS);
    }

    public void release() {
      released.countDown();
    }
  }

  private final AtomicReference<Hold> hold = new AtomicReference<>();

  public List<Released> calls() {
    return List.copyOf(calls);
  }

  public void answer(Outcome value) {
    outcome.set(value);
  }

  /** Stop scripting: the shipped executor answers, against whatever git host the test staged. */
  public void passThrough() {
    delegate.set(true);
  }

  /** Park the next release until {@link Hold#release} — one release, then the hold is spent. */
  public Hold holdNextRelease() {
    Hold armed = new Hold(new CountDownLatch(1), new CountDownLatch(1));
    hold.set(armed);
    return armed;
  }

  public void reset() {
    Hold armed = hold.getAndSet(null);
    if (armed != null) {
      armed.release();
    }
    calls.clear();
    outcome.set(Outcome.released("2026.831.90000", "released-sha-0"));
    delegate.set(false);
  }

  @Override
  public Outcome release(Release release) {
    calls.add(
        new Released(
            release.requestId(),
            release.repoId(),
            release.projectId(),
            release.repoName(),
            release.backingBranch(),
            release.mergedSha(),
            release.summary(),
            release.requester(),
            release.namedSources(),
            release.defaultBranch(),
            release.priority()));
    Hold armed = hold.getAndSet(null);
    if (armed != null) {
      armed.entered().countDown();
      try {
        if (!armed.released().await(30, TimeUnit.SECONDS)) {
          return Outcome.refusedRetryable("the test never let the held release go");
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return Outcome.refusedRetryable("interrupted while held");
      }
    }
    return delegate.get() ? real.release(release) : outcome.get();
  }

  /**
   * The second call, delegated the same way: a scripted release consumed nothing on any git host,
   * and a passed-through one deletes exactly what the shipped executor would.
   */
  @Override
  public java.util.Set<String> deleteConsumedBranches(
      Release release, java.util.Map<String, String> heads) {
    return delegate.get() ? real.deleteConsumedBranches(release, heads) : java.util.Set.of();
  }
}
