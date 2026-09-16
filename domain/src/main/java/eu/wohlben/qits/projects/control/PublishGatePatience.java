package eu.wohlben.qits.projects.control;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * <b>How long a publish gate may stay PENDING before somebody is told.</b> The pure half of {@link
 * ReleaseFinalization}'s "this release run is never going to report" signal, kept out of that class
 * so the decision is testable without a database, a git host or a clock.
 *
 * <p><b>The stall it makes audible was invisible by construction.</b> A released tag whose publish
 * gate stands PENDING for ever holds {@code main} back silently: the sweep's own line is DEBUG,
 * deliberately, because it re-asks about the same row every thirty seconds and an INFO there would
 * be a log nobody reads. So the only evidence was the gate list in a JSON body somebody had to think
 * to fetch, and the live case ran for days that way.
 *
 * <p><b>It says it once per window, never once per sweep.</b> The waiting time is <b>quantized down
 * to a multiple of the patience</b>, and the caller only speaks when the sentence it produces
 * differs from the one already on the row — the idiom {@code ReleaseFinalization.failed} uses for a
 * merge that will not apply. With the shipped hour that is one WARN per hour per stuck release: loud
 * enough to be found, quiet enough to stay readable.
 *
 * <p><b>It is a signal and never a verdict.</b> Nothing here passes a gate, and nothing anywhere
 * else may grow a timeout that does: a publish that is merely slow must keep {@code main} waiting,
 * because the alternative is merging a release whose artifacts were never published. The only thing
 * an expired window changes is who knows about it.
 *
 * <p><b>The clock is {@code released_at}</b>, which is the row's only timestamp from before the
 * gate — there is no {@code publish_pending_since} column and adding one would buy a few seconds of
 * precision for a migration. The gate is stamped on the release path or on the next sweep after it,
 * so the two moments are within a sweep of each other and the answer is a duration in hours.
 */
public final class PublishGatePatience {

  private PublishGatePatience() {}

  /**
   * The sentence to put on a release whose publish run has been owed for longer than {@code
   * patience}, or empty while it is still within the window.
   *
   * <p>The sentence carries the waited time because that is what makes the caller's
   * "say-it-when-it-changes" rule a cadence rather than a single line: it changes once per window
   * and not before.
   *
   * @param patience how long a PENDING publish gate is ordinary; a non-positive value switches the
   *     signal off rather than making every row overdue
   */
  public static Optional<String> overdue(
      String tagName, String repoId, Instant releasedAt, Instant now, Duration patience) {
    if (releasedAt == null || now == null || patience == null || patience.isNegative()
        || patience.isZero()) {
      return Optional.empty();
    }
    Duration elapsed = Duration.between(releasedAt, now);
    if (elapsed.compareTo(patience) < 0) {
      return Optional.empty();
    }
    Duration waited = patience.multipliedBy(elapsed.dividedBy(patience));
    return Optional.of(
        "The release run of "
            + tagName
            + " of "
            + repoId
            + " has not reported for "
            + humanize(waited)
            + "; main is waiting on a publish gate that may never be answered");
  }

  /** A waited time as a person reads it — hours where there are any, minutes below that. */
  public static String humanize(Duration waited) {
    long hours = waited.toHours();
    long minutes = waited.toMinutes() % 60;
    if (hours == 0) {
      return minutes + "m";
    }
    return minutes == 0 ? hours + "h" : hours + "h" + minutes + "m";
  }
}
