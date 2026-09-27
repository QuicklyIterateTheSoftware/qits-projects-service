package eu.wohlben.qits.entities.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.AuditEntityType;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.hibernate.exception.JDBCConnectionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * An epics <b>write</b> holds through a postgres cutover, and lands exactly once when it does.
 *
 * <p>One seam stands for every write wrapped in this module ({@code WorkEntityService}'s create,
 * update, transition, blocked and delete, for every archetype): they share {@link WritePatience}, so what
 * this pins is the wiring — {@code DbRetry.inNewTx} opens the transaction, a body failure that says
 * the connection went is retried, and everything else is reported on the first attempt.
 *
 * <p><b>Exactly once is the assertion that matters.</b> A retry is only worth having if a write that
 * was interrupted after being staged leaves one row and not two, and {@link FailingEpicWrites} fails
 * after {@code persist} precisely so that question has an answer. The count is read in a fresh
 * transaction, because this test calls the service on one thread with no request scope and a
 * thread-bound session would answer from its own cache.
 *
 * <p><b>The other half is the one that must NOT retry.</b> A constraint violation is as certain not
 * to have committed as a lost connection is, and as certain to fail the same way for fifteen
 * seconds; retrying it would turn one visible failure into a slow one. The proof is the attempt
 * count, not the clock: five failures armed, four still unspent.
 *
 * <p><b>The count is read from {@code entity} rather than from the legacy {@code epic} table, and
 * that is the one assertion the mirror's removal moved.</b> It used to go through {@code
 * EpicRepository}, which answered because {@code EpicService} wrote a legacy row behind every
 * create; with the mirror gone that table has no writer at all and the same query answers zero for
 * every case here — a green-looking nothing rather than a failure, which is why the subject had to
 * move rather than the number. The claim is unchanged: exactly one epic row, counted in the table
 * the create actually writes.
 */
@QuarkusTest
@TestProfile(EpicWriteCutoverTest.ImpatientWrites.class)
class EpicWriteCutoverTest extends EntitiesTestSupport {

  /**
   * Arms the failing epic table for this class alone, and shortens the write patience to one
   * second. The shipped deadline is fifteen, which the non-retry case would otherwise have to
   * outwait to prove it did not wait.
   */
  public static class ImpatientWrites implements QuarkusTestProfile {
    @Override
    public Set<Class<?>> getEnabledAlternatives() {
      return Set.of(FailingEpicWrites.class);
    }

    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("qits.entities.write-deadline", "1S");
    }
  }

  @Inject WorkEntityService workEntities;

  @Inject FailingEpicWrites epics;

  @Inject RecordingTransitionAnnouncer announcer;

  @BeforeEach
  void healthy() {
    epics.healthy();
    announcer.clear();
  }

  /**
   * <b>A retried move is announced once</b>, and what it announces is the attempt that committed.
   * A supersede is the transition that inserts — its successor draft — so it is the one {@link
   * FailingEpicWrites} can interrupt after staging: the first attempt dies with the successor in the
   * transaction, the body re-runs, and an announcement made inside the hold would have told the
   * platform about a successor that was rolled back.
   */
  @Test
  void aRetriedTransitionIsAnnouncedExactlyOnce() {
    var epic =
        workEntities
            .create(
                Archetype.EPIC,
                "proj-transition-cutover",
                EntityWrite.epic("Superseded through the cutover", null),
                "alice")
            .entity();
    workEntities.transition(Archetype.EPIC, epic.id, "REFINED", "alice");
    announcer.clear();
    epics.loseTheConnection(1);

    var moved =
        workEntities.transition(Archetype.EPIC, epic.id, WorkEntityService.SUPERSEDE, "alice");

    assertEquals(0, epics.unspent(), "the armed failure was never reached — nothing was retried");
    assertEquals(1, announcer.batches().size(), "a retried hold announced more than once");
    var batch = announcer.batches().get(0).entities();
    assertEquals(2, batch.size(), "the moved row and its successor: " + batch);
    assertEquals(epic.id, batch.get(0).id());
    assertEquals("REFINED", batch.get(0).statusBefore());
    assertEquals("DROPPED", batch.get(0).status());
    assertEquals("REFINED", moved.statusBefore());
    assertEquals(
        moved.successor().id,
        batch.get(1).id(),
        "the successor announced is the one that committed, not the rolled-back first attempt's");
    assertNull(batch.get(1).statusBefore(), "the successor was created by the move");
    assertEquals("REPORTED", batch.get(1).status());
    assertTrue(batch.stream().allMatch(entity -> "alice".equals(entity.changedBy())));
  }

  @Test
  void aCreateLandsExactlyOnceAfterTheWriteLosesItsConnection() {
    epics.loseTheConnection(1);

    var epic =
        workEntities
            .create(
                Archetype.EPIC,
                "proj-write-cutover",
                EntityWrite.epic("Held through the cutover", null),
                "alice")
            .entity();

    assertEquals(
        0, epics.unspent(), "the armed failure was never reached — the write did not go through");
    inFreshTx(
        () -> {
          assertEquals(
              1,
              workEntityRepository.listByProjectAndArchetype("proj-write-cutover", Archetype.EPIC).size(),
              "the retried create left more than one epic behind");
          assertEquals(
              1,
              auditRepository.listForEntity(AuditEntityType.EPIC, epic.id).size(),
              "the retried create left more than one audit row behind");
        });
  }

  /**
   * <b>A move whose commit fails is announced to nobody</b> — the half of "after the hold, never
   * inside it" a retry cannot show. The retryable failures all land inside the body, before the
   * batch exists, so an announcement at the very end of the body would pass the test above; it is
   * only a failure past the body's end that tells it from an announcement made after the write
   * returned.
   */
  @Test
  void aTransitionWhoseCommitFailsIsNotAnnounced() {
    var epic =
        workEntities
            .create(
                Archetype.EPIC,
                "proj-transition-commit",
                EntityWrite.epic("Never superseded", null),
                "alice")
            .entity();
    workEntities.transition(Archetype.EPIC, epic.id, "REFINED", "alice");
    announcer.clear();
    epics.failAtCommit(1);

    assertThrows(
        RuntimeException.class,
        () -> workEntities.transition(Archetype.EPIC, epic.id, WorkEntityService.SUPERSEDE, "alice"));

    assertEquals(0, epics.unspent(), "the commit failure was never armed");
    assertEquals(List.of(), announcer.batches(), "a move that never committed was announced");
  }

  /** A failure that is not the connection is reported at once, after exactly one attempt. */
  @Test
  void aFailureThatIsNotTheConnectionIsNotRetried() {
    epics.failWithoutLosingTheConnection(5);

    long startedAt = System.nanoTime();
    IllegalStateException reported =
        assertThrows(
            IllegalStateException.class,
            () ->
                workEntities
                    .create(
                        Archetype.EPIC,
                        "proj-not-retried",
                        EntityWrite.epic("Reported, not retried", null),
                        "alice")
                    .entity());
    long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

    assertEquals(FailingEpicWrites.NOT_THE_CONNECTION, reported.getMessage());
    assertEquals(4, epics.unspent(), "the write was attempted more than once");
    assertTrue(elapsedMs < 900, "waited " + elapsedMs + "ms — a non-connection failure paused");
    inFreshTx(
        () ->
            assertEquals(
                0,
                workEntityRepository.listByProjectAndArchetype("proj-not-retried", Archetype.EPIC).size(),
                "the failed create committed a row"));
  }

  /** A cutover that does not end is still a failure, reported at the deadline and not before. */
  @Test
  void aDatabaseThatStaysGoneFailsAtTheDeadline() {
    epics.loseTheConnection(1_000);

    long startedAt = System.nanoTime();
    assertThrows(
        JDBCConnectionException.class,
        () ->
            workEntities
                .create(
                    Archetype.EPIC,
                    "proj-write-gone",
                    EntityWrite.epic("Never lands", null),
                    "alice")
                .entity());
    long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

    assertTrue(
        elapsedMs >= 900, "gave up after " + elapsedMs + "ms — the write did not wait at all");
    assertTrue(
        elapsedMs < 10_000,
        "gave up after " + elapsedMs + "ms — the configured deadline is not the one in force");
    inFreshTx(
        () ->
            assertEquals(
                0,
                workEntityRepository.listByProjectAndArchetype("proj-write-gone", Archetype.EPIC).size(),
                "a create that never succeeded committed a row"));
  }
}
