package eu.wohlben.qits.entities.control;

import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.EntityStatus;
import eu.wohlben.qits.entities.entity.WorkEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * <b>{@code QUALITY_CHECKS}: the step between a ticket's IMPLEMENTED and its verification</b>
 * (qits-150). Every move of a ticket into VERIFYING or VERIFIED from before them — IMPLEMENTED →
 * VERIFYING (the agent's own move, and the platform's automatic one at the verify press, {@link
 * EntityStateMachine#isStartedMove}) and IMPLEMENTED → VERIFIED (the SKIP) — runs every {@link
 * QualityCheck} bean, and the move is refused with all their reasons at once while any of them
 * fails.
 *
 * <p><b>No check exists yet, so the step is empty and every ticket passes it.</b> The gate is the
 * extension point: a check added later is judged here without a line changing here.
 *
 * <p><b>No path from IMPLEMENTED to VERIFIED goes round it.</b> {@code WorkEntityService.move} judges
 * every gate on a FORWARD or SKIP move — the status door, the platform's {@code transitionFrom}, the
 * verify press's started move — and the PUT-shaped door ({@code EntityTransitionService}) refuses any
 * status change a gate applies to, which is why {@link #appliesTo} reads every source before
 * VERIFYING and not only IMPLEMENTED: that door has no adjacency rule, and IMPLEMENTING → VERIFIED
 * stated there would otherwise skip the step. On the served registry only the two real moves out of
 * IMPLEMENTED carry the name. A move back into VERIFYING from VERIFIED is a correction and is never
 * judged.
 *
 * <p><b>A failing check will later send the ticket back to its implementer</b> — see {@link
 * QualityCheck}. Not built: a refusal today is the ordinary quality-gate 409.
 *
 * <p>A ticket only: an epic is verified as a whole plan, and a feature or a task runs no phase.
 */
@ApplicationScoped
public class QualityChecksGate implements TransitionGate {

  public static final String NAME = "QUALITY_CHECKS";

  /** The checks this gate runs — none yet. */
  @Inject Instance<QualityCheck> checks;

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public boolean appliesTo(Archetype archetype, EntityStatus from, EntityStatus to) {
    return archetype == Archetype.TICKET
        && (to == EntityStatus.VERIFYING || to == EntityStatus.VERIFIED)
        && from != null
        && !EntityStateMachine.isOffWalk(from)
        && !EntityStateMachine.isAtOrPast(from, EntityStatus.VERIFYING);
  }

  @Override
  public Optional<String> refusal(WorkEntity row, Mover mover) {
    List<QualityCheck> running = new ArrayList<>();
    checks.forEach(running::add);
    running.sort(Comparator.comparing(QualityCheck::name));
    List<String> failed = new ArrayList<>();
    for (QualityCheck check : running) {
      check.refusal(row, mover).ifPresent(why -> failed.add(check.name() + ": " + why));
    }
    if (failed.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        "its IMPLEMENTED claim fails "
            + (failed.size() == 1 ? "a quality check" : failed.size() + " quality checks")
            + " — "
            + String.join("; ", failed));
  }
}
