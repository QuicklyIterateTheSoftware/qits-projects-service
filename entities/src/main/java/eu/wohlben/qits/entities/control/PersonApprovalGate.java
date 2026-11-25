package eu.wohlben.qits.entities.control;

import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.EntityStatus;
import eu.wohlben.qits.entities.entity.WorkEntity;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;

/**
 * <b>{@code PERSON_APPROVAL}: scheduling is a person's decision</b> (qits-887, decision 4). The move
 * REFINED → READY_FOR_DEV is refused unless the {@link Mover} is a person — which this module never
 * decides: a door says so, from qits-891's one definition of a person ({@code
 * projects/security/PersonCheck}: a browser session this service introspected, or a person's
 * {@code qits} CLI token). An agent's bearer, a service client, asserted identity headers, an MCP
 * tool and every in-process caller are machines and are refused.
 *
 * <p><b>Every archetype</b>, so a piece's own scheduling is covered too (the piece rule refuses it
 * first anyway). A schedule's cascade is judged once, on the entity
 * that moves. Unscheduling (READY_FOR_DEV →
 * REFINED) is a BACK move and is never judged.
 *
 * <p><b>One machine passes: a delivery desk carrying a {@link Mover#declaredApproval}</b> (epic
 * qits-1440) — the project's own {@code front_desk.delivery.start_refined} in its {@code
 * .config/qits/project.yml}, which a person committed and a release request approved. Only the
 * dispatch path stamps one, and only on a desk whose project declares it. qits-1596, which gave the
 * desk the owner's coordinator's rights, kept {@code start_refined} as the owner's switch: without
 * it the desk schedules nothing.
 *
 * <p><b>A project agent passes on the same terms</b> (qits-2672, epic qits-2521): a project's
 * long-running agent carrying the same declaration — the lifecycle move door stamps one only when
 * its project declares {@code start_refined} — schedules an item of its own project that is
 * assigned to it by name. Another's item, another project's, or any item while the switch is off
 * is refused as any machine's move is. Every other gate still runs.
 */
@ApplicationScoped
public class PersonApprovalGate implements TransitionGate {

  public static final String NAME = "PERSON_APPROVAL";

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public boolean appliesTo(Archetype archetype, EntityStatus from, EntityStatus to) {
    return from == EntityStatus.REFINED
        && to == EntityStatus.READY_FOR_DEV;
  }

  @Override
  public Optional<String> refusal(WorkEntity row, Mover mover) {
    if (mover.isPerson() || isDeclaredOwnDesk(row, mover) || isDeclaredOwnAgent(row, mover)) {
      return Optional.empty();
    }
    if (mover.isProjectAgent()) {
      return Optional.of(
          "scheduling (REFINED → READY_FOR_DEV) needs a person; "
              + mover.described()
              + " is a machine credential — "
              + (mover.declaredApproval() == null
                  ? "a project agent schedules only under front_desk.delivery.start_refined"
                  : "a project agent schedules only its own project's items assigned to it"));
    }
    return Optional.of(
        "scheduling (REFINED → READY_FOR_DEV) needs a person; "
            + mover.described()
            + " is a machine credential — a person schedules it from the board, the Schedule tab"
            + " or their own qits CLI");
  }

  /**
   * A desk stamped with its project's start-refined declaration, on its own project's row — the
   * dispatch path stamps only such a desk, and the project check keeps it that way.
   */
  private static boolean isDeclaredOwnDesk(WorkEntity row, Mover mover) {
    return mover.isDesk()
        && mover.declaredApproval() != null
        && row != null
        && mover.deskProject() != null
        && mover.deskProject().equals(row.projectId);
  }

  /**
   * A project agent stamped with its project's start-refined declaration, on its own project's row
   * assigned to it by name (qits-2672).
   */
  private static boolean isDeclaredOwnAgent(WorkEntity row, Mover mover) {
    return mover.isProjectAgent()
        && mover.declaredApproval() != null
        && row != null
        && mover.agentProject() != null
        && mover.agentProject().equals(row.projectId)
        && mover.agentName() != null
        && mover.agentName().equals(row.assignee);
  }
}
