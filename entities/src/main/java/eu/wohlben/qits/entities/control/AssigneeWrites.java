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
import java.util.Objects;
import org.jboss.logging.Logger;

/**
 * <b>The one writer of {@link WorkEntity#assignee}</b> — and so the one place its change is
 * announced (qits-2518).
 *
 * <p><b>Two writes, two claim rules.</b> {@link #claim} is a person's write (qits-2259), shared by
 * the create, the PATCH and the PUT-shaped transition: a different non-blank assignee is a claim; a
 * blank or absent one clears both; the value the row already holds, restated, changes nothing — the
 * SPA re-sends the whole row on every save, and that must not turn the agent a dispatch put there
 * into a person's claim. {@link #dispatch} is the dispatch's own write ({@code
 * EntityDispatchService.setAssignee}), which clears the flag.
 *
 * <p><b>The announcement is owed to the commit</b>, {@code ReleaseRequestStates}' shape: every
 * write records the row and the value it held before the transaction's first write in the
 * transaction's registry, and one synchronisation tells {@link AssigneeAnnouncer} after a commit,
 * once per row whose value actually differs from that first one. A write that rolls back announces
 * nothing, and a {@code WritePatience} retry re-runs the body in a new transaction that owes afresh,
 * so a retried write is announced once. With no transaction active (nothing here does that) it is
 * told at once; with no announcer assembled (this module's own suite) nothing is owed at all.
 *
 * <p>The qualified id is read when the change is first owed, inside the write — where {@link
 * EntityQualifier} is built to be called — never in the after-completion. So is the writer's name
 * ({@link AssignerNames}), again whenever a later write in the transaction changes the principal:
 * the request's identity and the database are only safely there during the write.
 */
@ApplicationScoped
public class AssigneeWrites {

  private static final Logger LOG = Logger.getLogger(AssigneeWrites.class);

  /** The key of this transaction's pending changes in the registry. */
  private static final Object PENDING = AssigneeWrites.class.getName() + ".pending";

  @Inject Instance<AssigneeAnnouncer> announcers;

  @Inject Instance<EntityQualifier> qualifier;

  @Inject Instance<AssignerNames> names;

  @Inject Instance<TransactionSynchronizationRegistry> transactions;

  /** Writes {@code assignee} onto {@code row} as a person's write, and the claim with it. */
  public void claim(WorkEntity row, String assignee, String changedBy) {
    String before = normalised(row.assignee);
    String next = normalised(assignee);
    if (next == null) {
      row.assignee = null;
      row.assigneeClaimed = false;
    } else if (!next.equals(row.assignee)) {
      row.assignee = next;
      row.assigneeClaimed = true;
    }
    owe(row, before, changedBy);
  }

  /** Writes the agent a press just put on {@code row}: not a person's claim, so it clears the flag. */
  public void dispatch(WorkEntity row, String assignee, String changedBy) {
    String before = normalised(row.assignee);
    row.assignee = normalised(assignee);
    // The agent is on it, not a person (qits-2259): the next phase's press goes through.
    row.assigneeClaimed = false;
    owe(row, before, changedBy);
  }

  private static String normalised(String assignee) {
    return assignee == null || assignee.isBlank() ? null : assignee;
  }

  /** A row whose assignee was written in this transaction, and what it held before the first write. */
  private static final class Pending {
    final WorkEntity row;
    final String before;
    String changedBy;
    String qualifiedId;
    String changedByName;
    boolean named;

    Pending(WorkEntity row, String before) {
      this.row = row;
      this.before = before;
    }

    boolean changed() {
      return !Objects.equals(before, row.assignee);
    }
  }

  private void owe(WorkEntity row, String before, String changedBy) {
    if (announcers.isUnsatisfied()) {
      return;
    }
    TransactionSynchronizationRegistry registry = activeTransaction();
    if (registry == null) {
      Pending now = new Pending(row, before);
      now.changedBy = changedBy;
      if (now.changed()) {
        now.qualifiedId = qualifiedId(row);
        name(now);
        announce(now, Instant.now());
      }
      return;
    }
    try {
      @SuppressWarnings("unchecked")
      Map<WorkEntity, Pending> pending = (Map<WorkEntity, Pending>) registry.getResource(PENDING);
      if (pending == null) {
        if (Objects.equals(before, row.assignee)) {
          // Nothing owed yet, and this write owes nothing: no synchronisation for a restatement.
          return;
        }
        pending = new IdentityHashMap<>();
        registry.putResource(PENDING, pending);
        registry.registerInterposedSynchronization(new AfterCommit(pending));
      }
      Pending owed = pending.computeIfAbsent(row, ignored -> new Pending(row, before));
      if (!Objects.equals(owed.changedBy, changedBy)) {
        owed.named = false;
      }
      owed.changedBy = changedBy;
      if (owed.changed()) {
        if (owed.qualifiedId == null) {
          owed.qualifiedId = qualifiedId(row);
        }
        if (!owed.named) {
          name(owed);
        }
      }
    } catch (RuntimeException e) {
      LOG.warnf(e, "Could not owe the assignee change of %s", row.id);
    }
  }

  /** Announces each row that ends the transaction with a different assignee, after a commit. */
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
          .onAssigneeChanged(
              new AssigneeChange(
                  row.id,
                  row.projectId,
                  row.archetype,
                  row.number,
                  owed.qualifiedId,
                  row.title,
                  row.status,
                  row.assignee,
                  owed.before,
                  row.assigneeClaimed,
                  owed.changedBy,
                  owed.changedByName == null ? owed.changedBy : owed.changedByName,
                  at));
    } catch (RuntimeException e) {
      // The port must not throw; the write is committed whatever happens here.
      LOG.warnf(e, "Could not announce the assignee change of %s", row.id);
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

  /** How a reader names {@code owed}'s writer, read now, inside the write: a decoration too. */
  private void name(Pending owed) {
    owed.named = true;
    owed.changedByName = null;
    if (names.isUnsatisfied()) {
      return;
    }
    try {
      owed.changedByName = names.get().nameOf(owed.changedBy);
    } catch (RuntimeException e) {
      LOG.warnf(e, "Could not name %s, who changed the assignee of %s", owed.changedBy, owed.row.id);
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
