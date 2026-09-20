package eu.wohlben.qits.entities.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.entities.persistence.WorkEntityRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * The per-project numeric id (V11): that it is allocated on every create path, that it is per
 * project rather than per archetype, and — the two claims that are the whole reason the allocator is
 * a counter row bumped in its own transaction rather than a {@code max(n) + 1} — that <b>two
 * simultaneous creates cannot collide</b> and that <b>a rolled-back create does not make its number
 * reappear</b>.
 *
 * <p>Both of those are asserted with an <b>actually concurrent</b> run and an <b>actually
 * rolled-back</b> transaction. Neither can be argued from a single-threaded call: a {@code max(n) +
 * 1} allocator passes every sequential assertion in this class and fails the two below, which is
 * exactly why they are here.
 */
@QuarkusTest
class EntityNumbersTest extends EntitiesTestSupport {

  @Inject EntityNumbers numbers;
  @Inject EpicService epicService;
  @Inject FeatureService featureService;
  @Inject TaskService taskService;
  @Inject TicketService ticketService;
  @Inject WorkEntityRepository entities;

  // ---- the two claims that need more than one thread or more than one outcome ------------------

  /**
   * <b>Sixteen threads, twenty allocations each, in one project: 320 numbers and not one repeat.</b>
   *
   * <p>This is the claim a comment cannot make. A read-then-write allocator — {@code select
   * max(number) + 1}, which is the obvious implementation and the wrong one — hands the same number
   * to every thread that reads between two writes, and it does so reliably enough that this test
   * fails on the first run rather than one in a hundred.
   *
   * <p>It exercises {@link EntityNumbers} directly rather than through a create, so that what is
   * under contention is the counter and nothing else: a failure here is the allocator, and a failure
   * in {@link #twoSimultaneousCreatesInOneProjectTakeDifferentNumbers} below is the wiring.
   */
  @Test
  void aHundredsOfConcurrentAllocationsInOneProjectNeverRepeatANumber() throws Exception {
    int threads = 16;
    int perThread = 20;
    List<Long> allocated = concurrently(threads, () -> {
      List<Long> mine = new ArrayList<>();
      for (int i = 0; i < perThread; i++) {
        mine.add(numbers.next("proj-contended"));
      }
      return mine;
    });

    assertEquals(threads * perThread, allocated.size());
    assertEquals(
        threads * perThread,
        Set.copyOf(allocated).size(),
        "an entity number was handed out twice: " + allocated);
    // The counter started at 1 (no row in the project, so no seed) and nothing rolled back, so the
    // block is exactly 1..320 — gap-free HERE even though gaps are permitted in general.
    assertEquals(1L, allocated.stream().mapToLong(Long::longValue).min().orElseThrow());
    assertEquals(
        (long) threads * perThread,
        allocated.stream().mapToLong(Long::longValue).max().orElseThrow());
  }

  /**
   * <b>The same claim through the real create path</b>: eight threads each creating an epic in one
   * project at once, eight distinct numbers on the eight rows.
   *
   * <p>Eight and not sixteen, deliberately: a create holds its own transaction's connection while
   * the allocator opens a second, so the pool ceiling is what bounds this and not the allocator.
   * That cost is stated in {@link EntityNumbers}' javadoc and this is where it is visible.
   */
  @Test
  void twoSimultaneousCreatesInOneProjectTakeDifferentNumbers() throws Exception {
    int threads = 8;
    List<String> ids =
        concurrently(
            threads,
            () -> List.of(epicService.create("proj-1", "Epic " + Thread.currentThread().threadId(), null, "t").id));

    assertEquals(threads, ids.size());
    List<Long> allocated = new ArrayList<>();
    inFreshTx(() -> ids.forEach(id -> allocated.add(entities.findById(id).number)));
    assertEquals(threads, Set.copyOf(allocated).size(), "two epics share a number: " + allocated);
  }

  /**
   * <b>A rolled-back allocation leaves a gap and never comes back.</b>
   *
   * <p>The allocation in the middle happens inside a transaction that then throws, so everything
   * that transaction wrote is undone — and the number is gone anyway, because the bump committed in
   * a transaction of its own. That is the property a database sequence has and a counter row updated
   * in the caller's transaction does not: the latter would hand {@code n + 1} back and the third
   * allocation below would equal the second.
   */
  @Test
  void aRolledBackAllocationLeavesAGapRatherThanReusingTheNumber() {
    long first = numbers.next("proj-rollback");

    AtomicReference<Long> discarded = new AtomicReference<>();
    assertThrows(
        IllegalStateException.class,
        () ->
            QuarkusTransaction.requiringNew()
                .run(
                    () -> {
                      discarded.set(numbers.next("proj-rollback"));
                      throw new IllegalStateException("the create this number was for failed");
                    }));

    long third = numbers.next("proj-rollback");

    assertEquals(first + 1, discarded.get(), "the discarded allocation was not the next number");
    assertEquals(first + 2, third, "the rolled-back number was handed out a second time");
  }

  // ---- what the number IS ----------------------------------------------------------------------

  /**
   * <b>The id names a node, not a ticket.</b> An epic, a feature under it, a task under that and a
   * ticket beside all of them draw from ONE run of integers, because the unified table holds every
   * archetype and {@code uq_entity_project_number} is over {@code (project_id, number)} alone.
   */
  @Test
  void everyArchetypeInAProjectDrawsFromOneRunOfIntegers() {
    WorkEntity epic = epicService.create("proj-1", "The epic", null, "t");
    Nested feature = featureService.create(epic.id, "The feature", null, null, "t");
    Nested task = taskService.create(feature.entity().id, "repo-1", "The task", null, null, "t");
    WorkEntity ticket =
        ticketService.create("proj-1", "The ticket", "it occurs", null, "BUG", null, "t");

    assertEquals(1L, numberOf(epic.id));
    assertEquals(2L, numberOf(feature.entity().id));
    assertEquals(3L, numberOf(task.entity().id));
    assertEquals(4L, numberOf(ticket.id));
  }

  /** Per project, so the numbers stay small and the qualified form carries its own scope. */
  @Test
  void eachProjectHasItsOwnRunAndBothStartAtOne() {
    WorkEntity here = epicService.create("proj-1", "Here", null, "t");
    WorkEntity there = epicService.create("proj-2", "There", null, "t");
    WorkEntity alsoHere = epicService.create("proj-1", "Also here", null, "t");

    assertEquals(1L, numberOf(here.id));
    assertEquals(1L, numberOf(there.id));
    assertEquals(2L, numberOf(alsoHere.id));
  }

  /**
   * A superseded epic's tree is copied into a successor draft, and <b>every copy is a new entity
   * with a number of its own</b> — the source keeps the number it has, because it stays in the list
   * as the record of what was discarded and two rows sharing a number would make the qualified form
   * ambiguous inside the very project that scopes it.
   */
  @Test
  void supersedingNumbersTheWholeCopiedTreeAfresh() {
    WorkEntity epic = epicService.create("proj-1", "Plan", null, "t");
    Nested feature = featureService.create(epic.id, "Feature", null, null, "t");
    taskService.create(feature.entity().id, "repo-1", "Task", null, null, "t");
    epicService.transition(epic.id, "IMPLEMENTATION", "t");

    epicService.transition(epic.id, "SUPERSEDED", "t");

    inFreshTx(
        () -> {
          List<WorkEntity> rows = entities.listByProject("proj-1");
          // 3 originals + 3 copies, six distinct numbers.
          assertEquals(6, rows.size());
          assertEquals(
              6,
              rows.stream().map(row -> row.number).collect(java.util.stream.Collectors.toSet()).size(),
              "a copy shares a number with the row it was copied from");
          assertTrue(rows.stream().allMatch(row -> row.number > 0));
        });
  }

  /** A re-archetype creates nothing, so it allocates nothing and the row keeps its number. */
  @Test
  void aTransitionAllocatesNothing() {
    WorkEntity epic = epicService.create("proj-1", "Plan", null, "t");
    Nested feature = featureService.create(epic.id, "Feature", null, null, "t");
    long before = numberOf(feature.entity().id);

    long after = numbers.next("proj-1");

    // Nothing but the two creates has drawn from this project's run.
    assertEquals(2L, before);
    assertEquals(3L, after);
  }

  // ---- fixtures --------------------------------------------------------------------------------

  private long numberOf(String entityId) {
    AtomicReference<Long> number = new AtomicReference<>();
    inFreshTx(() -> number.set(entities.findById(entityId).number));
    return number.get();
  }

  /**
   * Runs {@code work} on {@code threads} threads that are held at a latch until every one of them is
   * ready, so the calls genuinely overlap rather than merely being submitted together, and answers
   * everything they produced.
   */
  private static <T> List<T> concurrently(int threads, ThrowingWork<T> work) throws Exception {
    CountDownLatch ready = new CountDownLatch(threads);
    CountDownLatch go = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      List<Future<List<T>>> futures = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        futures.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  go.await(30, TimeUnit.SECONDS);
                  return work.run();
                }));
      }
      assertTrue(ready.await(30, TimeUnit.SECONDS), "threads never became ready");
      go.countDown();

      List<T> all = new ArrayList<>();
      for (Future<List<T>> future : futures) {
        all.addAll(future.get(60, TimeUnit.SECONDS));
      }
      return all;
    } finally {
      pool.shutdownNow();
    }
  }

  @FunctionalInterface
  private interface ThrowingWork<T> {
    List<T> run() throws Exception;
  }
}
