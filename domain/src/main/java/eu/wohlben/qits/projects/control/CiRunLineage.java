package eu.wohlben.qits.projects.control;

import java.util.List;
import java.util.Optional;

/**
 * Which run one qits-ci run was fired to replace — asked of qits-ci for a run this service holds
 * no ledger row for (ticket qits-556) — and, since qits-1920, which outputs a red run found stale.
 *
 * <p><b>Why the ledger cannot answer it alone.</b> qits-ci announces {@code BuildSuccessful} /
 * {@code BuildFailed} for terminal runs only, and a run it <em>auto-retried</em> (an infrastructure
 * failure re-fired by qits-ci itself) never announces at all. So a chain red A → auto-retried B →
 * green C reaches {@link BuildStatusLedger} as A's row and C's row, with C naming B and nothing
 * here naming A. The walk up C's ancestry stops at B, A stands, and the release request A rejected
 * stays REJECTED behind a green build. This port is what bridges that gap: B's parent is qits-ci's
 * fact, read off {@code GET /ci/api/runs/{runId}}.
 *
 * <p>A port in the house shape ({@link ActiveBuilds}'): the implementation is {@code
 * service/…/releasehost}, resolved via {@code Instance} with absent supported. <b>Every non-answer
 * is one answer — {@code Optional.empty()}</b>: the run is not a retry, the run is unknown (404),
 * the port is unconfigured, qits-ci is unreachable, the body is unreadable. All of them mean "the
 * walk stops here", which is exactly the behaviour before this port existed, so a failure costs
 * the re-arm and never the verdict. An implementation must not throw and must answer quickly: it is
 * called inside the ledger's write transaction, on the bus consumption.
 */
public interface CiRunLineage {

  /** The run {@code runId} re-fires, or empty where it is none or could not be asked. */
  Optional<String> retryOfRunId(String runId);

  /**
   * The output kinds a red run's QA found stale (qits-1920): the {@code stale outputs: <kind>}
   * highlights ({@link StaleOutputs#HIGHLIGHT}) of its {@value StaleOutputs#REPORT_KIND} report,
   * off qits-ci's {@code GET /ci/api/runs/{runId}/reports} ({@code listRunReports}), in their order.
   *
   * <p>The same contract as {@link #retryOfRunId}: <b>every non-answer is an empty list</b> — no
   * such report, a 404, an unconfigured port, an unreachable qits-ci, an unreadable body — and it
   * never throws. The caller's sentence then reads exactly as it did before qits-1920. Called once
   * per red verdict, before the gate transaction opens.
   */
  List<String> staleOutputKinds(String runId);
}
