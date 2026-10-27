package eu.wohlben.qits.entities.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.entities.entity.Archetype;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>Accepting an epic closes its plan in the same step</b> (qits-1685). The move to DONE moves
 * every open feature and task below it to DONE in the epic's transaction, and one announcement
 * carries them all. A failure part way through rolls back the epic and every piece.
 */
@QuarkusTest
@TestProfile(AcceptCascadeTest.WithFailingAudit.class)
class AcceptCascadeTest extends EntitiesTestSupport {

  /** Enables {@link FailingAudit} for this class alone. */
  public static class WithFailingAudit implements QuarkusTestProfile {
    @Override
    public Set<Class<?>> getEnabledAlternatives() {
      return Set.of(FailingAudit.class);
    }
  }

  @Inject WorkEntityService workEntities;
  @Inject FailingAudit audit;
  @Inject RecordingTransitionAnnouncer announcer;

  @BeforeEach
  void quiet() {
    audit.healthy();
    announcer.clear();
  }

  /** A VERIFIED epic: one feature VERIFIED, below it one task IMPLEMENTED and one DROPPED. */
  private record Plan(String epic, String feature, String task, String dropped) {}

  private Plan verifiedPlan() {
    String epic =
        workEntities
            .create(
                Archetype.EPIC,
                "proj-1",
                EntityWrite.epic("Plan", "The spine")
                    .withAcceptanceCriteria(EntitiesTestSupport.CRITERIA),
                "t")
            .entity()
            .id;
    String feature =
        workEntities
            .create(Archetype.FEATURE, epic, EntityWrite.feature("Feature", null, null), "t")
            .entity()
            .id;
    String task =
        workEntities
            .create(Archetype.TASK, feature, EntityWrite.task("repo-1", "Task", null, null), "t")
            .entity()
            .id;
    String dropped =
        workEntities
            .create(Archetype.TASK, feature, EntityWrite.task("repo-1", "Dropped", null, null), "t")
            .entity()
            .id;
    for (String target : List.of("REFINED", "READY_FOR_DEV")) {
      workEntities.transition(Archetype.EPIC, epic, target, Mover.person("t"));
    }
    workEntities.transition(Archetype.TASK, dropped, "DROPPED", "t", TestDrops.WONT_DO);
    for (String target : List.of("IMPLEMENTED", "VERIFYING", "VERIFIED")) {
      workEntities.transition(Archetype.EPIC, epic, target, Mover.person("t"));
    }
    workEntities.transition(Archetype.FEATURE, feature, "VERIFIED", "agent");
    announcer.clear();
    return new Plan(epic, feature, task, dropped);
  }

  private String status(Archetype archetype, String id) {
    return QuarkusTransaction.requiringNew().call(() -> workEntities.get(archetype, id)).status;
  }

  @Test
  void acceptingTheEpicClosesEveryOpenPieceInOneAnnouncement() {
    Plan plan = verifiedPlan();

    workEntities.transition(Archetype.EPIC, plan.epic(), "DONE", Mover.person("dana"));

    assertEquals("DONE", status(Archetype.EPIC, plan.epic()));
    assertEquals("DONE", status(Archetype.FEATURE, plan.feature()));
    assertEquals("DONE", status(Archetype.TASK, plan.task()));
    assertEquals("DROPPED", status(Archetype.TASK, plan.dropped()), "decided against, so left");

    assertEquals(1, announcer.batches().size(), "one move, one announcement");
    Map<String, String> before =
        announcer.batches().get(0).entities().stream()
            .collect(Collectors.toMap(TransitionedEntity::id, TransitionedEntity::statusBefore));
    assertEquals(
        Map.of(plan.epic(), "VERIFIED", plan.feature(), "VERIFIED", plan.task(), "IMPLEMENTED"),
        before);
    assertTrue(
        announcer.batches().get(0).entities().stream()
            .allMatch(m -> "DONE".equals(m.status()) && "dana".equals(m.changedBy())));
  }

  @Test
  void aFailureOnTheLastPieceRollsBackTheEpicAndEveryPiece() {
    Plan plan = verifiedPlan();
    // The task is the last piece the move closes, after the feature already changed.
    audit.failFor(plan.task());

    RuntimeException failure =
        assertThrows(
            RuntimeException.class,
            () ->
                workEntities.transition(
                    Archetype.EPIC, plan.epic(), "DONE", Mover.person("dana")));

    assertTrue(failure.toString().contains(FailingAudit.FAILED), failure.toString());
    assertEquals("VERIFIED", status(Archetype.EPIC, plan.epic()));
    assertEquals("VERIFIED", status(Archetype.FEATURE, plan.feature()));
    assertEquals("IMPLEMENTED", status(Archetype.TASK, plan.task()));
    assertEquals(List.of(), announcer.batches(), "nothing committed, nothing announced");
  }
}
