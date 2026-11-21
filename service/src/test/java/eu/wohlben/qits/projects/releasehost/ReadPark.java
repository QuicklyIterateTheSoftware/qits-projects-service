package eu.wohlben.qits.projects.releasehost;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * <b>Every read that reaches a fake while this is armed waits here until {@link #release}</b> — the
 * tree reads of {@link RecordingReleaseGitHost#parkTreeReads} and the fold reads of {@link
 * RecordingFoldChanges#parkReads}. Unlike {@link RecordingReleaseGitHost.Hold}, which parks one read
 * of one repository, a park takes every read of every repository for as long as it stands.
 *
 * <p>It is how a test proves a caller did <em>not</em> wait for a read, independent of how fast the
 * machine is: while the reads are parked, a caller that waited for one cannot finish, so it fails by
 * timeout rather than by a stopwatch. The reads are counted per repository <b>as they arrive</b>,
 * before they block — a parked read has not answered yet, so nothing else the fake records sees it.
 *
 * <p>A parked read gives up after {@link #GIVE_UP_SECONDS} and goes on as if released, so a park a
 * test forgot can slow the suite down but never hang it.
 */
public final class ReadPark {

  /** How long a parked read waits before it goes on regardless. */
  public static final long GIVE_UP_SECONDS = 30;

  private final CountDownLatch released = new CountDownLatch(1);

  private final Map<String, AtomicInteger> arrived = new ConcurrentHashMap<>();

  /** Count a read of {@code repoId} as arrived, then wait for the release (or the give-up). */
  void park(String repoId) {
    arrived.computeIfAbsent(repoId, key -> new AtomicInteger()).incrementAndGet();
    try {
      released.await(GIVE_UP_SECONDS, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** How many reads of {@code repoId} have arrived since the park was armed, released or not. */
  public int arrived(String repoId) {
    AtomicInteger count = arrived.get(repoId);
    return count == null ? 0 : count.get();
  }

  /**
   * Wait until {@code condition} holds of {@link #arrived}; an {@link AssertionError} naming {@code
   * what} if it does not within ten seconds.
   */
  public void awaitArrived(Predicate<ReadPark> condition, String what)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + 10_000;
    while (!condition.test(this)) {
      if (System.currentTimeMillis() > deadline) {
        throw new AssertionError(
            "timed out waiting for parked reads: " + what + "; arrived " + arrived);
      }
      Thread.sleep(20);
    }
  }

  /** Let every parked read go on, and every later one through at once. Idempotent. */
  public void release() {
    released.countDown();
  }
}
