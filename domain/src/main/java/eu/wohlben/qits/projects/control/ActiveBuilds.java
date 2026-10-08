package eu.wohlben.qits.projects.control;

import java.util.Optional;

/**
 * How many CI runs are still queued or running for one commit — the half of the build gate the
 * ledger cannot answer, because only terminal runs announce.
 *
 * <p>A port in the house shape: the implementation is {@code service/…/releasehost} (an HTTP read
 * of qits-ci's active-runs listing), resolved via {@code Instance} with absent supported. The
 * gate's reading of every non-answer is the same: <b>{@code Optional.empty()} means "could not
 * ask"</b> — the service unreachable, a non-200, an unreadable answer — and a gate that cannot ask
 * stays pending rather than guessing. An implementation must not throw and must answer quickly; it
 * is called on gate evaluation, which runs on the bus dispatch and the sweep.
 *
 * <p><b>Unconfigured is the one "could not ask" that does not hold</b>, and {@link #configured} is
 * how the gate tells it apart: a tier with no qits-ci address will never get an answer, so holding
 * there would hold every vouched fold for ever. A probe that IS configured and could not answer is a
 * fact about the moment — qits-ci restarting for its own deploy is the ordinary case — and reading it
 * as "nothing in flight" released qits-edge-service 44385ca2 past its own still-running QA run on
 * 2026-10-07 (qits-760).
 */
public interface ActiveBuilds {

  /** Active (queued or running) runs for this commit, or empty when it could not be asked. */
  Optional<Integer> activeFor(String repoId, String commitSha);

  /**
   * Whether this probe is pointed at a qits-ci at all. False only where nothing will ever answer —
   * no address on this tier — which is the one non-answer the gate lets a vouch through. Defaults to
   * true, so an implementation that does not say is treated as one whose silence must hold.
   */
  default boolean configured() {
    return true;
  }
}
