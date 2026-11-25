package eu.wohlben.qits.entities.control;

import eu.wohlben.qits.entities.entity.WorkEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;
import java.time.Instant;
import java.util.IdentityHashMap;
import java.util.Map;
import org.jboss.logging.Logger;

/**
 * <b>Where a change of {@link WorkEntity#blocked} is owed to the commit</b> (qits-2674), so the
 * ledger can count blocks: the block door's write ({@link WorkEntityService#setBlocked}) and a
 * transition's clear ({@link WorkEntityService#clearBlocks}, from both doors that move a status)
 * each tell this bean, inside their write, what the flag became and why.
 *
 * <p><b>{@link AssigneeWrites}' shape.</b> Every write records the row and the flag it held before
 * the transaction's first write in the transaction's registry, and one synchronisation tells {@link
 * BlockAnnouncer} after a commit, once per row whose flag actually differs from that first one — an
 * idempotent re-block, and a transition of a row that was not blocked, announce nothing. A write
 * that rolls back announces nothing, and a {@code WritePatience} retry re-runs the body in a new
 * transaction that owes afresh. With no transaction active it is told at once; with no announcer
 * assembled (this module's own suite) nothing is owed at all.
 *
 * <p>Only the <b>explicit</b> flag counts. The derived agent-waiting block is a reading of the row
 * ({@link EntityBlockState}), not a write anybody made, and a block nobody stated is not one the
 * ledger can attribute.
 */
@ApplicationScoped
public class BlockWrites {

  private static final Logger LOG = Logger.getLogger(BlockWrites.class);

  /** The key of this transaction's pending changes in the registry. */
  private static final Object PENDING = BlockWrites.class.getName() + ".pending";

  @Inject Instance<BlockAnnouncer> announcers;

  @Inject Instance<EntityQualifier> qualifier;

  @Inject Instance<TransactionSynchronizationRegistry> transactions;

  /**
   * {@code row}'s flag is about to be written by {@code changedBy}, saying {@code reason}. Called
   * BEFORE the write, so the value held before the transaction's first write is the row's own.
   */
  public void writing(WorkEntity row, String reason, String changedBy) {
    if (announcers.isUnsatisfied()) {
      return;
    }
    owe(row, reason, changedBy);
  }

  /** The reason a transition's clear states: {@code cleared by transition to <STATUS>}. */
  public static String clearedBy(String status) {
    return "cleared by transition to " + status;
  }

  /** A row whose flag was written in this transaction, and what it held before the first write. */
  private static final class Pending {
    final WorkEntity row;
    final boolean before;
    String reason;
    String changedBy;
    String qualifiedId;

    Pending(WorkEntity row, boolean before) {
      this.row = row;
      this.before = before;
    }

    boolean changed() {
      return before != row.blocked;
    }
  }

  private void owe(WorkEntity row, String reason, String changedBy) {
    TransactionSynchronizationRegistry registry = activeTransaction();
    if (registry == null) {
      // Nothing here writes outside a transaction; were one to, the change is told after it, by a
      // synchronisation it cannot register — so it is dropped rather than told before the write.
      LOG.debugf("A block write of %s outside a transaction is not announced", row.id);
      return;
    }
    try {
      @SuppressWarnings("unchecked")
      Map<WorkEntity, Pending> pending = (Map<WorkEntity, Pending>) registry.getResource(PENDING);
      if (pending == null) {
        pending = new IdentityHashMap<>();
        registry.putResource(PENDING, pending);
        registry.registerInterposedSynchronization(new AfterCommit(pending));
      }
      Pending owed = pending.computeIfAbsent(row, ignored -> new Pending(row, row.blocked));
      owed.reason = reason;
      owed.changedBy = changedBy;
      if (owed.qualifiedId == null) {
        owed.qualifiedId = qualifiedId(row);
      }
    } catch (RuntimeException e) {
      LOG.warnf(e, "Could not owe the block change of %s", row.id);
    }
  }

  /** Announces each row that ends the transaction with a different flag, after a commit. */
  private final class AfterCommit implements Synchronization {

    private final Map<WorkEntity, Pending> pending;

    AfterCommit(Map<WorkEntity, Pending> pending) {
      this.pending = pending;
    }

    @Override
    public void beforeCompletion() {}

    @Override
    public void afterCompletion(int status) {
      if (status != Status.STATUS_COMMITTED) {
        return;
      }
      Instant at = Instant.now();
      for (Pending owed : pending.values()) {
        if (owed.changed()) {
          announce(owed, at);
        }
      }
    }
  }

  private void announce(Pending owed, Instant at) {
    WorkEntity row = owed.row;
    try {
      announcers
          .get()
          .onBlockChanged(
              new BlockChange(
                  row.id,
                  row.projectId,
                  row.archetype,
                  row.number,
                  owed.qualifiedId,
                  row.status,
                  row.blocked,
                  owed.reason,
                  owed.changedBy,
                  at));
    } catch (RuntimeException e) {
      // The port must not throw; the write is committed whatever happens here.
      LOG.warnf(e, "Could not announce the block change of %s", row.id);
    }
  }

  /** {@code <slug>-<number>}, or null: a decoration, never a failure. */
  private String qualifiedId(WorkEntity row) {
    if (qualifier.isUnsatisfied()) {
      return null;
    }
    try {
      return qualifier.get().qualifiedId(row);
    } catch (RuntimeException e) {
      return null;
    }
  }

  private TransactionSynchronizationRegistry activeTransaction() {
    if (!transactions.isResolvable()) {
      return null;
    }
    try {
      TransactionSynchronizationRegistry registry = transactions.get();
      int status = registry.getTransactionStatus();
      return status == Status.STATUS_ACTIVE || status == Status.STATUS_MARKED_ROLLBACK
          ? registry
          : null;
    } catch (RuntimeException e) {
      return null;
    }
  }
}
