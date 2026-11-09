package eu.wohlben.qits.entities.control;

import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.EntityStatus;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.entities.entity.WorkTag;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * <b>{@code CONDITION_MET}: a condition closes straight to DONE</b> (qits-1972). A ticket tagged
 * {@link WorkTag#CONDITION} is no work to walk but a fact other items name in their {@code needs}
 * at DONE, so it is met by one move — the ticket's {@link EntityStateMachine.TransitionKind#CLOSE},
 * {@code X → DONE} from an open status short of VERIFIED — rather than walked to VERIFIED first.
 * It judges a ticket's CLOSE moves alone; VERIFIED → DONE is the walk's FORWARD and stays ungated,
 * as at {@link ChildrenClosedGate}, whose CLOSE moves (an epic's, a feature's) this gate leaves
 * alone.
 *
 * <p><b>Only a condition closes.</b> Every other ticket is refused: its DONE is the platform's,
 * set when the wrapper release request carrying its branch is finalized (qits-150). The status
 * door refuses an untagged ticket's DONE before any gate is asked, so this refusal is the
 * in-process callers' and the served registry's reading of the same rule.
 *
 * <p><b>Who may close</b>: a person a door verified, or the project's own delivery desk — {@link
 * ChildrenClosedGate#mayClose}, the same rule. Every other machine is refused, and every refusal is
 * reported together in the one 409 {@code TransitionGates.require} raises.
 */
@ApplicationScoped
public class ConditionMetGate implements TransitionGate {

  public static final String NAME = "CONDITION_MET";

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public boolean appliesTo(Archetype archetype, EntityStatus from, EntityStatus to) {
    return archetype == Archetype.TICKET && EntityStateMachine.isClose(archetype, from, to);
  }

  @Override
  public Optional<String> refusal(WorkEntity row, Mover mover) {
    List<String> refused = new ArrayList<>();
    if (row == null || row.tags == null || !row.tags.contains(WorkTag.CONDITION)) {
      refused.add(
          "it is not tagged condition — only a ticket tagged condition closes straight to DONE;"
              + " any other ticket is set DONE by the platform when the wrapper release request"
              + " carrying its branch is finalized");
    }
    if (!ChildrenClosedGate.mayClose(row, mover)) {
      refused.add(
          "closing a condition to DONE needs a person or the delivery desk; "
              + mover.described()
              + " is neither — ask the desk with an @desk comment");
    }
    return refused.isEmpty() ? Optional.empty() : Optional.of(String.join("; ", refused));
  }
}
