package eu.wohlben.qits.projects.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * {@link PublishGatePatience} — a plain unit test, because the decision is a clock and two durations
 * and nothing else. What it pins is the <b>cadence</b>: a stall that never reports must be findable
 * without becoming a log nobody reads, so the sentence has to change once per window and not once
 * per thirty-second sweep.
 */
class PublishGatePatienceTest {

  private static final Instant RELEASED = Instant.parse("2026-09-16T08:00:00Z");

  private static Optional<String> at(String instant, Duration patience) {
    return PublishGatePatience.overdue(
        "2026.916.120000", "repo-1", RELEASED, Instant.parse(instant), patience);
  }

  @Test
  void nothingIsSaidInsideTheWindow() {
    assertEquals(Optional.empty(), at("2026-09-16T08:59:59Z", Duration.ofHours(1)));
  }

  @Test
  void theWindowElapsingIsWhatSpeaks() {
    Optional<String> said = at("2026-09-16T09:00:00Z", Duration.ofHours(1));
    assertTrue(said.isPresent());
    assertTrue(said.get().contains("2026.916.120000"), said.get());
    assertTrue(said.get().contains("repo-1"), said.get());
    assertTrue(said.get().contains("1h"), said.get());
  }

  /**
   * The cadence itself: every sweep inside one window produces the <b>identical</b> sentence, which
   * is what {@code ReleaseFinalization} compares against the row to stay quiet, and the next window
   * produces a different one, which is what makes it speak again.
   */
  @Test
  void theSentenceChangesOncePerWindowAndNotOncePerSweep() {
    Duration patience = Duration.ofHours(1);
    String first = at("2026-09-16T09:00:30Z", patience).orElseThrow();
    assertEquals(first, at("2026-09-16T09:30:00Z", patience).orElseThrow(), "same window");
    assertEquals(first, at("2026-09-16T09:59:59Z", patience).orElseThrow(), "still the same window");

    String second = at("2026-09-16T10:00:00Z", patience).orElseThrow();
    assertTrue(second.contains("2h"), second);
    assertTrue(!second.equals(first), "the next window is a new sentence, so it is said again");
  }

  /** A shorter patience is a shorter window, and the waited time is spelled in minutes below an hour. */
  @Test
  void aShortWindowReadsInMinutes() {
    assertEquals(Optional.empty(), at("2026-09-16T08:14:00Z", Duration.ofMinutes(15)));
    assertTrue(at("2026-09-16T08:16:00Z", Duration.ofMinutes(15)).orElseThrow().contains("15m"));
    assertTrue(at("2026-09-16T08:31:00Z", Duration.ofMinutes(15)).orElseThrow().contains("30m"));
  }

  /** Off is a supported configuration and is never "everything is overdue". */
  @Test
  void aZeroOrNegativePatienceSwitchesTheSignalOff() {
    assertEquals(Optional.empty(), at("2026-09-17T08:00:00Z", Duration.ZERO));
    assertEquals(Optional.empty(), at("2026-09-17T08:00:00Z", Duration.ofHours(-1)));
  }

  /** A row with no timestamp to measure from is not overdue; it is unmeasurable. */
  @Test
  void aRowWithNoReleaseInstantSaysNothing() {
    assertEquals(
        Optional.empty(),
        PublishGatePatience.overdue("tag", "repo-1", null, Instant.now(), Duration.ofHours(1)));
  }

  @Test
  void theWaitedTimeReadsTheWayAPersonWouldSayIt() {
    assertEquals("45m", PublishGatePatience.humanize(Duration.ofMinutes(45)));
    assertEquals("2h", PublishGatePatience.humanize(Duration.ofHours(2)));
    assertEquals("2h30m", PublishGatePatience.humanize(Duration.ofMinutes(150)));
  }
}
