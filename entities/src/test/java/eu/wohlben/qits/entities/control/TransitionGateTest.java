package eu.wohlben.qits.entities.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.error.ConflictException;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The quality-gate mechanism (qits-887), with two test gates ({@link ArmableGates}): a FORWARD or
 * SKIP move asks every gate that applies, a BACK, DROP or REOPEN asks none, every refusal comes back
 * in one 409, and {@link WorkEntityService#planTransition} refuses before anything is touched.
 */
@QuarkusTest
class TransitionGateTest extends EntitiesTestSupport {

  @Inject WorkEntityService workEntities;
  @Inject RecordingTransitionAnnouncer announcer;

  @BeforeEach
  void quiet() {
    ArmableGates.disarm();
    announcer.clear();
  }

  @AfterEach
  void disarm() {
    ArmableGates.disarm();
  }

  private String ticket() {
    return workEntities
        .create(
            Archetype.TICKET,
            "proj-1",
            EntityWrite.ticket("Gated", "it occurs", null, "BUG", null)
                .withAcceptanceCriteria(CRITERIA),
            "t")
        .entity()
        .id;
  }

  private String status(String id) {
    return QuarkusTransaction.requiringNew().call(() -> workEntities.get(Archetype.TICKET, id))
        .status;
  }

  @Test
  void aForwardMoveIsJudgedByEveryGateThatAppliesAndAllRefusalsComeBackTogether() {
    String id = ticket();
    ArmableGates.First.state.arm("the first says no");
    ArmableGates.Second.state.arm("the second says no too");

    ConflictException refusal =
        assertThrows(
            ConflictException.class,
            () -> workEntities.transition(Archetype.TICKET, id, "REFINED", "agent-7"));

    assertEquals(
        "Ticket "
            + id
            + " cannot move to REFINED: A_FIRST_TEST_GATE: the first says no;"
            + " B_SECOND_TEST_GATE: the second says no too",
        refusal.getMessage());
    assertEquals("REPORTED", status(id));
    assertEquals(List.of(), announcer.batches());
    assertEquals(List.of(id + " by agent-7"), ArmableGates.Second.state.judged());
  }

  @Test
  void aGateThatPassesLetsTheMoveThroughAndSeesTheMover() {
    String id = ticket();
    ArmableGates.First.state.arm(null);

    workEntities.transition(Archetype.TICKET, id, "REFINED", Mover.person("ada"));

    assertEquals("REFINED", status(id));
    assertEquals(List.of(id + " by ada"), ArmableGates.First.state.judged());
  }

  @Test
  void aSkipIsJudgedAndBackDropAndReopenNeverAre() {
    String id = ticket();
    workEntities.transition(Archetype.TICKET, id, "REFINED", "t");
    workEntities.transition(Archetype.TICKET, id, "READY_FOR_DEV", Mover.person("ada"));
    ArmableGates.First.state.arm("refused");

    // READY_FOR_DEV -> IMPLEMENTED is the SKIP.
    assertThrows(
        ConflictException.class,
        () -> workEntities.transition(Archetype.TICKET, id, "IMPLEMENTED", "t"));
    assertEquals(1, ArmableGates.First.state.judged().size());

    // A correction passes an armed, refusing gate without asking it.
    workEntities.transition(Archetype.TICKET, id, "REFINED", "t"); // BACK
    workEntities.transition(Archetype.TICKET, id, "DROPPED", "t"); // DROP
    workEntities.transition(Archetype.TICKET, id, "REPORTED", "t"); // REOPEN
    assertEquals("REPORTED", status(id));
    assertEquals(1, ArmableGates.First.state.judged().size());
  }

  @Test
  void planTransitionRefusesBeforeAnythingIsTouched() {
    String id = ticket();
    ArmableGates.Second.state.arm("not yet");

    ConflictException refusal =
        assertThrows(
            ConflictException.class,
            () -> workEntities.planTransition(Archetype.TICKET, id, "REFINED", Mover.machine("t")));

    assertTrue(refusal.getMessage().contains("B_SECOND_TEST_GATE: not yet"), refusal.getMessage());
    assertEquals("REPORTED", status(id));
    assertEquals(List.of(), announcer.batches());
    // A correction is planned without asking.
    workEntities.transition(Archetype.TICKET, id, "DROPPED", "t");
    assertEquals(1, ArmableGates.Second.state.judged().size());
  }
}
