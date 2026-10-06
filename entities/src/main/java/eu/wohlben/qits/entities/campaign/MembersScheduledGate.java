package eu.wohlben.qits.entities.campaign;

import eu.wohlben.qits.entities.control.EntityStateMachine;
import eu.wohlben.qits.entities.control.Mover;
import eu.wohlben.qits.entities.control.TransitionGate;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.EntityMembership;
import eu.wohlben.qits.entities.entity.EntityStatus;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.entities.persistence.EntityMembershipRepository;
import eu.wohlben.qits.entities.persistence.WorkEntityRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * <b>{@code MEMBERS_SCHEDULED}: a campaign is ready for dev when its members are</b> (qits-887,
 * decisions 18 and 19). A campaign's REFINED → READY_FOR_DEV is refused unless every member is
 * READY_FOR_DEV or further along the walk; the refusal names each member still behind, in the
 * campaign's order, with the status it holds.
 *
 * <p><b>DROPPED members are set aside</b>: decided against, they will never be scheduled and hold up
 * nothing. <b>An empty campaign passes</b> — it has no member behind, so there is nothing to wait
 * for; whether an empty campaign is worth scheduling is the person's call, and the {@code
 * PERSON_APPROVAL} gate beside this one already makes it a person's move.
 *
 * <p>The move <b>neither starts nor pauses</b> the campaign: READY_FOR_DEV means "ready for
 * development", and a started campaign runs at REFINED and READY_FOR_DEV alike ({@link
 * EntityStateMachine#campaignRunsAt}). This gate only says whether the claim is true.
 *
 * <p>A bean like every gate, collected by {@code WorkEntityService} and named on the move in the
 * served registry without a line elsewhere. It reads the memberships and the member rows in the
 * move's own transaction, so a member scheduled in the same breath is what it sees.
 */
@ApplicationScoped
public class MembersScheduledGate implements TransitionGate {

  public static final String NAME = "MEMBERS_SCHEDULED";

  @Inject EntityMembershipRepository memberships;

  @Inject WorkEntityRepository entities;

  /** The members' qualified ids for the sentence; {@code #<number>} without it. */
  @Inject Instance<EntityQualifier> qualifier;

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public boolean appliesTo(Archetype archetype, EntityStatus from, EntityStatus to) {
    return archetype == Archetype.CAMPAIGN
        && from == EntityStatus.REFINED
        && to == EntityStatus.READY_FOR_DEV;
  }

  @Override
  public Optional<String> refusal(WorkEntity row, Mover mover) {
    List<EntityMembership> edges = memberships.campaignMembers(row.id);
    if (edges.isEmpty()) {
      return Optional.empty();
    }
    Map<String, WorkEntity> rows =
        entities.listByIds(edges.stream().map(edge -> edge.childId).toList()).stream()
            .collect(Collectors.toMap(entity -> entity.id, Function.identity()));
    List<String> behind = new ArrayList<>();
    for (EntityMembership edge : edges) {
      WorkEntity member = rows.get(edge.childId);
      if (member != null && isBehind(member.status)) {
        behind.add(EntityQualifier.render(qualifier, member) + " (" + member.status + ")");
      }
    }
    if (behind.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        (behind.size() == 1 ? "a member is" : behind.size() + " members are")
            + " not READY_FOR_DEV yet: "
            + String.join(", ", behind)
            + " — schedule "
            + (behind.size() == 1 ? "it" : "them")
            + " (or drop "
            + (behind.size() == 1 ? "it" : "them")
            + ") first");
  }

  /** Before READY_FOR_DEV on the walk; DROPPED is aside, never behind. */
  static boolean isBehind(String statusWord) {
    EntityStatus status = EntityStatus.valueOf(statusWord);
    return !EntityStateMachine.isOffWalk(status)
        && !EntityStateMachine.isAtOrPast(status, EntityStatus.READY_FOR_DEV);
  }
}
