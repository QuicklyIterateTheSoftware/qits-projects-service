package eu.wohlben.qits.entities.control;

import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.EntityStatus;
import eu.wohlben.qits.entities.entity.WorkEntity;
import java.util.Optional;

/**
 * <b>A quality gate on a lifecycle move</b> (qits-887): a named check a move has to pass, beside
 * the state machine rather than in it. {@link EntityStateMachine} says which moves exist; a gate
 * says what an entity — or its mover — must be for one of them to be made <em>now</em>.
 *
 * <p><b>Adding one is one class.</b> A gate is a CDI bean; {@link WorkEntityService} collects every
 * one through {@code Instance<TransitionGate>} and asks each whether it {@link #appliesTo} the move
 * at hand, so a new gate touches neither the machine nor any other gate, and the served registry
 * ({@link ArchetypeRegistryDocument.LegalMove#gates}) names it on every move it applies to without a
 * line of its own. {@link TransitionGates} is the shared reading of that.
 *
 * <p><b>Gates judge FORWARD and SKIP moves only.</b> A BACK, a DROP or a REOPEN is a correction —
 * undoing a claim, abandoning work — and is never refused by a quality gate; that rule is the
 * caller's ({@link TransitionGates#gated}), so a gate does not restate it. Every gate that refuses is
 * reported together in one 409, so a caller fixes everything in one round trip.
 *
 * <p>{@link #appliesTo} is a pure function of the move, because the registry asks it with no row in
 * hand; {@link #refusal} reads the row, and may read beyond it — a campaign gate reading its members
 * (qits-942) injects what it needs, since gates are beans.
 */
public interface TransitionGate {

  /** The gate's name as the registry and a refusal spell it — {@code ACCEPTANCE_CRITERIA}. */
  String name();

  /** Whether this gate judges a move of {@code archetype} from {@code from} to {@code to}. */
  boolean appliesTo(Archetype archetype, EntityStatus from, EntityStatus to);

  /**
   * Why {@code row} may not make the move now, as a clause with no subject ("it has no acceptance
   * criteria"), or empty when it passes. Asked only when {@link #appliesTo} said yes. {@code row} is
   * the entity as it stands before the move, and {@code mover} who is moving it.
   */
  Optional<String> refusal(WorkEntity row, Mover mover);
}
