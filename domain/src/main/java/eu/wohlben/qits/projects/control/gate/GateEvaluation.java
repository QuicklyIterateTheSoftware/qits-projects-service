package eu.wohlben.qits.projects.control.gate;

import eu.wohlben.qits.projects.control.ReleaseGates;
import java.util.List;

/**
 * What one gate says about one request.
 *
 * @param state the gate's state
 * @param detail one sentence, or null
 * @param checks the named parts of the answer; empty where the gate has none
 * @param runId the qits-ci run the answer rests on, or null
 * @param link where a person reads more, or null
 */
public record GateEvaluation(
    ReleaseGates.State state, String detail, List<GateCheck> checks, String runId, String link) {

  public GateEvaluation {
    checks = checks == null ? List.of() : List.copyOf(checks);
  }

  public static GateEvaluation of(ReleaseGates.State state, String detail) {
    return new GateEvaluation(state, detail, List.of(), null, null);
  }
}
