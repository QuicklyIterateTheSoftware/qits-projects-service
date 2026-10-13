package eu.wohlben.qits.projects.control.gate;

import eu.wohlben.qits.projects.control.ReleaseGates;
import eu.wohlben.qits.projects.entity.ReleaseRequest;

/**
 * <b>The fold as the cause of a rejection</b> (qits-1221): a request whose sources the git host
 * could not fold because one of them does not exist ({@code 404 no-such-source}) is REJECTED with
 * {@code rejecting_gate = fold}, beside {@link AutomationsGate#KIND} (qits-1199).
 *
 * <p><b>Not a {@link ReleaseGate}</b>, deliberately: the fold is what every gate judges, not one of
 * them, so it is no entry on the gate list and is not discovered. What it changes is what the gates
 * that are on the list say: none of them can pass or wait for content that could not be made, so
 * {@link ReleaseGateEvaluator} reports every applying gate that has not itself FAILED as FAILED with
 * the fold's sentence — instead of "Nothing is folded yet" and "Waiting for the automations to
 * answer", which read as a request still in progress.
 */
public final class FoldFailure {

  /** The {@code rejecting_gate} of a request whose sources could not be folded. */
  public static final String KIND = "fold";

  private FoldFailure() {}

  /** Whether {@code row} stands REJECTED because its sources could not be folded. */
  public static boolean rejected(ReleaseRequest row) {
    return row != null
        && row.state == ReleaseRequest.State.REJECTED
        && KIND.equals(row.rejectingGate);
  }

  /**
   * What an applying gate says about a request the fold rejected: its own FAILED stands (a red
   * build is still a red build); anything else is the fold's failure, in the request's own sentence.
   */
  static GateEvaluation answer(GateSubject subject, GateEvaluation said) {
    if (!rejected(subject.request()) || said.state() == ReleaseGates.State.FAILED) {
      return said;
    }
    String detail = subject.request().detail;
    return new GateEvaluation(
        ReleaseGates.State.FAILED,
        detail == null ? "The sources could not be folded" : detail,
        said.checks(),
        said.runId(),
        said.link());
  }
}
