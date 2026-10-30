package eu.wohlben.qits.entities.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.EntityStatus;
import eu.wohlben.qits.entities.entity.Idea;
import eu.wohlben.qits.entities.entity.IdeaStatus;
import eu.wohlben.qits.entities.entity.WorkEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * An idea's derived, stored status (qits-1387): PLANNED with no counted work, QUEUED while some is
 * open, DONE once every counted item is DONE — VERIFIED is not enough, DROPPED is not counted — and
 * re-derived in the transaction of every write that can move it.
 */
@QuarkusTest
class IdeaStatusRecomputeTest extends EntitiesTestSupport {

  private static final String PROJECT = "idea-status-proj";

  private static final List<EntityStatus> WALK =
      List.of(
          EntityStatus.REPORTED,
          EntityStatus.REFINED,
          EntityStatus.READY_FOR_DEV,
          EntityStatus.IMPLEMENTING,
          EntityStatus.IMPLEMENTED,
          EntityStatus.VERIFYING,
          EntityStatus.VERIFIED,
          EntityStatus.DONE);

  @Inject IdeaService ideas;

  @Inject IdeaStatusRecompute recompute;

  @Inject WorkEntityService work;

  @Inject EntityTransitionService transitions;

  @Inject RecordingIdeaAnnouncer announced;

  @BeforeEach
  void quiet() {
    announced.clear();
  }

  /** Read in a transaction of its own: a read outside one would answer the test's cached row. */
  private IdeaStatus statusOf(Idea idea) {
    return QuarkusTransaction.requiringNew().call(() -> ideaRepository.findById(idea.id).status);
  }

  private String inspiredByOf(WorkEntity row) {
    return QuarkusTransaction.requiringNew()
        .call(() -> workEntityRepository.findById(row.id).inspiredByIdeaId);
  }

  private String statusOfWork(WorkEntity row) {
    return QuarkusTransaction.requiringNew().call(() -> workEntityRepository.findById(row.id).status);
  }

  private WorkEntity ticket(String ideaId) {
    return work.create(
            Archetype.TICKET,
            PROJECT,
            EntityWrite.ticket("A ticket", "something occurs", null, "BUG", null)
                .withAcceptanceCriteria(CRITERIA)
                .withInspiredBy(ideaId, false),
            "t")
        .entity();
  }

  private void walk(WorkEntity row, EntityStatus to) {
    int from = WALK.indexOf(EntityStatus.valueOf(statusOfWork(row)));
    for (int step = from + 1; step <= WALK.indexOf(to); step++) {
      work.transition(row.archetype, row.id, WALK.get(step).name(), Mover.person("t"));
    }
  }

  @Test
  void theRuleItself() {
    assertEquals(IdeaStatus.PLANNED, IdeaStatusRecompute.derive(List.of()));
    assertEquals(IdeaStatus.PLANNED, IdeaStatusRecompute.derive(List.of("DROPPED", "DROPPED")));
    assertEquals(IdeaStatus.QUEUED, IdeaStatusRecompute.derive(List.of("REPORTED")));
    assertEquals(IdeaStatus.QUEUED, IdeaStatusRecompute.derive(List.of("VERIFIED")));
    assertEquals(IdeaStatus.QUEUED, IdeaStatusRecompute.derive(List.of("DONE", "VERIFIED")));
    assertEquals(IdeaStatus.DONE, IdeaStatusRecompute.derive(List.of("DONE")));
    assertEquals(IdeaStatus.DONE, IdeaStatusRecompute.derive(List.of("DONE", "DROPPED")));
  }

  @Test
  void plannedThenQueuedThenDoneAndVerifiedIsNotEnough() {
    Idea idea = ideas.create(PROJECT, "Walk it", null, null, "t");
    assertEquals(IdeaStatus.PLANNED, statusOf(idea));

    WorkEntity ticket = ticket(idea.id);
    assertEquals(IdeaStatus.QUEUED, statusOf(idea), "a create naming the idea queues it");
    IdeaChange queued = announced.changes().get(announced.changes().size() - 1);
    assertEquals(IdeaChange.Kind.UPDATED, queued.kind());
    assertEquals("PLANNED", queued.statusBefore());
    assertEquals("QUEUED", queued.status());

    walk(ticket, EntityStatus.VERIFIED);
    assertEquals(IdeaStatus.QUEUED, statusOf(idea), "VERIFIED is not DONE");

    announced.clear();
    walk(ticket, EntityStatus.DONE);
    assertEquals(IdeaStatus.DONE, statusOf(idea));
    assertEquals(1, announced.changes().size(), "the move that finished it announced it once");
    assertEquals("QUEUED", announced.changes().get(0).statusBefore());
  }

  @Test
  void droppedWorkIsNotCounted() {
    Idea idea = ideas.create(PROJECT, "Drop one", null, null, "t");
    WorkEntity done = ticket(idea.id);
    walk(done, EntityStatus.DONE);
    WorkEntity open = ticket(idea.id);
    assertEquals(IdeaStatus.QUEUED, statusOf(idea));

    work.transition(Archetype.TICKET, open.id, "DROPPED", "t", TestDrops.WONT_DO);
    assertEquals(IdeaStatus.DONE, statusOf(idea), "the dropped ticket no longer holds it open");

    Idea alone = ideas.create(PROJECT, "Only dropped", null, null, "t");
    WorkEntity dropped = ticket(alone.id);
    work.transition(Archetype.TICKET, dropped.id, "DROPPED", "t", TestDrops.WONT_DO);
    assertEquals(IdeaStatus.PLANNED, statusOf(alone), "only dropped work is no work");
  }

  @Test
  void clearingOrMovingInspiredByReDerivesBothIdeas() {
    Idea first = ideas.create(PROJECT, "First", null, null, "t");
    Idea second = ideas.create(PROJECT, "Second", null, null, "t");
    WorkEntity ticket = ticket(first.id);
    assertEquals(IdeaStatus.QUEUED, statusOf(first));

    work.update(
        Archetype.TICKET, ticket.id, EntityWrite.inspiration(second.id, false), "t");
    assertEquals(IdeaStatus.PLANNED, statusOf(first), "the idea it left");
    assertEquals(IdeaStatus.QUEUED, statusOf(second), "the idea it names now");

    work.update(
        Archetype.TICKET, ticket.id, EntityWrite.inspiration(null, true), "t");
    assertEquals(IdeaStatus.PLANNED, statusOf(second));
    assertEquals(null, inspiredByOf(ticket));
  }

  @Test
  void anInspiredByEditIsAllowedAtEveryStatus() {
    Idea idea = ideas.create(PROJECT, "Late", null, null, "t");
    WorkEntity epic = work.create(Archetype.EPIC, PROJECT, EntityWrite.epic("Epic", null).withAcceptanceCriteria(CRITERIA), "t").entity();
    walk(epic, EntityStatus.IMPLEMENTING);

    work.update(Archetype.EPIC, epic.id, EntityWrite.inspiration(idea.id, false), "t");
    assertEquals(IdeaStatus.QUEUED, statusOf(idea));
  }

  @Test
  void deletingTheWorkReDerivesTheIdea() {
    Idea idea = ideas.create(PROJECT, "Deleted work", null, null, "t");
    WorkEntity ticket = ticket(idea.id);
    assertEquals(IdeaStatus.QUEUED, statusOf(idea));

    work.delete(Archetype.TICKET, ticket.id, "t");
    assertEquals(IdeaStatus.PLANNED, statusOf(idea));
  }

  @Test
  void theWholeStateTransitionReDerivesToo() {
    Idea idea = ideas.create(PROJECT, "Reshaped", null, null, "t");
    WorkEntity epic =
        work.create(
                Archetype.EPIC,
                PROJECT,
                EntityWrite.epic("Epic", null).withInspiredBy(idea.id, false),
                "t")
            .entity();
    assertEquals(IdeaStatus.QUEUED, statusOf(idea));

    // A drop stated through the whole-state door: the idea's only work is no longer counted.
    transitions.transition(
        java.util.Map.of(
            epic.id,
            new EntityTransition(
                Archetype.EPIC,
                null,
                "Epic",
                null,
                "DROPPED",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                eu.wohlben.qits.entities.entity.WorkOutcome.WONT_DO)),
        "t");
    assertEquals(IdeaStatus.PLANNED, statusOf(idea));
    assertEquals(idea.id, inspiredByOf(epic), "the transition keeps the idea");
  }

  @Test
  void theReconcileFixesADriftedStatus() {
    Idea idea = ideas.create(PROJECT, "Drifted", null, null, "t");
    ticket(idea.id);
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                epicsEntityManager
                    .createNativeQuery("update idea set status = 'DONE' where id = ?1")
                    .setParameter(1, idea.id)
                    .executeUpdate());
    assertEquals(IdeaStatus.DONE, statusOf(idea));

    IdeaStatusRecompute.Reconciled reconciled = recompute.reconcile("boot");
    assertTrue(
        reconciled.drifts().contains(new IdeaStatusRecompute.Drift(idea.id, PROJECT, "DONE", "QUEUED")),
        reconciled.drifts().toString());
    assertEquals(IdeaStatus.QUEUED, statusOf(idea));
    assertTrue(recompute.reconcile("boot").drifts().isEmpty(), "a second pass finds nothing");
  }
}
