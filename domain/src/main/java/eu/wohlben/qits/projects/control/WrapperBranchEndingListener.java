package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.entity.BranchDelivery.Step;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.persistence.ReleaseRequestRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import org.jboss.logging.Logger;

/**
 * <b>A wrapper branch's delivery ending closes its ticket</b> (qits-150): hears {@link
 * BranchDeliveries}' steps and tells {@link WrapperBranchEndings} — once the step's transaction has
 * committed — when a branch of the project's wrapper repository
 *
 * <ul>
 *   <li>goes <b>LIVE</b>: the request carrying it is FINALIZED ({@link
 *       WrapperBranchEndings.Ending#FINALIZED}); or
 *   <li>goes <b>WITHDRAWN</b> because its request's fold adds nothing to main ({@link
 *       ReleaseRequests#EMPTY_WRAPPER_WITHDRAWN}, {@link WrapperBranchEndings.Ending#EMPTY}). Every
 *       other withdrawal — a person's, a deleted branch, a withdrawn request with work still on it —
 *       ends nothing, and is not told.
 * </ul>
 *
 * <p><b>Keyed on the branch's ledger row, never on the request it first joined.</b> The ledger
 * follows a branch through every hand-on and obsolescence (MOVED, then the successor's RELEASED and
 * LIVE — {@link BranchDeliveries#superseded}, {@link BranchDeliveries#carriesReleased}), so the
 * request that finalizes is the one that actually carried the branch to {@code main}, whichever it
 * joined first. The step names that request, and that is what the ticket's thread is told.
 *
 * <p><b>The wrapper</b> is the project's {@link ApprovalPolicy#isEstateWrapper estate wrapper} —
 * the {@code PROJECT} repository, the same row {@code ProjectService.findWrapper} answers and the
 * one a ticket's agent is dispatched on. A branch going live in any other repository is a member
 * release, and no ticket is closed by it.
 *
 * <p><b>Inside the step's transaction it only reads</b> (the repository's archetype, the request's
 * detail) — the listener contract. The port is asked after the commit on a worker thread, with no
 * transaction on it, because the ticket lives in the entities module's own database. A rolled-back
 * step tells nothing. A port that could not close the ticket leaves it to the reconcile on the far
 * side ({@code TicketWrapperDone.reconcile}), which reads the same ledger row.
 */
@ApplicationScoped
public class WrapperBranchEndingListener implements BranchDeliveryStepListener {

  private static final Logger LOG = Logger.getLogger(WrapperBranchEndingListener.class);

  private static final Object PENDING = WrapperBranchEndingListener.class.getName() + ".pending";

  @Inject ApprovalPolicy approvalPolicy;

  @Inject ReleaseRequestRepository requests;

  @Inject Instance<WrapperBranchEndings> endings;

  @Inject Instance<TransactionSynchronizationRegistry> transactions;

  private ExecutorService worker;

  @PostConstruct
  void start() {
    worker = Executors.newVirtualThreadPerTaskExecutor();
  }

  @PreDestroy
  void stop() {
    worker.shutdownNow();
  }

  @Override
  public void onStep(BranchDeliveryStep step) {
    if (step == null
        || step.branch() == null
        || step.requestId() == null
        || !endings.isResolvable()) {
      return;
    }
    WrapperBranchEndings.Ending ending;
    if (Step.LIVE.name().equals(step.step())) {
      ending = WrapperBranchEndings.Ending.FINALIZED;
    } else if (Step.WITHDRAWN.name().equals(step.step())) {
      ending = WrapperBranchEndings.Ending.EMPTY;
    } else {
      return;
    }
    if (!approvalPolicy.isEstateWrapper(step.repoId())) {
      return;
    }
    if (ending == WrapperBranchEndings.Ending.EMPTY && !emptyWithdrawal(step.requestId())) {
      return;
    }
    owe(
        new WrapperBranchEndings.Ended(
            step.projectId(),
            step.repoId(),
            step.branch(),
            step.requestId(),
            step.qualifiedId(),
            ending == WrapperBranchEndings.Ending.FINALIZED ? step.version() : null,
            ending));
  }

  /**
   * Whether {@code requestId} was withdrawn for carrying nothing. Read in the step's transaction, so
   * it sees the detail the withdrawal wrote before stepping its branches.
   */
  private boolean emptyWithdrawal(String requestId) {
    ReleaseRequest row = requests.findByIdOptional(requestId).orElse(null);
    return row != null
        && row.state == ReleaseRequest.State.WITHDRAWN
        && ReleaseRequests.EMPTY_WRAPPER_WITHDRAWN.equals(row.detail);
  }

  /** Tells the port once the active transaction commits; at once where there is none. */
  private void owe(WrapperBranchEndings.Ended ended) {
    TransactionSynchronizationRegistry registry = activeTransaction();
    if (registry == null) {
      submit(ended);
      return;
    }
    try {
      @SuppressWarnings("unchecked")
      List<WrapperBranchEndings.Ended> pending =
          (List<WrapperBranchEndings.Ended>) registry.getResource(PENDING);
      if (pending == null) {
        List<WrapperBranchEndings.Ended> owed = new ArrayList<>();
        registry.putResource(PENDING, owed);
        registry.registerInterposedSynchronization(
            new Synchronization() {
              @Override
              public void beforeCompletion() {}

              @Override
              public void afterCompletion(int status) {
                if (status == Status.STATUS_COMMITTED) {
                  owed.forEach(WrapperBranchEndingListener.this::submit);
                }
              }
            });
        pending = owed;
      }
      pending.add(ended);
    } catch (RuntimeException e) {
      LOG.warnf(
          e,
          "Could not owe the ending of wrapper branch %s (%s); the reconcile will find it",
          ended.branch(),
          ended.qualifiedId());
    }
  }

  private void submit(WrapperBranchEndings.Ended ended) {
    try {
      worker.submit(() -> tell(ended));
    } catch (RejectedExecutionException e) {
      LOG.debugf("Shutting down; the ending of %s is left to the reconcile", ended.branch());
    }
  }

  private void tell(WrapperBranchEndings.Ended ended) {
    try {
      endings.get().ended(ended);
    } catch (RuntimeException e) {
      // The port says it must not throw; a throw is a port bug and the reconcile has the row.
      LOG.warnf(
          e,
          "Could not close the work item of wrapper branch %s (%s %s)",
          ended.branch(),
          ended.qualifiedId(),
          ended.ending());
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
