package eu.wohlben.qits.entities.control;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.AuditEntityType;
import eu.wohlben.qits.entities.entity.AuditOperation;
import eu.wohlben.qits.entities.entity.EntityStatus;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.entities.error.BadRequestException;
import eu.wohlben.qits.entities.error.ConflictException;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The ticket lifecycle: the five pipeline statuses walked adjacently, the off-pipeline exit that is
 * DROPPED, and what a ticket deliberately does <em>not</em> freeze. The last part is the one worth
 * testing hardest — the epic lifecycle beside it refuses almost every write once its scope is
 * committed to, and each case here is a write that a reader of that class would expect to be
 * refused and which must not be.
 *
 * <p><b>The DROPPED cases ask {@link EntityLifecycle} directly where everything else goes through
 * {@code WorkEntityService}</b>, and the split is deliberate rather than convenience. Those cases sweep
 * every pair the word takes part in — droppable from each of the four open statuses, refused from
 * DONE (which is final and refuses everything), and reopening to REPORTED and to nothing else — and asking the rule is how a sweep stays a
 * sweep: driving each pair over the service would mean walking a ticket to the starting status and
 * writing a row per pair to assert something the rule decides on its own.
 *
 * <p>What that costs is the one claim a rule-level case cannot make: that the status column accepts
 * the word the rule allows. Java's enum and the database's {@code ck_entity_status} are two places
 * the vocabulary is written down (V9's split — the constraint spells the vocabulary, the service
 * spells the rule), so a lifecycle that gains a word gains a migration too, and V14 is it. {@link
 * #aDroppedTicketIsPersistedAndRevivedThroughTheService} is the end-to-end proof that the two now
 * agree; it is one case rather than a second sweep because one persisted DROPPED row settles the
 * column, and the column has no opinion about which pair reached it.
 */
@QuarkusTest
class TicketLifecycleTest extends EntitiesTestSupport {

  /**
   * The <b>pipeline</b>, in order. Every legal move along it is a step between neighbours in this
   * list — which is not the same claim as "every legal move", because {@link EntityStatus#DROPPED}
   * is off this line entirely and is asserted on its own below.
   */
  private static final List<EntityStatus> ORDER =
      List.of(
          EntityStatus.REPORTED,
          EntityStatus.REFINED,
          EntityStatus.IMPLEMENTED,
          EntityStatus.VERIFIED,
          EntityStatus.DONE);

  @Inject WorkEntityService workEntities;

  @Inject EntityCommentService ticketComments;
  @Inject AuditService auditService;

  private WorkEntity reported() {
    return workEntities
        .create(
            Archetype.TICKET,
            "proj-1",
            EntityWrite.ticket(
                "Login button does nothing",
                "clicking the login button does nothing on the sign-in page",
                null,
                "BUG",
                null),
            "t")
        .entity();
  }

  /**
   * A ticket walked forward to {@code status}, one legal step at a time. Pipeline statuses only:
   * DROPPED is not on the walk, and a silent REPORTED would be the worst possible answer to an ask
   * for it.
   */
  private WorkEntity at(EntityStatus status) {
    assertTrue(ORDER.contains(status), status + " is not on the pipeline and cannot be walked to");
    WorkEntity ticket = reported();
    for (int step = 1; step <= ORDER.indexOf(status); step++) {
      ticket =
          workEntities
              .transition(Archetype.TICKET, ticket.id, ORDER.get(step).name(), "t")
              .entity();
    }
    return ticket;
  }

  // --- the graph -------------------------------------------------------------------------------

  @Test
  void aTicketWalksTheWholeLifecycleForward() {
    WorkEntity ticket = reported();
    assertEquals(
        EntityStatus.REPORTED.name(), ticket.status, "a filed ticket has been reported and no more");
    for (int step = 1; step < ORDER.size(); step++) {
      assertEquals(
          ORDER.get(step).name(),
          workEntities
              .transition(Archetype.TICKET, ticket.id, ORDER.get(step).name(), "t")
              .entity()
              .status);
    }
  }

  @Test
  void aTicketWalksTheWholeLifecycleBackwardFromVerified() {
    // Below DONE every status moves back one step. DONE itself is final and is asserted on its own.
    WorkEntity ticket = at(EntityStatus.VERIFIED);
    for (int step = ORDER.size() - 3; step >= 0; step--) {
      assertEquals(
          ORDER.get(step).name(),
          workEntities
              .transition(Archetype.TICKET, ticket.id, ORDER.get(step).name(), "t")
              .entity()
              .status);
    }
  }

  @Test
  void aFailedVerificationIsTheOrdinaryMoveBackToRefined() {
    // There is no reject verb: what a failed verification establishes is that the ticket needs
    // deciding again, which is the state a just-refined ticket is in.
    WorkEntity ticket = at(EntityStatus.IMPLEMENTED);
    assertEquals(
        EntityStatus.REFINED.name(),
        workEntities.transition(Archetype.TICKET, ticket.id, "REFINED", "alice").entity().status);
    // And forward again from there, as many times as the fix takes.
    assertEquals(
        EntityStatus.IMPLEMENTED.name(),
        workEntities
            .transition(Archetype.TICKET, ticket.id, "IMPLEMENTED", "alice")
            .entity()
            .status);
  }

  /**
   * The pipeline is adjacent-only in both directions, and this is the sweep that says so: every
   * pair of pipeline statuses that is not one step apart is refused, whichever end it is asked
   * from. It deliberately sweeps {@link #ORDER} and not {@code EntityStatus.values()} — DROPPED is
   * not a sixth step and is not judged by distance along this list, so folding it in here would
   * assert the very thing that is false about it.
   */
  @Test
  void everyNonAdjacentPipelinePairIsRefusedInBothDirections() {
    for (EntityStatus from : ORDER) {
      for (EntityStatus to : ORDER) {
        if (Math.abs(ORDER.indexOf(from) - ORDER.indexOf(to)) == 1
            && from != EntityStatus.DONE) {
          continue;
        }
        WorkEntity ticket = at(from);
        assertThrows(
            ConflictException.class,
            () -> workEntities.transition(Archetype.TICKET, ticket.id, to.name(), "t").entity(),
            from + " -> " + to + " is not one step and must be refused");
      }
    }
  }

  @Test
  void movingToTheStatusItAlreadyHasIsRejected() {
    // DROPPED is the same answer and is asserted with the rest of its cases, through the rule.
    // Covered by the sweep above (a distance of zero is not a distance of one), and stated on its
    // own because it is the case a caller most often expects to be a no-op.
    for (EntityStatus status : ORDER) {
      WorkEntity ticket = at(status);
      assertThrows(
          ConflictException.class,
          () -> workEntities.transition(Archetype.TICKET, ticket.id, status.name(), "t").entity());
    }
  }

  // --- DROPPED, the exit that is not a step ----------------------------------------------------

  /** A move the lifecycle allows, asked of the rule itself. See the class javadoc for why. */
  private static void legal(EntityStatus from, EntityStatus target) {
    assertDoesNotThrow(
        () -> EntityLifecycle.requireTransition(Archetype.TICKET, from, target), from + " -> " + target);
  }

  /** A move the lifecycle refuses, asked the same way. */
  private static void refused(EntityStatus from, EntityStatus target) {
    assertThrows(
        ConflictException.class,
        () -> EntityLifecycle.requireTransition(Archetype.TICKET, from, target),
        from + " -> " + target + " must be refused");
  }

  @Test
  void workIsDroppableFromEveryStatusThatIsNotAlreadyClosed() {
    // A decision not to do the work can be taken at any point while the work is still open, and
    // nothing about where the ticket got to changes that: a report nobody wants refined and a fix
    // released and then thought better of are the same decision.
    for (EntityStatus open :
        List.of(
            EntityStatus.REPORTED,
            EntityStatus.REFINED,
            EntityStatus.IMPLEMENTED,
            EntityStatus.VERIFIED)) {
      legal(open, EntityStatus.DROPPED);
    }
  }

  @Test
  void doneIsFinalAndRefusesEveryTarget() {
    // DONE has no exits: not the step back to VERIFIED, not DROPPED, not itself. A done development
    // that later turns out wrong is a new ticket, and the refusal says so.
    for (EntityStatus target : EntityStatus.values()) {
      ConflictException refusal =
          assertThrows(
              ConflictException.class,
              () -> EntityLifecycle.requireTransition(Archetype.TICKET, EntityStatus.DONE, target),
              "DONE -> " + target + " must be refused");
      assertTrue(refusal.getMessage().contains("DONE is final"), refusal.getMessage());
      assertTrue(
          refusal.getMessage().contains("a follow-up is a new ticket or epic"),
          refusal.getMessage());
    }
  }

  @Test
  void aDoneTicketRefusesEveryTargetThroughTheService() {
    WorkEntity ticket = at(EntityStatus.DONE);
    for (EntityStatus target : EntityStatus.values()) {
      assertThrows(
          ConflictException.class,
          () -> workEntities.transition(Archetype.TICKET, ticket.id, target.name(), "t"),
          "DONE -> " + target + " must be refused");
    }
    assertEquals(EntityStatus.DONE.name(), workEntities.get(Archetype.TICKET, ticket.id).status);
  }

  @Test
  void verifiedIsStillDroppable() {
    legal(EntityStatus.VERIFIED, EntityStatus.DROPPED);
    refused(EntityStatus.DONE, EntityStatus.DROPPED);
  }

  @Test
  void droppedWorkIsRevivedByAskingAgainWhatItIsFor() {
    // Reviving abandoned work is the refine phase, so REPORTED is where it lands — and resuming at
    // wherever it was abandoned is refused rather than remembered, which is what keeps this a
    // graph instead of a graph plus a column.
    legal(EntityStatus.DROPPED, EntityStatus.REPORTED);
    refused(EntityStatus.DROPPED, EntityStatus.REFINED);
    refused(EntityStatus.DROPPED, EntityStatus.IMPLEMENTED);
    refused(EntityStatus.DROPPED, EntityStatus.VERIFIED);
    refused(EntityStatus.DROPPED, EntityStatus.DONE);
    refused(EntityStatus.DROPPED, EntityStatus.DROPPED);
  }

  @Test
  void aDroppedTicketIsPersistedAndRevivedThroughTheService() {
    // The one DROPPED case that goes the whole way, because the rule allowing a move says nothing
    // about a column accepting the word: before epics V14 every line below the transition here
    // died in the database with ck_entity_status refusing DROPPED, while every rule-level case
    // above passed exactly as it does now.
    WorkEntity ticket = at(EntityStatus.REFINED);

    assertEquals(
        EntityStatus.DROPPED.name(),
        workEntities.transition(Archetype.TICKET, ticket.id, "DROPPED", "alice").entity().status);
    // Read back rather than believed from the answer: what is under test is the row, so the
    // assertion has to be one the write actually reached the database to satisfy.
    inFreshTx(
        () ->
            assertEquals(
                EntityStatus.DROPPED.name(), workEntities.get(Archetype.TICKET, ticket.id).status));

    // And out again the one way out there is. Reviving is the refine phase, so the work does not
    // resume at the REFINED it was dropped from — see the rule-level case for why that is a graph
    // decision rather than a forgotten column.
    assertEquals(
        EntityStatus.REPORTED.name(),
        workEntities.transition(Archetype.TICKET, ticket.id, "REPORTED", "alice").entity().status);
  }

  @Test
  void theRefusalNamesBothEnds() {
    WorkEntity ticket = reported();
    ConflictException refused =
        assertThrows(
            ConflictException.class,
            () -> workEntities.transition(Archetype.TICKET, ticket.id, "VERIFIED", "t").entity());
    assertTrue(refused.getMessage().contains("REPORTED"), refused.getMessage());
    assertTrue(refused.getMessage().contains("VERIFIED"), refused.getMessage());
  }

  @Test
  void transitionIsAuditedAsAnUpdate() {
    WorkEntity ticket = reported();
    workEntities.transition(Archetype.TICKET, ticket.id, "REFINED", "alice").entity();

    var history = auditService.listForEntity(AuditEntityType.TICKET, ticket.id);
    assertEquals(AuditOperation.UPDATE, history.get(0).operation);
    assertEquals("alice", history.get(0).changedBy);
    assertTrue(history.get(0).snapshot.contains("\"status\":\"REFINED\""));
  }

  // --- illegal targets -------------------------------------------------------------------------

  @Test
  void anUnknownTargetIsAConflict() {
    WorkEntity ticket = reported();
    // The caller asked for a state that does not exist, which is the same kind of answer as asking
    // for one that is not reachable — so 409, exactly as an epic answers.
    assertThrows(
        ConflictException.class,
        () -> workEntities.transition(Archetype.TICKET, ticket.id, "CLOSED", "t").entity());
    assertThrows(
        ConflictException.class,
        () -> workEntities.transition(Archetype.TICKET, ticket.id, "OPEN", "t").entity());
    assertThrows(
        ConflictException.class,
        () -> workEntities.transition(Archetype.TICKET, ticket.id, "refined", "t").entity());
  }

  @Test
  void anAbsentTargetIsABadRequest() {
    WorkEntity ticket = reported();
    // A malformed request rather than a refused move, and the split matters to the surfaces above.
    assertThrows(
        BadRequestException.class,
        () -> workEntities.transition(Archetype.TICKET, ticket.id, null, "t").entity());
    assertThrows(
        BadRequestException.class,
        () -> workEntities.transition(Archetype.TICKET, ticket.id, "  ", "t").entity());
  }

  // --- what does NOT freeze --------------------------------------------------------------------

  @Test
  void aDoneTicketIsStillEditable() {
    // The whole difference from an epic on the same lifecycle. A ticket carries one small thing rather than a scope
    // that was committed to, so closing it commits to nothing and freezes nothing.
    WorkEntity ticket = at(EntityStatus.DONE);

    WorkEntity edited =
        workEntities
            .update(
                Archetype.TICKET,
                ticket.id,
                EntityWrite.ticketEdit(
                    "Better title",
                    "the login button is still inert on the sign-in page",
                    false,
                    "more detail",
                    false,
                    "IMPROVEMENT",
                    "bob",
                    false),
                "bob")
            .entity();
    assertEquals("Better title", edited.title);
    assertEquals("the login button is still inert on the sign-in page", edited.impetus);
    assertEquals("more detail", edited.description);
    assertEquals("bob", edited.assignee);
    assertEquals(EntityStatus.DONE.name(), edited.status, "an edit does not move the status");
  }

  @Test
  void aDoneTicketStillTakesComments() {
    WorkEntity ticket = at(EntityStatus.DONE);
    assertNotNull(ticketComments.addComment(ticket.id, "it came back", "alice"));
    assertEquals(1, ticketComments.listComments(ticket.id).size());
  }

  @Test
  void aDoneTicketCanStillBeDeleted() {
    WorkEntity ticket = at(EntityStatus.DONE);
    workEntities.delete(Archetype.TICKET, ticket.id, "t");
    inFreshTx(() -> assertTrue(workEntities.listByProject(Archetype.TICKET, "proj-1").isEmpty()));
  }

  @Test
  void theUpdateEndpointCannotMoveTheStatus() {
    // transition is the ONLY writer of the column, which is why UpdateTicketRequest has no status
    // field at all — there is no argument here that could carry one.
    WorkEntity ticket = reported();
    WorkEntity edited =
        workEntities
            .update(
                Archetype.TICKET,
                ticket.id,
                EntityWrite.ticketEdit("Renamed", null, false, null, false, null, null, false),
                "t")
            .entity();
    assertEquals(EntityStatus.REPORTED.name(), edited.status);
  }
}
