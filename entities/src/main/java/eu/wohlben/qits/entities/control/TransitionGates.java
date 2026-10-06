package eu.wohlben.qits.entities.control;

import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.EntityStatus;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.entities.error.ConflictException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * <b>The one reading of "which gates does this move have"</b> (qits-887), shared by the three
 * places that ask it — the move itself ({@link WorkEntityService}), the PUT-shaped door ({@link
 * EntityTransitionService}) and the served registry ({@link ArchetypeRegistryDocument}) — so a gate
 * the registry advertises is exactly a gate the move judges.
 */
public final class TransitionGates {

  private TransitionGates() {}

  /**
   * Whether a move of this kind is judged by gates at all: FORWARD and SKIP. A BACK, DROP or REOPEN
   * is a correction and passes every gate — see {@link TransitionGate}.
   */
  public static boolean gated(EntityStateMachine.TransitionKind kind) {
    return kind == EntityStateMachine.TransitionKind.FORWARD
        || kind == EntityStateMachine.TransitionKind.SKIP;
  }

  /** The gates that apply to {@code from → to} of {@code archetype}, in name order. */
  public static List<TransitionGate> applying(
      Iterable<? extends TransitionGate> gates, Archetype archetype, EntityStatus from, EntityStatus to) {
    List<TransitionGate> applying = new ArrayList<>();
    for (TransitionGate gate : gates) {
      if (gate.appliesTo(archetype, from, to)) {
        applying.add(gate);
      }
    }
    applying.sort(Comparator.comparing(TransitionGate::name));
    return List.copyOf(applying);
  }

  /**
   * The names of the gates a legal move of {@code archetype} has — empty for a move no gate judges
   * (a BACK, a DROP, a REOPEN) and for one none applies to. What the registry serves per move.
   */
  public static List<String> namesOf(
      Iterable<? extends TransitionGate> gates, Archetype archetype, EntityStateMachine.Transition move) {
    if (!gated(move.kind())) {
      return List.of();
    }
    return applying(gates, archetype, move.from(), move.to()).stream()
        .map(TransitionGate::name)
        .toList();
  }

  /**
   * <b>The gates on a move, judged</b>: a 409 carrying every refusal at once — {@code "Epic <id>
   * cannot move to READY_FOR_DEV: ACCEPTANCE_CRITERIA: …; PERSON_APPROVAL: …"} — or nothing. A move
   * that is not FORWARD or SKIP for {@code row}'s archetype is not judged; {@code from → to} must be
   * a legal move already (the machine's refusal comes first).
   */
  public static void require(
      Iterable<? extends TransitionGate> gates,
      String noun,
      WorkEntity row,
      EntityStatus from,
      EntityStatus to,
      Mover mover) {
    Optional<EntityStateMachine.TransitionKind> kind =
        EntityStateMachine.transitionsFrom(row.archetype, from).stream()
            .filter(move -> move.to() == to)
            .map(EntityStateMachine.Transition::kind)
            .findFirst();
    if (kind.isEmpty() || !gated(kind.get())) {
      return;
    }
    List<String> refused = new ArrayList<>();
    for (TransitionGate gate : applying(gates, row.archetype, from, to)) {
      gate.refusal(row, mover).ifPresent(why -> refused.add(gate.name() + ": " + why));
    }
    if (!refused.isEmpty()) {
      throw new ConflictException(
          noun + " " + row.id + " cannot move to " + to + ": " + String.join("; ", refused));
    }
  }
}
