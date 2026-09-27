package eu.wohlben.qits.entities.control;

import eu.wohlben.qits.entities.entity.AuditEntityType;
import eu.wohlben.qits.entities.entity.AuditOperation;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.entities.error.ConflictException;
import eu.wohlben.qits.entities.error.NotFoundException;
import eu.wohlben.qits.entities.persistence.WorkEntityRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The entities module's half of the one dispatch path (qits-394): reading a row whatever its
 * archetype, and recording the continue-or-stop bit on it.
 *
 * <p><b>This module still knows nothing about phases, workspaces or prompts</b> — those are the
 * service layer's ({@code projects/api/EntityDispatch}, {@code PhasePrompts}, {@code PhaseAdvance}).
 * What is here is only what a row can say about itself: which row an id names, and whether the last
 * press on it asked for the whole flow or for one phase ({@link WorkEntity#dispatchContinues}).
 *
 * <p>The bit is refused on an archetype with no lifecycle. That is a registry fact rather than a
 * phase fact — a kind with no legal statuses has no transition to continue from — so it is stated
 * here, through {@link Archetypes#legalStatuses}, and not as a second list of dispatchable kinds.
 */
@ApplicationScoped
public class EntityDispatchService {

  @Inject WorkEntityRepository entities;

  @Inject AuditService auditService;

  @Inject ReadPatience reads;

  @Inject WritePatience writes;

  /**
   * The row this id names, <b>of any archetype</b>, or a 404. The per-archetype services each 404 a
   * row of another kind; the one dispatch door is about every kind, so it reads through here.
   */
  public WorkEntity get(String id) {
    return reads.hold("an entity by id", () -> entity(id));
  }

  /**
   * {@link #get}, read in a transaction of its own (qits-417) — so what it answers is what is
   * committed now, never a row an earlier read in the same request or request context left in that
   * context's session. The by-id dispatch reads through here: it decides a phase from the status, and
   * a caller that looked at the row earlier (the campaign executor's own checks) must not be handed
   * that earlier look back.
   */
  public WorkEntity fresh(String id) {
    return reads.hold(
        "an entity by id, fresh",
        () -> QuarkusTransaction.requiringNew().call(() -> entity(id)));
  }

  /**
   * Record what the press asked for: {@code true} for the whole flow, {@code false} for one phase.
   * Audited as an UPDATE of the row under its own id — the two archetypes that carry a lifecycle are
   * both roots, so the row is its own subtree key.
   *
   * <p>Idempotent in value and still audited on every call, {@code setBlocked}'s reasoning: each
   * press is a statement somebody made, and the log is where "who asked for one phase" is read.
   */
  public WorkEntity setDispatchContinues(String id, boolean continues, String changedBy) {
    return writes.hold(
        "entity dispatch mode",
        () -> {
          WorkEntity row = entity(id);
          if (Archetypes.legalStatuses(row.archetype).isEmpty()) {
            throw new ConflictException(
                "A "
                    + row.archetype
                    + " has no lifecycle, so there is no run of phases to continue or stop.");
          }
          row.dispatchContinues = continues;
          entities.getEntityManager().flush();
          auditService.record(
              AuditEntityType.of(row.archetype),
              row.id,
              row.id,
              AuditOperation.UPDATE,
              changedBy,
              row);
          return row;
        });
  }

  private WorkEntity entity(String id) {
    WorkEntity row = id == null ? null : entities.findById(id);
    if (row == null) {
      throw new NotFoundException("Entity not found: " + id);
    }
    return row;
  }
}
