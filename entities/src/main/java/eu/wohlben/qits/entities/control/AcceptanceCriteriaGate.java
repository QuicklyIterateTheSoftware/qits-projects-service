package eu.wohlben.qits.entities.control;

import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.EntityStatus;
import eu.wohlben.qits.entities.entity.WorkEntity;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * <b>{@code ACCEPTANCE_CRITERIA}: work is not refined, and not scheduled, without acceptance
 * criteria</b> (qits-887, decision 2) — the first quality gate. An epic or a ticket moving into
 * REFINED or READY_FOR_DEV must carry at least one criterion.
 *
 * <p><b>Both statuses, on purpose</b> (decision 14): a REFINED entity that predates the gate stays
 * REFINED without criteria, and the check on scheduling is what stops it being scheduled until a
 * person or an agent writes them — editable at REFINED, outside an epic's scope freeze, for exactly
 * that reason.
 *
 * <p><b>EPIC and TICKET only for now</b> (decision 13): a feature and a task are pieces of their
 * epic's plan, carried along by its moves, and hold no criteria. {@link #ARCHETYPES} is a field so
 * widening the gate later is one word, and the property's slot ({@code Archetypes}) widens with it.
 */
@ApplicationScoped
public class AcceptanceCriteriaGate implements TransitionGate {

  public static final String NAME = "ACCEPTANCE_CRITERIA";

  /** The kinds the gate judges. */
  static final Set<Archetype> ARCHETYPES = EnumSet.of(Archetype.EPIC, Archetype.TICKET);

  /** The statuses a judged kind may not enter without criteria. */
  static final Set<EntityStatus> INTO = EnumSet.of(EntityStatus.REFINED, EntityStatus.READY_FOR_DEV);

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public boolean appliesTo(Archetype archetype, EntityStatus from, EntityStatus to) {
    return ARCHETYPES.contains(archetype) && INTO.contains(to);
  }

  @Override
  public Optional<String> refusal(WorkEntity row, Mover mover) {
    if (row.acceptanceCriteria != null && !row.acceptanceCriteria.isEmpty()) {
      return Optional.empty();
    }
    String tool = row.archetype == Archetype.EPIC ? "update_epic" : "update_ticket";
    return Optional.of(
        "it has no acceptance criteria — write them first (acceptanceCriteria: "
            + tool
            + ", PATCH /entities/{id} or qits work update)");
  }
}
