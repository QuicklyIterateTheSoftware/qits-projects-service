package eu.wohlben.qits.projects.campaignhost;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * <b>Whether the criteria consumer is actually getting through its frames</b> (qits-416/qits-418)
 * — what {@code bus/CampaignCriteriaListener} records about itself, read by {@link
 * CampaignEvaluatorHealth} into the progress read's {@code evaluator} block.
 *
 * <p>Why it exists: on 2026-09-27 that consumer failed on every frame (it touched the {@code epics}
 * datasource inside the eventstream library's claim transaction, which Narayana refuses), the
 * library rolled each claim back and logged it, the watermark stopped — and the progress read went
 * on answering {@code connected: true, stalled: false}, because the stream WAS connected and the
 * sweep DID complete its passes. A consumer wedged on one frame and a consumer correctly waiting read
 * the same. This bean is what tells them apart.
 *
 * <p><b>What counts.</b> A failure is recorded when the listener's body throws (it then rethrows, so
 * the library rolls the claim back and the event stays owed — this bean only watches). A success is
 * recorded when the <em>claim transaction commits</em>, not when the body returns: a body that
 * returned into a claim that then failed to commit handled nothing, and is recorded as a failure.
 * Outside any transaction (the container-free unit test) a return is a success at once. {@link
 * #failing()} is "the newest outcome is a failure", ordered by a sequence rather than by instants so
 * two outcomes in one clock tick cannot tie.
 *
 * <p>{@code lastError}/{@code lastErrorAt} are the newest failure ever seen by this process and stay
 * after a recovery — {@link #failing()} is what says whether it is current. In-memory and per
 * process on purpose: it is a liveness read, and a restart that clears it also re-runs the catch-up
 * that would fail again.
 */
@ApplicationScoped
public class CampaignCriteriaConsumerHealth {

  /**
   * The criteria consumer's storage key, in {@code consumed_event} and {@code consumer_watermark}.
   * {@code bus/CampaignCriteriaListener} answers it as its {@code consumerId()}; it lives here so the
   * watermark read in {@link CampaignEvaluatorHealth} names the same row without {@code
   * campaignhost} depending on {@code bus}. <b>Never change it</b> — see the listener.
   */
  public static final String CONSUMER_ID = "projects-campaign-criteria";

  private static final int MAX_ERROR_LENGTH = 600;

  /** Everything recorded, swapped whole so a reader never sees half of one outcome. */
  record State(
      long sequence,
      long lastSuccessSequence,
      long lastFailureSequence,
      Instant lastHandledAt,
      Instant lastErrorAt,
      String lastError) {

    static final State EMPTY = new State(0, 0, 0, null, null, null);

    State succeeded(Instant at) {
      return new State(sequence + 1, sequence + 1, lastFailureSequence, at, lastErrorAt, lastError);
    }

    State failed(Instant at, String error) {
      return new State(sequence + 1, lastSuccessSequence, sequence + 1, lastHandledAt, at, error);
    }
  }

  @Inject Instance<TransactionSynchronizationRegistry> registry;

  private final AtomicReference<State> state = new AtomicReference<>(State.EMPTY);

  /**
   * The body returned for {@code frameName} {@code frameId}: a success once the transaction it ran
   * in commits, a failure if that transaction ends any other way; a success at once outside one.
   */
  public void handled(String frameName, String frameId) {
    TransactionSynchronizationRegistry tsr = activeRegistry();
    if (tsr == null) {
      succeeded();
      return;
    }
    tsr.registerInterposedSynchronization(
        new Synchronization() {
          @Override
          public void beforeCompletion() {}

          @Override
          public void afterCompletion(int status) {
            if (status == Status.STATUS_COMMITTED) {
              succeeded();
            } else {
              record(
                  frameName
                      + " "
                      + frameId
                      + ": the claim transaction did not commit (JTA status "
                      + status
                      + ")");
            }
          }
        });
  }

  /** The body threw on {@code frameName} {@code frameId}; the caller rethrows. */
  public void failed(String frameName, String frameId, Throwable error) {
    record(frameName + " " + frameId + ": " + describe(error));
  }

  /** Whether the newest recorded outcome is a failure. */
  public boolean failing() {
    State s = state.get();
    return s.lastFailureSequence() > s.lastSuccessSequence();
  }

  /** The newest failure, described, or null when there has been none. */
  public String lastError() {
    return state.get().lastError();
  }

  /** When the newest failure happened, or null. */
  public Instant lastErrorAt() {
    return state.get().lastErrorAt();
  }

  /** When a frame was last handled and committed, or null. */
  public Instant lastHandledAt() {
    return state.get().lastHandledAt();
  }

  /** The suite's reset: this bean outlives every test in the one application. */
  void reset() {
    state.set(State.EMPTY);
  }

  private void succeeded() {
    Instant now = Instant.now();
    state.updateAndGet(s -> s.succeeded(now));
  }

  private void record(String error) {
    Instant now = Instant.now();
    String bounded =
        error.length() > MAX_ERROR_LENGTH ? error.substring(0, MAX_ERROR_LENGTH) + "…" : error;
    state.updateAndGet(s -> s.failed(now, bounded));
  }

  private TransactionSynchronizationRegistry activeRegistry() {
    if (registry == null || !registry.isResolvable()) {
      return null;
    }
    try {
      TransactionSynchronizationRegistry tsr = registry.get();
      int status = tsr.getTransactionStatus();
      return status == Status.STATUS_ACTIVE || status == Status.STATUS_MARKED_ROLLBACK
          ? tsr
          : null;
    } catch (RuntimeException e) {
      return null;
    }
  }

  /**
   * The exception's class and message, and its root cause's when that is a different one — the
   * Narayana refusal arrives several wrappers deep, and the wrapper alone says nothing.
   */
  static String describe(Throwable error) {
    Throwable root = error;
    while (root.getCause() != null && root.getCause() != root) {
      root = root.getCause();
    }
    String outer = error.getClass().getName() + ": " + error.getMessage();
    return root == error
        ? outer
        : outer + " (root cause " + root.getClass().getName() + ": " + root.getMessage() + ")";
  }
}
