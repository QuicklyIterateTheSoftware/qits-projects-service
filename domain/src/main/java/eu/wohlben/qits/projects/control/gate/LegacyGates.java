package eu.wohlben.qits.projects.control.gate;

import eu.wohlben.qits.projects.control.ReleaseGates;
import java.util.ArrayList;
import java.util.List;

/**
 * The deprecated {@code gates[]} and {@code pipeline.gates[]} lists, made from the gate classes'
 * answer. They carry only the kinds {@link ReleaseGates.Kind} names, in its order.
 *
 * <p><b>{@code DEPLOYMENT} has no class.</b> It always meant "the released tag reached {@code
 * main}", which is the finalize step rather than a gate; {@link DeploymentRollbackGate} is the
 * deployment's gate now. So this class keeps the old rule for the old lists: configured at {@code
 * main} (or {@code UNKNOWN} with the rest of an unreadable set), PASSED once the tag merged,
 * PENDING until then.
 */
public final class LegacyGates {

  private LegacyGates() {}

  public static List<ReleaseGates.Gate> of(
      List<ReleaseGateEvaluator.Evaluated> evaluated, GateSubject subject) {
    List<ReleaseGates.Gate> gates = new ArrayList<>();
    for (ReleaseGateEvaluator.Evaluated answer : evaluated) {
      ReleaseGateEvaluator.legacyKind(answer.gate().kind())
          .filter(kind -> kind != ReleaseGates.Kind.DEPLOYMENT)
          .ifPresent(kind -> gates.add(new ReleaseGates.Gate(kind, answer.evaluation().state())));
    }
    ReleaseGates.GateSet set = subject.gateSet();
    if (!set.known()) {
      gates.add(new ReleaseGates.Gate(ReleaseGates.Kind.DEPLOYMENT, ReleaseGates.State.UNKNOWN));
    } else if (set.requires(ReleaseGates.Kind.DEPLOYMENT)) {
      boolean merged = subject.released() != null && subject.released().mergedAt != null;
      gates.add(
          new ReleaseGates.Gate(
              ReleaseGates.Kind.DEPLOYMENT,
              merged ? ReleaseGates.State.PASSED : ReleaseGates.State.PENDING));
    }
    return List.copyOf(gates);
  }
}
