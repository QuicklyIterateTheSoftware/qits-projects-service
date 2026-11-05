package eu.wohlben.qits.entities.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.EntityStatus;
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
    ArmableQualityCheck.reset();
    announcer.clear();
  }

  @AfterEach
  void disarm() {
    ArmableGates.disarm();
    ArmableQualityCheck.reset();
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
    workEntities.transition(Archetype.TICKET, id, "DROPPED", "t", TestDrops.WONT_DO); // DROP
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
    workEntities.transition(Archetype.TICKET, id, "DROPPED", "t", TestDrops.WONT_DO);
    assertEquals(1, ArmableGates.Second.state.judged().size());
  }

  // --- the quality-check step between IMPLEMENTED and verification (qits-150) -------------------

  @Inject QualityChecksGate qualityChecks;

  /** A ticket walked to IMPLEMENTED by the walk's own moves. */
  private String implementedTicket() {
    String id = ticket();
    workEntities.transition(Archetype.TICKET, id, "REFINED", "t");
    workEntities.transition(Archetype.TICKET, id, "READY_FOR_DEV", Mover.person("ada"));
    workEntities.transition(Archetype.TICKET, id, "IMPLEMENTED", "t");
    return id;
  }

  /**
   * The step judges every ticket move out of IMPLEMENTED into verification — the agent's FORWARD to
   * VERIFYING, the platform's automatic started move (transitionFrom, as the verify press makes it)
   * and the SKIP straight to VERIFIED — and a failing check refuses each with the gate's 409.
   */
  @Test
  void theQualityStepJudgesEveryMoveOutOfImplementedIntoVerification() {
    String id = implementedTicket();
    ArmableQualityCheck.arm("the claim does not hold");

    for (String target : List.of("VERIFYING", "VERIFIED")) {
      ConflictException refusal =
          assertThrows(
              ConflictException.class,
              () -> workEntities.transition(Archetype.TICKET, id, target, "agent-7"),
              "IMPLEMENTED -> " + target);
      assertEquals(
          "Ticket "
              + id
              + " cannot move to "
              + target
              + ": QUALITY_CHECKS: its IMPLEMENTED claim fails a quality check — ARMED_TEST_CHECK:"
              + " the claim does not hold",
          refusal.getMessage());
    }
    assertThrows(
        ConflictException.class,
        () ->
            workEntities.transitionFrom(
                Archetype.TICKET,
                id,
                EntityStatus.IMPLEMENTED,
                EntityStatus.VERIFYING,
                "verify-press"),
        "the platform's started move goes through the step too");
    assertEquals("IMPLEMENTED", status(id));
    assertEquals(3, ArmableQualityCheck.judged().size(), ArmableQualityCheck.judged().toString());
  }

  /**
   * The shipped step is empty: with no check refusing, every ticket passes it on both moves, and
   * the check saw the ticket as it stood — still IMPLEMENTED.
   */
  @Test
  void everyTicketPassesTheEmptyStep() {
    String verifying = implementedTicket();
    workEntities.transition(Archetype.TICKET, verifying, "VERIFYING", "t");
    assertEquals("VERIFYING", status(verifying));

    String skipped = implementedTicket();
    workEntities.transition(Archetype.TICKET, skipped, "VERIFIED", "t");
    assertEquals("VERIFIED", status(skipped));

    assertEquals(
        List.of(verifying + " at IMPLEMENTED", skipped + " at IMPLEMENTED"),
        ArmableQualityCheck.judged());
  }

  /**
   * Where the step stands: a ticket's moves into VERIFYING or VERIFIED from before them — the two
   * real moves out of IMPLEMENTED, and the jumps the PUT-shaped door could state — and nothing else:
   * not an epic, not the move back from VERIFIED, not a move off the walk.
   */
  @Test
  void theQualityStepStandsOnlyBetweenATicketsImplementedAndItsVerification() {
    assertTrue(
        qualityChecks.appliesTo(Archetype.TICKET, EntityStatus.IMPLEMENTED, EntityStatus.VERIFYING));
    assertTrue(
        qualityChecks.appliesTo(Archetype.TICKET, EntityStatus.IMPLEMENTED, EntityStatus.VERIFIED));
    assertTrue(
        qualityChecks.appliesTo(Archetype.TICKET, EntityStatus.IMPLEMENTING, EntityStatus.VERIFIED),
        "the PUT-shaped door has no adjacency rule, so its jump is judged too");
    assertFalse(
        qualityChecks.appliesTo(Archetype.TICKET, EntityStatus.VERIFIED, EntityStatus.VERIFYING));
    assertFalse(
        qualityChecks.appliesTo(Archetype.TICKET, EntityStatus.VERIFYING, EntityStatus.VERIFIED));
    assertFalse(
        qualityChecks.appliesTo(Archetype.TICKET, EntityStatus.DROPPED, EntityStatus.VERIFIED));
    assertFalse(
        qualityChecks.appliesTo(Archetype.EPIC, EntityStatus.IMPLEMENTED, EntityStatus.VERIFYING));
    assertEquals(
        List.of(QualityChecksGate.NAME),
        TransitionGates.namesOf(
            List.of(qualityChecks),
            Archetype.TICKET,
            EntityStateMachine.transition(EntityStatus.IMPLEMENTED, EntityStatus.VERIFIED)
                .orElseThrow()));
  }
}
