package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.entity.ReleaseRequestSource;
import eu.wohlben.qits.projects.persistence.ReleaseRequestSourceRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;

/**
 * <b>The one place a release request's state is written</b> (qits-1153, W7), and so the one place
 * {@code ReleaseRequestStateChanged} is announced from.
 *
 * <p>Every writer calls {@link #move} instead of assigning {@link ReleaseRequest#state};
 * {@code ReleaseRequestStatesRuleTest} fails the build on an assignment anywhere else in {@code
 * domain}. A move to the state the row already has is a no-op and announces nothing.
 *
 * <h2>Announced after the commit, once per request</h2>
 *
 * <p>The move registers the row on the transaction active on this thread. When that transaction
 * commits, each registered request whose state differs from the state it had before the
 * transaction is announced once, through {@link ReleaseRequestStateAnnouncer}, on this thread, so
 * the bus takes the cause from the same causation scope. So:
 *
 * <ul>
 *   <li>a transaction that rolls back announces nothing — which is also what keeps a retried write
 *       ({@code DbRetry}, a re-run {@code requiringNew} body) from announcing twice;
 *   <li>two moves of one request in one transaction announce one change, from the first state to
 *       the last, and none when the last equals the first;
 *   <li>the fields are read when the transaction ends, so a version or a detail written after the
 *       move in the same transaction travels with it.
 * </ul>
 *
 * <p>The bus is not transactional: its outbox writes in a transaction of its own. "After the
 * commit" is therefore the strongest promise there is — the rule {@code ReleaseRequestChanged} and
 * {@code RepositoryRenamed} follow, made automatic here. A crash between the commit and the publish
 * loses the announcement; the request still reads right over REST.
 *
 * <p>A move with no transaction active (nothing in this module does that) announces at once.
 */
@ApplicationScoped
public class ReleaseRequestStates {

  private static final Logger LOG = Logger.getLogger(ReleaseRequestStates.class);

  /** The key of this transaction's pending moves in the registry. */
  private static final Object PENDING = ReleaseRequestStates.class.getName() + ".pending";

  @Inject Instance<ReleaseRequestStateAnnouncer> announcers;

  @Inject Instance<TransactionSynchronizationRegistry> transactions;

  @Inject ReleaseRequestSourceRepository sources;

  @Inject BranchDeliveries deliveries;

  /** A request moved in this transaction, and the state it had before the first move. */
  private static final class Pending {
    final ReleaseRequest row;
    final ReleaseRequest.State before;
    List<String> branches;

    Pending(ReleaseRequest row, ReleaseRequest.State before) {
      this.row = row;
      this.before = before;
    }
  }

  /**
   * Sets {@code row}'s state to {@code to} and, if that is a change, announces it once the
   * transaction commits. A new row (state still null) is a move from nothing.
   *
   * <p>A move of an existing request to PENDING or READY also puts back to JOINED every branch the
   * delivery ledger still holds FAILED or CONFLICTED on it ({@link BranchDeliveries#resumed},
   * qits-1719): this is the one writer of the state, so every path back in front of the gates is
   * covered by this one call. The ledger reads {@code row.mergedSha} here, at the move, so a caller
   * that moves the request onto a new fold sets the sha first.
   *
   * @return whether the state changed
   */
  public boolean move(ReleaseRequest row, ReleaseRequest.State to) {
    ReleaseRequest.State before = row.state;
    if (before == to) {
      return false;
    }
    row.state = to;
    if (before != null
        && (to == ReleaseRequest.State.PENDING || to == ReleaseRequest.State.READY)) {
      deliveries.resumed(row);
    }
    TransactionSynchronizationRegistry registry = activeTransaction();
    if (registry == null) {
      Pending now = new Pending(row, before);
      now.branches = branchesOf(row.id);
      announce(now);
      return true;
    }
    try {
      @SuppressWarnings("unchecked")
      Map<String, Pending> pending = (Map<String, Pending>) registry.getResource(PENDING);
      if (pending == null) {
        pending = new LinkedHashMap<>();
        registry.putResource(PENDING, pending);
        registry.registerInterposedSynchronization(new AfterCommit(pending));
      }
      pending.putIfAbsent(row.id, new Pending(row, before));
    } catch (RuntimeException e) {
      LOG.warnf(e, "Could not register the state change of release request %s", row.id);
    }
    return true;
  }

  /** Reads the branches before the commit and announces after it. */
  private final class AfterCommit implements Synchronization {

    private final Map<String, Pending> pending;

    AfterCommit(Map<String, Pending> pending) {
      this.pending = pending;
    }

    @Override
    public void beforeCompletion() {
      for (Pending moved : pending.values()) {
        if (moved.row.state != moved.before) {
          moved.branches = branchesOf(moved.row.id);
        }
      }
    }

    @Override
    public void afterCompletion(int status) {
      if (status != Status.STATUS_COMMITTED) {
        return;
      }
      for (Pending moved : pending.values()) {
        if (moved.row.state != moved.before) {
          announce(moved);
        }
      }
    }
  }

  private List<String> branchesOf(String requestId) {
    try {
      return sources.listByRequest(requestId).stream()
          .filter(source -> source.kind == ReleaseRequestSource.Kind.BRANCH)
          .map(source -> source.name)
          .toList();
    } catch (RuntimeException e) {
      LOG.debugf(e, "Could not read the branches of release request %s", requestId);
      return null;
    }
  }

  private void announce(Pending moved) {
    if (!announcers.isResolvable()) {
      return;
    }
    ReleaseRequest row = moved.row;
    try {
      announcers
          .get()
          .onStateChanged(
              new ReleaseRequestStateAnnouncer.StateChange(
                  row.id,
                  row.qualifiedId,
                  row.projectId,
                  row.repoId,
                  row.repoName,
                  row.backingBranch(),
                  row.state.name(),
                  moved.before == null ? null : moved.before.name(),
                  row.updatedAt == null ? Instant.now() : row.updatedAt,
                  row.mergedSha,
                  row.version,
                  row.detail,
                  moved.branches));
    } catch (RuntimeException e) {
      LOG.warnf(e, "Could not announce the state change of release request %s", row.id);
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
