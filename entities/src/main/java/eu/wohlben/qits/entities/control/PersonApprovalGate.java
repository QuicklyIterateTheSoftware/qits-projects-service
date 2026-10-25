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
 * first anyway) and a campaign's REFINED → READY_FOR_DEV is a person's move as decision 19 says. A
 * schedule's cascade is judged once, on the entity that moves. Unscheduling (READY_FOR_DEV →
 * REFINED) is a BACK move and is never judged.
 *
 * <p><b>One machine passes: the project's own delivery desk</b> — a {@link Mover#isDesk desk}
 * mover bound to the row's project ({@link Mover#deskProject}), which since qits-1596 holds the
 * owner's coordinator's rights and schedules its own project's items as the coordinator's person
 * would. A desk of another project, or one whose token named no project, is refused like any
 * machine. Before qits-1596 the desk passed only carrying a {@link Mover#declaredApproval} (epic
 * qits-1440) — the project's {@code front_desk.delivery.start_refined}, which the dispatch path
 * still stamps — and such a mover still passes, by the same project rule.
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
    return from == EntityStatus.REFINED && to == EntityStatus.READY_FOR_DEV;
  }

  @Override
  public Optional<String> refusal(WorkEntity row, Mover mover) {
    if (mover.isPerson() || isOwnDesk(row, mover)) {
      return Optional.empty();
    }
    return Optional.of(
        "scheduling (REFINED → READY_FOR_DEV) needs a person; "
            + mover.described()
            + " is a machine credential — a person schedules it from the board, the Schedule tab"
            + " or their own qits CLI, or the project's own delivery desk does");
  }

  /** The row's own project's delivery desk (qits-1596). */
  private static boolean isOwnDesk(WorkEntity row, Mover mover) {
    return mover.isDesk()
        && mover.deskProject() != null
        && row != null
        && mover.deskProject().equals(row.projectId);
  }
}
