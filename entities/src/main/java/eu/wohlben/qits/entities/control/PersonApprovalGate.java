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
 * REFINED) is a BACK move and is never judged; system approval is a later epic.
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
    if (mover.isPerson()) {
      return Optional.empty();
    }
    return Optional.of(
        "scheduling (REFINED → READY_FOR_DEV) needs a person; "
            + mover.described()
            + " is a machine credential — a person schedules it from the board, the Schedule tab"
            + " or their own qits CLI");
  }
}
