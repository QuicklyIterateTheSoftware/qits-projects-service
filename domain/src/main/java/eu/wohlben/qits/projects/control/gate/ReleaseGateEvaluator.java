package eu.wohlben.qits.projects.control.gate;

import eu.wohlben.qits.projects.control.ReleaseGates;
import eu.wohlben.qits.projects.dto.ReleaseGateCheckDto;
import eu.wohlben.qits.projects.dto.ReleaseQualityGateDto;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The shared machinery over every {@link ReleaseGate}: find them, order them, ask each one, and put
 * the answer on the wire. A gate class holds only its own rule.
 *
 * <p><b>The rule for every gate:</b> {@code DOES_NOT_APPLY} leaves the gate out; {@code UNREADABLE}
 * reports it {@code UNKNOWN} with the gate set's reason; {@code APPLIES} reports what the gate says.
 */
@ApplicationScoped
public class ReleaseGateEvaluator {

  /** What a kind must look like. */
  public static final String KIND_PATTERN = "[a-z0-9-]+";

  @Inject Instance<ReleaseGate> discovered;

  private volatile List<ReleaseGate> ordered;

  /** One gate and what it said. */
  public record Evaluated(ReleaseGate gate, GateEvaluation evaluation) {}

  /** Every gate, in answer order. */
  public List<ReleaseGate> gates() {
    List<ReleaseGate> known = ordered;
    if (known == null) {
      List<ReleaseGate> found = new ArrayList<>();
      discovered.forEach(found::add);
      known = order(found);
      ordered = known;
    }
    return known;
  }

  /** What every gate that holds {@code subject} says, in answer order. */
  public List<Evaluated> evaluate(GateSubject subject) {
    return evaluate(gates(), subject);
  }

  /** The same, over a given list of gates — the seam the unit tests use. */
  public static List<Evaluated> evaluate(List<ReleaseGate> gates, GateSubject subject) {
    List<Evaluated> answered = new ArrayList<>();
    for (ReleaseGate gate : order(gates)) {
      switch (gate.applicability(subject)) {
        case DOES_NOT_APPLY -> {}
        case UNREADABLE ->
            answered.add(
                new Evaluated(
                    gate,
                    GateEvaluation.of(ReleaseGates.State.UNKNOWN, subject.gateSet().detail())));
        case APPLIES -> answered.add(new Evaluated(gate, gate.evaluate(subject)));
      }
    }
    return List.copyOf(answered);
  }

  /** Answer order: {@link ReleaseGate#order()}, then kind. */
  static List<ReleaseGate> order(List<ReleaseGate> gates) {
    return gates.stream()
        .sorted(Comparator.comparingInt(ReleaseGate::order).thenComparing(ReleaseGate::kind))
        .toList();
  }

  /**
   * The deprecated {@code gates[]} word for a kind: {@code ci} is {@code CI}. Present only for the
   * five kinds {@link ReleaseGates.Kind} names; a newer gate is on the new list alone.
   */
  public static Optional<ReleaseGates.Kind> legacyKind(String kind) {
    String upper = kind.toUpperCase(Locale.ROOT).replace('-', '_');
    for (ReleaseGates.Kind legacy : ReleaseGates.Kind.values()) {
      if (legacy.name().equals(upper)) {
        return Optional.of(legacy);
      }
    }
    return Optional.empty();
  }

  /** One evaluated gate as the wire carries it. */
  public static ReleaseQualityGateDto toDto(Evaluated evaluated) {
    ReleaseGate gate = evaluated.gate();
    GateEvaluation answer = evaluated.evaluation();
    return new ReleaseQualityGateDto(
        gate.kind(),
        gate.label(),
        gate.position().wire(),
        answer.state().name(),
        answer.detail(),
        answer.checks().stream()
            .map(
                check ->
                    new ReleaseGateCheckDto(
                        check.name(), check.state(), check.detail(), check.runId()))
            .toList(),
        answer.runId(),
        answer.link());
  }
}
