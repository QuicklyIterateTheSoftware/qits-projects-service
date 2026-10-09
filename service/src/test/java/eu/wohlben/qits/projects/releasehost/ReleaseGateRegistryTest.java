package eu.wohlben.qits.projects.releasehost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.control.ReleaseGates;
import eu.wohlben.qits.projects.control.gate.ReleaseGate;
import eu.wohlben.qits.projects.control.gate.ReleaseGateEvaluator;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The invariants every release gate the container discovers must hold. A new gate class is checked
 * here without being named.
 */
@QuarkusTest
public class ReleaseGateRegistryTest {

  @Inject ReleaseGateEvaluator evaluator;

  @Test
  void kindsAreUniqueAndWellFormed() {
    List<ReleaseGate> gates = evaluator.gates();
    Set<String> kinds = new HashSet<>();
    for (ReleaseGate gate : gates) {
      assertTrue(
          gate.kind().matches(ReleaseGateEvaluator.KIND_PATTERN),
          gate.kind() + " does not match " + ReleaseGateEvaluator.KIND_PATTERN);
      assertTrue(kinds.add(gate.kind()), "two gates claim the kind " + gate.kind());
      assertFalse(gate.label() == null || gate.label().isBlank(), gate.kind() + " has no label");
      assertNotNull(gate.position(), gate.kind() + " has no position");
    }
  }

  @Test
  void ordersAreUniqueSoTheAnswerOrderIsStable() {
    List<ReleaseGate> gates = evaluator.gates();
    assertEquals(
        gates.size(), gates.stream().map(ReleaseGate::order).distinct().count(), "two gates share an order");
  }

  @Test
  void everyLegacyKindButDeploymentHasExactlyOneGateClassInTheOldOrder() {
    List<ReleaseGates.Kind> legacy =
        evaluator.gates().stream()
            .map(gate -> ReleaseGateEvaluator.legacyKind(gate.kind()))
            .flatMap(java.util.Optional::stream)
            .toList();
    // DEPLOYMENT meant "the tag reached main": the finalize step, kept for the deprecated lists by
    // LegacyGates. The deployment's gate is deployment-not-rolled-back, which has no legacy kind.
    assertEquals(
        Arrays.stream(ReleaseGates.Kind.values())
            .filter(kind -> kind != ReleaseGates.Kind.DEPLOYMENT)
            .toList(),
        legacy);
  }
}
