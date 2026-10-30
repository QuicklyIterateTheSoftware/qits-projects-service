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
 * <b>{@code CHILDREN_CLOSED}: finished work closes straight to DONE when every child is closed</b>
 * (qits-1757). It judges the {@link EntityStateMachine.TransitionKind#CLOSE} moves alone — a
 * campaign's, an epic's or a feature's {@code X → DONE} from an open status short of VERIFIED — and
 * never VERIFIED → DONE, which is the walk's FORWARD and stays ungated. That matters beyond the move
 * door: the bulk door ({@code EntityTransitionService.refuseGatedStatus}) refuses any status change
 * a gate {@link #appliesTo}, so matching VERIFIED → DONE here would shut the ordinary close there.
 *
 * <p><b>Which children count</b>: a campaign's CAMPAIGN-kind members; an epic's or a feature's
 * STRUCTURAL children (its features, its tasks). <b>Only the direct child's own status is read</b>,
 * and it must be DONE or DROPPED — VERIFIED is not closed, whatever {@code CampaignProgress} draws.
 * Nothing deeper is read, and nothing needs to be: a DONE child has no open descendant (qits-1685's
 * downward cascade closed them), and a campaign holding an epic whose feature is open is refused
 * anyway, because that epic is not DONE. <b>An item with no children is refused</b>: "every child is
 * closed" says nothing of an empty one, and its close is the walk.
 *
 * <p><b>Who may close</b>: a person a door verified, or the project's own delivery desk ({@link
 * Mover#isDesk}, bound to the row's project). Every other machine — an agent's {@code qits:agent},
 * {@code qits:system}, an in-process caller — is refused, here rather than in a controller, so every
 * door that asks the gates enforces it. Both refusals are reported together in the one 409 {@code
 * TransitionGates.require} raises.
 *
 * <p>Nothing is derived: no move of a child ever closes its parent. This gate only says whether a
 * close somebody asked for is true.
 */
@ApplicationScoped
public class ChildrenClosedGate implements TransitionGate {

  public static final String NAME = "CHILDREN_CLOSED";

  @Inject EntityMembershipRepository memberships;

  @Inject WorkEntityRepository entities;

  /** The children's qualified ids for the sentence; {@code #<number>} without it. */
  @Inject Instance<EntityQualifier> qualifier;

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public boolean appliesTo(Archetype archetype, EntityStatus from, EntityStatus to) {
    return EntityStateMachine.isClose(archetype, from, to);
  }

  @Override
  public Optional<String> refusal(WorkEntity row, Mover mover) {
    List<String> refused = new ArrayList<>();
    if (!mayClose(row, mover)) {
      refused.add(
          "closing to DONE needs a person or the delivery desk; "
              + mover.described()
              + " is neither — a person closes it from the board or their own qits CLI, or the"
              + " project's desk does");
    }
    childrenRefusal(row).ifPresent(refused::add);
    return refused.isEmpty() ? Optional.empty() : Optional.of(String.join("; ", refused));
  }

  /** A verified person, or the delivery desk of {@code row}'s own project. */
  static boolean mayClose(WorkEntity row, Mover mover) {
    return mover.isPerson()
        || (mover.isDesk()
            && row != null
            && mover.deskProject() != null
            && mover.deskProject().equals(row.projectId));
  }

  private Optional<String> childrenRefusal(WorkEntity row) {
    List<EntityMembership> edges =
        row.archetype == Archetype.CAMPAIGN
            ? memberships.campaignMembers(row.id)
            : memberships.childrenOf(row.id);
    if (edges.isEmpty()) {
      return Optional.of(
          EntityQualifier.render(qualifier, row)
              + " has no children — closing to DONE needs at least one closed child");
    }
    Map<String, WorkEntity> rows =
        entities.listByIds(edges.stream().map(edge -> edge.childId).toList()).stream()
            .collect(Collectors.toMap(entity -> entity.id, Function.identity()));
    List<String> open = new ArrayList<>();
    for (EntityMembership edge : edges) {
      WorkEntity child = rows.get(edge.childId);
      if (child != null && !isClosed(child.status)) {
        open.add(EntityQualifier.render(qualifier, child) + " (" + child.status + ")");
      }
    }
    if (open.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        (open.size() == 1 ? "a child is" : open.size() + " children are")
            + " not closed yet: "
            + String.join(", ", open)
            + " — close or drop "
            + (open.size() == 1 ? "it" : "them")
            + " first");
  }

  /** DONE or DROPPED; VERIFIED and everything before it are open. */
  static boolean isClosed(String statusWord) {
    return EntityStatus.DONE.name().equals(statusWord)
        || EntityStatus.DROPPED.name().equals(statusWord);
  }
}
