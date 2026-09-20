package eu.wohlben.qits.entities.control;

import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.entities.persistence.WorkEntityRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import java.sql.SQLTransientConnectionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.hibernate.exception.JDBCConnectionException;

/**
 * The merged planning table with a postgres cutover in the middle of a <b>write</b>: the row is
 * staged, and then the insert throws what a caller sees when its connection dies mid-flight. That is
 * the insert an epic create makes now — {@code EpicService} writes {@code entity} and mirrors the
 * old row behind it, so this is the write that decides whether the create landed.
 *
 * <p><b>The order is the whole point.</b> {@code super.persist} runs first, so the failure lands
 * <em>after</em> the write is in the transaction rather than before it. That is what makes the
 * exactly-once question a real one — a retry that re-ran the body on the transaction that already
 * held the row would leave two epics behind. The sibling {@link ConnectionLosingEpics} fails a read
 * and could never show that.
 *
 * <p>Two failure shapes, because {@code DbRetry.inNewTx} must tell them apart:
 *
 * <ul>
 *   <li>{@link #loseTheConnection} throws Hibernate's {@code JDBCConnectionException} wrapping
 *       postgres' {@code 57P01} ("terminating connection due to administrator command") — the real
 *       thing the server says to every open connection while it is being replaced, and the cause
 *       chain the retry actually walks. A stand-in marker would prove the loop runs and not that it
 *       fires on a cutover.
 *   <li>{@link #failWithoutLosingTheConnection} throws a plain runtime failure standing for every
 *       write that would fail identically on the second attempt — a constraint violation, a bug.
 *       Nothing in its chain says "connection", so nothing about it may be retried.
 * </ul>
 *
 * <p><b>{@code @Alternative} with no {@code @Priority}</b>: one test profile enables it and it is
 * inert everywhere else in this suite, which matters more here than for a read — a globally enabled
 * one would sit in the path of every planning insert the module's other tests make.
 */
@Alternative
@ApplicationScoped
public class FailingEpicWrites extends WorkEntityRepository {

  /** The message the non-connection arm fails with, so a test can name it rather than a type. */
  public static final String NOT_THE_CONNECTION = "the epic slug is already taken";

  private final AtomicInteger cutovers = new AtomicInteger();
  private final AtomicInteger otherFailures = new AtomicInteger();

  /** Arms the next {@code count} inserts to fail as a severed connection does, after staging. */
  public void loseTheConnection(int count) {
    cutovers.set(count);
    otherFailures.set(0);
  }

  /** Arms the next {@code count} inserts to fail for a reason a retry could not fix. */
  public void failWithoutLosingTheConnection(int count) {
    cutovers.set(0);
    otherFailures.set(count);
  }

  /** Clears both arms. */
  public void healthy() {
    cutovers.set(0);
    otherFailures.set(0);
  }

  /** How many armed failures were never used — the attempt count, read from the other end. */
  public int unspent() {
    return Math.max(0, cutovers.get()) + Math.max(0, otherFailures.get());
  }

  @Override
  public void persist(WorkEntity entity) {
    super.persist(entity);
    if (cutovers.getAndDecrement() > 0) {
      throw new JDBCConnectionException(
          "Unable to acquire JDBC Connection",
          new SQLTransientConnectionException(
              "terminating connection due to administrator command", "57P01"));
    }
    if (otherFailures.getAndDecrement() > 0) {
      throw new IllegalStateException(NOT_THE_CONNECTION);
    }
  }
}
