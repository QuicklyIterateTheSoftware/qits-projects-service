package eu.wohlben.qits.epics.migration;

import io.quarkus.hibernate.orm.PersistenceUnit;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.hibernate.Session;

/**
 * <b>The three lines that put {@link MigrationVerification} on a live connection.</b> It holds no
 * rule, makes no decision and must never grow one: everything the door asserts is in the pure class,
 * so the same comparison runs against production, against an embedded postgres in a plain JUnit
 * test, and against a psql session, with nothing to keep in step.
 *
 * <p><b>TEMPORARY.</b> Deleted by V13 with the rest of this package — see {@code package-info.java}.
 *
 * <h2>Why a raw {@link java.sql.Connection} and not the entity manager's own queries</h2>
 *
 * <p>Because the four old tables have no JPA mapping worth having. {@code EpicRepository} and its
 * three siblings are still on disk with <b>zero injections in {@code src/main}</b> and the cleanup
 * deletes them; wiring them back in here to run a verification would resurrect exactly the coupling
 * the cutover removed, and would then have to be undone again. And because every check is one
 * set-based statement over whole tables — an anti-join, a window function, a lateral unpivot — which
 * is SQL the ORM would only be in the way of.
 *
 * <p>{@code EntityNumbers} sets the precedent for the injection point: the {@code epics} persistence
 * unit by name, used for native statements alone.
 *
 * <h2>Why a transaction, and why {@code requiringNew}</h2>
 *
 * <p>A Hibernate {@link Session} needs a context to hand out a connection at all, and the cheapest
 * way to guarantee one wherever this is called from — a request thread, a scheduled sweep, a test —
 * is to open one rather than to depend on the caller having done so. {@code requiringNew} because
 * this must never join somebody else's write: it is a read that scans whole tables, and suspending
 * it into an unrelated transaction would hold that transaction open for the length of the scan.
 *
 * <p>It buys no snapshot and does not claim one. Statements run at READ COMMITTED and each sees its
 * own instant; {@link MigrationVerification}'s javadoc argues why that is harmless here — the old
 * tables cannot move at all, and a row arriving in {@code entity} mid-run lands in an informational
 * category rather than a failing one.
 */
@ApplicationScoped
public class MigrationVerificationService {

  /** The epics persistence unit. Only the connection under it is ever used. */
  @Inject
  @PersistenceUnit("epics")
  EntityManager epics;

  /**
   * Runs the whole comparison and answers the report.
   *
   * <p>Nothing catches a {@link java.sql.SQLException} on the way out. {@code doReturningWork} declares it,
   * Hibernate converts it to its own unchecked exception, and the resource answers a 500 carrying
   * the driver's message — which is what an operator needs. Swallowing it into an empty report would
   * be the worst answer available: a clean-looking document produced by a comparison that did not
   * run.
   */
  public VerificationReport verify() {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                epics.unwrap(Session.class).doReturningWork(MigrationVerification::compare));
  }
}
