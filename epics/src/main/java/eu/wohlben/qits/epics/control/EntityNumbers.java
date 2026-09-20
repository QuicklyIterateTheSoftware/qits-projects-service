package eu.wohlben.qits.epics.control;

import eu.wohlben.qits.epics.entity.WorkEntity;
import io.quarkus.hibernate.orm.PersistenceUnit;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

/**
 * <b>The per-project numeric id allocator</b> (V11): the one place a {@link WorkEntity#number} comes
 * from. Unique within the project, never reused, gap-tolerant.
 *
 * <h2>Why it is here and not beside {@code ProjectService}</h2>
 *
 * <p>{@code domain}'s {@code ProjectService} is the obvious neighbour — it is where the platform's
 * other per-project derivation lives, the one that caps a project slug at 31 so {@code
 * <slug>-<slug>} still fits a git-host repository id. It is also the wrong module, and not by a
 * little: <b>{@code epics} depends on {@code domain} nowhere and on no auth module, and it stays
 * that way</b> — it is the module most likely to be lifted out next, and a lift-out that dragged
 * {@code domain} behind it would move a database rather than tables out of somebody else's.
 *
 * <p>So the tension is resolved the way this module has already resolved exactly this tension once:
 * {@link Slugs#slugify} is <em>a deliberate copy</em> of {@code ProjectService.slugify}, duplicated
 * rather than shared, with each side told to change the other. The idiom is copied; the dependency
 * is not. Here even the copy is unnecessary, because there is nothing in {@code ProjectService} to
 * copy — a project slug is allocated against the {@code project} table in the {@code projects}
 * database, and an entity number is allocated against the {@code entity} table in the {@code epics}
 * database. They are two different physical databases, so a shared allocator could not have been one
 * transaction with either write even if the module boundary had allowed it. The rule and its reason
 * are the thing that travels, and they are restated above.
 *
 * <h2>Two properties that pull against each other, and the answer that holds both</h2>
 *
 * <ul>
 *   <li><b>Two simultaneous creates cannot collide.</b>
 *   <li><b>A rolled-back create does not make its number reappear.</b>
 * </ul>
 *
 * <p>A {@code max(number) + 1} read-then-write gives <em>neither</em>: two creates read one maximum
 * and write one number, and a rollback hands the number straight back. A postgres <b>sequence</b>
 * gives both — {@code nextval} is non-transactional, so a rollback leaves a gap — but a sequence
 * <em>per project</em> means one {@code create sequence} per project, DDL the deployer does not run
 * and that no migration in this lineage could account for afterwards.
 *
 * <p>So this is the third answer: a <b>counter row</b> ({@code entity_number_sequence}), bumped by
 * {@code update ... set next_number = next_number + n}, which takes a row lock and is atomic under
 * READ COMMITTED — that is the first property — inside <b>a transaction of its own</b>, which
 * commits before the create's transaction does anything further, and that is the second. It is a
 * sequence, emulated in a row, with the one behaviour that matters preserved: the number leaves the
 * counter for good the moment it is handed out.
 *
 * <p><b>Gaps are therefore ordinary and are fine.</b> The id is a name, not a count: nothing sums
 * it, nothing pages by it, and nothing reads a missing number as a missing row.
 *
 * <h2>What it costs</h2>
 *
 * <p>One extra short transaction per created row — a second pooled connection held for the length of
 * two statements, nested inside the create's own transaction. That is the price of the second
 * property and there is no cheaper way to buy it short of the per-project DDL. What it buys back is
 * real: the counter's row lock is held for the bump alone rather than for the whole create, so two
 * people planning in one project do not serialise on each other's slug reads and audit writes.
 *
 * <p>{@link #allocate(String, int)} is the batch form, for a write that mints several rows at once
 * ({@code EpicService.supersede} copies a whole feature/task tree) — one bump for the block instead
 * of one per row.
 *
 * <h2>Where the first number comes from</h2>
 *
 * <p>V11 seeds {@code entity_number_sequence} with {@code max(number) + 1} per project over the rows
 * it has just backfilled, so the allocator starts <em>above</em> the highest backfilled value
 * without ever reading one. A project with no entity row has no counter row at all, and the insert
 * below mints it at 1 on first use — which is also why nothing has to write this table when a
 * project is created or deleted.
 */
@ApplicationScoped
public class EntityNumbers {

  /**
   * The epics persistence unit. Only native statements are issued through it, and deliberately: the
   * bump is two plain SQL statements against a table with no entity class, so there is no session
   * state to reason about across the nested transaction boundary.
   */
  @Inject
  @PersistenceUnit("epics")
  EntityManager entityManager;

  /** The next number for {@code projectId}. */
  public long next(String projectId) {
    return allocate(projectId, 1);
  }

  /**
   * A block of {@code count} consecutive numbers for {@code projectId}, answering the first of them.
   * The caller owns every number in {@code [answer, answer + count)}; a caller that then writes
   * fewer rows than it asked for simply leaves a gap.
   */
  public long allocate(String projectId, int count) {
    Validations.requireText(projectId, "projectId");
    if (count < 1) {
      throw new IllegalArgumentException("count must be at least 1, was " + count);
    }
    // ITS OWN TRANSACTION, and that is the whole design rather than a detail: it commits
    // independently of the create that asked for it, so a create that rolls back leaves a gap
    // instead of handing the number back. See the class javadoc.
    return QuarkusTransaction.requiringNew().call(() -> bump(projectId, count));
  }

  /**
   * The bump, in two statements and no {@code returning} clause.
   *
   * <p>The insert is {@code on conflict do nothing}, which is one statement with no read-then-write
   * window: it says "this project already has a counter" in exactly the words the primary key
   * already says it. V10's header makes the same argument about the same idiom.
   *
   * <p>The update takes the row's write lock and is atomic under READ COMMITTED — a concurrent
   * bumper blocks on it and then re-evaluates {@code next_number + n} against the committed value,
   * which is what makes two simultaneous creates unable to collide. The select that follows is in
   * the same transaction and therefore reads this transaction's own uncommitted value, so the two
   * statements together are a read of what this caller has just taken.
   */
  private long bump(String projectId, int count) {
    entityManager
        .createNativeQuery(
            "insert into entity_number_sequence (project_id, next_number) values (:project, 1)"
                + " on conflict (project_id) do nothing")
        .setParameter("project", projectId)
        .executeUpdate();
    entityManager
        .createNativeQuery(
            "update entity_number_sequence set next_number = next_number + :count"
                + " where project_id = :project")
        .setParameter("count", (long) count)
        .setParameter("project", projectId)
        .executeUpdate();
    Object taken =
        entityManager
            .createNativeQuery(
                "select next_number from entity_number_sequence where project_id = :project")
            .setParameter("project", projectId)
            .getSingleResult();
    return ((Number) taken).longValue() - count;
  }
}
