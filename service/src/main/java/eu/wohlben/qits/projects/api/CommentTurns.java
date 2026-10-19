package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.projects.control.WorkspaceAgentTurns;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.eclipse.microprofile.context.ThreadContext;
import org.jboss.logging.Logger;

/**
 * <b>A comment tells the item's agent</b> (qits-1448): one turn, {@code (<who> just commented on
 * <qualifiedId>)}, delivered through {@link WorkspaceAgentTurns} off the request thread, so a
 * comment POST never waits on a delivery hop that may launch a harness (its bound is 30 s).
 *
 * <p>Only {@code WorkEntityDoors.addComment} calls this — the door people, agents and machines
 * comment through. The in-service writers ({@code PhaseAdvance.say}, {@code EntityDispatch}, {@code
 * EntityBlocks}, {@code TicketUnattendedGateTickets}) write the thread directly and tell nobody.
 *
 * <p>Nothing is written on the thread about the delivery: the turn is itself caused by a comment,
 * and a comment about it would double every thread. What happened is logged and nothing else.
 *
 * <p>The executor propagates no context ({@code CampaignExecutor}'s shape): no request context, no
 * transaction, no causation scope. The task opens no transaction — the port is an HTTP hop.
 */
@ApplicationScoped
public class CommentTurns {

  private static final Logger LOG = Logger.getLogger(CommentTurns.class);

  @Inject Instance<WorkspaceAgentTurns> turns;

  ManagedExecutor async;

  /** Deliveries handed to {@link #async} and not yet finished — read by the suite to wait for quiet. */
  private final AtomicInteger pending = new AtomicInteger();

  @PostConstruct
  void start() {
    async =
        ManagedExecutor.builder()
            .propagated(ThreadContext.NONE)
            .cleared(ThreadContext.ALL_REMAINING)
            .build();
  }

  @PreDestroy
  void stop() {
    async.shutdown();
  }

  /** The turn's text: one line, parenthesised, no trailing period, no comment body. */
  public static String text(String who, String qualifiedId) {
    return "(" + who + " just commented on " + qualifiedId + ")";
  }

  /**
   * Hands {@code text} for the agent of {@code targetWorkId} to the executor and returns at once.
   * Nothing here throws: an absent port, a full executor and a port that breaks its never-throw
   * promise are each a log line.
   *
   * @param commentedOn the qualified id of the entity the comment is on, for the log line
   */
  public void tell(String targetWorkId, String commentedOn, String text) {
    if (!turns.isResolvable()) {
      return;
    }
    pending.incrementAndGet();
    try {
      async.runAsync(
          () -> {
            try {
              deliver(targetWorkId, commentedOn, text);
            } finally {
              pending.decrementAndGet();
            }
          });
    } catch (RejectedExecutionException | IllegalStateException e) {
      pending.decrementAndGet();
      LOG.warnf(
          "Could not hand the comment on %s to the agent of %s (%s)",
          commentedOn, targetWorkId, e.getMessage());
    }
  }

  private void deliver(String targetWorkId, String commentedOn, String text) {
    WorkspaceAgentTurns.Turn turn;
    try {
      turn = turns.get().deliver(targetWorkId, text);
    } catch (RuntimeException e) {
      // The port promises never to throw; a bug in it must not surface anywhere.
      LOG.warnf(e, "Could not tell the agent of %s about a comment on %s", targetWorkId, commentedOn);
      return;
    }
    if (turn == null) {
      return;
    }
    if (turn.spoken()) {
      LOG.infof(
          "Told the agent of %s about a comment on %s (%s)",
          targetWorkId, commentedOn, turn.outcome());
    } else if (turn.outcome() == WorkspaceAgentTurns.Outcome.NO_AGENT) {
      LOG.debugf(
          "The agent of %s was not told about a comment on %s: it has no agent",
          targetWorkId, commentedOn);
    } else {
      LOG.warnf(
          "Could not tell the agent of %s about a comment on %s: %s",
          targetWorkId, commentedOn, turn.detail());
    }
  }

  /** How many deliveries are still running; public for the suite, which waits for quiet. */
  public int pending() {
    return pending.get();
  }
}
