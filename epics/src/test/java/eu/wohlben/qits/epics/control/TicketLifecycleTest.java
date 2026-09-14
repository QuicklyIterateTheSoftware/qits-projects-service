package eu.wohlben.qits.epics.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.epics.entity.AuditEntityType;
import eu.wohlben.qits.epics.entity.AuditOperation;
import eu.wohlben.qits.epics.entity.Ticket;
import eu.wohlben.qits.epics.entity.TicketStatus;
import eu.wohlben.qits.epics.error.BadRequestException;
import eu.wohlben.qits.epics.error.ConflictException;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The ticket lifecycle: the five statuses, the adjacency rule that decides every move, and what a
 * ticket deliberately does <em>not</em> freeze. The last part is the one worth testing hardest —
 * the epic lifecycle beside it refuses almost every write once its scope is committed to, and each
 * case here is a write that a reader of that class would expect to be refused and which must not
 * be.
 */
@QuarkusTest
class TicketLifecycleTest extends EpicsTestSupport {

  /** The lifecycle in order. Every legal move is a step between neighbours in this list. */
  private static final List<TicketStatus> ORDER =
      List.of(
          TicketStatus.REPORTED,
          TicketStatus.REFINED,
          TicketStatus.IMPLEMENTED,
          TicketStatus.VERIFIED,
          TicketStatus.DONE);

  @Inject TicketService ticketService;
  @Inject AuditService auditService;

  private Ticket reported() {
    return ticketService.create(
        "proj-1",
        "Login button does nothing",
        "clicking the login button does nothing on the sign-in page",
        null,
        "BUG",
        null,
        "t");
  }

  /** A ticket walked forward to {@code status}, one legal step at a time. */
  private Ticket at(TicketStatus status) {
    Ticket ticket = reported();
    for (int step = 1; step <= ORDER.indexOf(status); step++) {
      ticket = ticketService.transition(ticket.id, ORDER.get(step).name(), "t");
    }
    return ticket;
  }

  // --- the graph -------------------------------------------------------------------------------

  @Test
  void aTicketWalksTheWholeLifecycleForward() {
    Ticket ticket = reported();
    assertEquals(TicketStatus.REPORTED, ticket.status, "a filed ticket has been reported and no more");
    for (int step = 1; step < ORDER.size(); step++) {
      assertEquals(
          ORDER.get(step), ticketService.transition(ticket.id, ORDER.get(step).name(), "t").status);
    }
  }

  @Test
  void aTicketWalksTheWholeLifecycleBackward() {
    // Nothing is terminal, DONE included: it reopens to VERIFIED like every other status moves
    // back. The alternative to a status that reopens is a second row saying the same thing.
    Ticket ticket = at(TicketStatus.DONE);
    for (int step = ORDER.size() - 2; step >= 0; step--) {
      assertEquals(
          ORDER.get(step), ticketService.transition(ticket.id, ORDER.get(step).name(), "t").status);
    }
  }

  @Test
  void aFailedVerificationIsTheOrdinaryMoveBackToRefined() {
    // There is no reject verb: what a failed verification establishes is that the ticket needs
    // deciding again, which is the state a just-refined ticket is in.
    Ticket ticket = at(TicketStatus.IMPLEMENTED);
    assertEquals(
        TicketStatus.REFINED, ticketService.transition(ticket.id, "REFINED", "alice").status);
    // And forward again from there, as many times as the fix takes.
    assertEquals(
        TicketStatus.IMPLEMENTED, ticketService.transition(ticket.id, "IMPLEMENTED", "alice").status);
  }

  @Test
  void everyNonAdjacentPairIsRefusedInBothDirections() {
    for (TicketStatus from : ORDER) {
      for (TicketStatus to : ORDER) {
        if (Math.abs(ORDER.indexOf(from) - ORDER.indexOf(to)) == 1) {
          continue;
        }
        Ticket ticket = at(from);
        assertThrows(
            ConflictException.class,
            () -> ticketService.transition(ticket.id, to.name(), "t"),
            from + " -> " + to + " is not one step and must be refused");
      }
    }
  }

  @Test
  void movingToTheStatusItAlreadyHasIsRejected() {
    // Covered by the sweep above (a distance of zero is not a distance of one), and stated on its
    // own because it is the case a caller most often expects to be a no-op.
    for (TicketStatus status : ORDER) {
      Ticket ticket = at(status);
      assertThrows(
          ConflictException.class, () -> ticketService.transition(ticket.id, status.name(), "t"));
    }
  }

  @Test
  void theRefusalNamesBothEnds() {
    Ticket ticket = reported();
    ConflictException refused =
        assertThrows(
            ConflictException.class, () -> ticketService.transition(ticket.id, "VERIFIED", "t"));
    assertTrue(refused.getMessage().contains("REPORTED"), refused.getMessage());
    assertTrue(refused.getMessage().contains("VERIFIED"), refused.getMessage());
  }

  @Test
  void transitionIsAuditedAsAnUpdate() {
    Ticket ticket = reported();
    ticketService.transition(ticket.id, "REFINED", "alice");

    var history = auditService.listForEntity(AuditEntityType.TICKET, ticket.id);
    assertEquals(AuditOperation.UPDATE, history.get(0).operation);
    assertEquals("alice", history.get(0).changedBy);
    assertTrue(history.get(0).snapshot.contains("\"status\":\"REFINED\""));
  }

  // --- illegal targets -------------------------------------------------------------------------

  @Test
  void anUnknownTargetIsAConflict() {
    Ticket ticket = reported();
    // The caller asked for a state that does not exist, which is the same kind of answer as asking
    // for one that is not reachable — so 409, exactly as an epic answers.
    assertThrows(ConflictException.class, () -> ticketService.transition(ticket.id, "CLOSED", "t"));
    assertThrows(ConflictException.class, () -> ticketService.transition(ticket.id, "OPEN", "t"));
    assertThrows(ConflictException.class, () -> ticketService.transition(ticket.id, "refined", "t"));
  }

  @Test
  void anAbsentTargetIsABadRequest() {
    Ticket ticket = reported();
    // A malformed request rather than a refused move, and the split matters to the surfaces above.
    assertThrows(BadRequestException.class, () -> ticketService.transition(ticket.id, null, "t"));
    assertThrows(BadRequestException.class, () -> ticketService.transition(ticket.id, "  ", "t"));
  }

  // --- what does NOT freeze --------------------------------------------------------------------

  @Test
  void aDoneTicketIsStillEditable() {
    // The whole difference from EpicLifecycle. A ticket carries one small thing rather than a scope
    // that was committed to, so closing it commits to nothing and freezes nothing.
    Ticket ticket = at(TicketStatus.DONE);

    Ticket edited =
        ticketService.update(
            ticket.id,
            "Better title",
            "the login button is still inert on the sign-in page",
            false,
            "more detail",
            false,
            "IMPROVEMENT",
            "bob",
            false,
            "bob");
    assertEquals("Better title", edited.title);
    assertEquals("the login button is still inert on the sign-in page", edited.impetus);
    assertEquals("more detail", edited.description);
    assertEquals("bob", edited.assignee);
    assertEquals(TicketStatus.DONE, edited.status, "an edit does not move the status");
  }

  @Test
  void aDoneTicketStillTakesComments() {
    Ticket ticket = at(TicketStatus.DONE);
    assertNotNull(ticketService.addComment(ticket.id, "it came back", "alice"));
    assertEquals(1, ticketService.listComments(ticket.id).size());
  }

  @Test
  void aDoneTicketCanStillBeDeleted() {
    Ticket ticket = at(TicketStatus.DONE);
    ticketService.delete(ticket.id, "t");
    inFreshTx(() -> assertTrue(ticketService.listByProject("proj-1").isEmpty()));
  }

  @Test
  void theUpdateEndpointCannotMoveTheStatus() {
    // transition is the ONLY writer of the column, which is why UpdateTicketRequest has no status
    // field at all — there is no argument here that could carry one.
    Ticket ticket = reported();
    Ticket edited =
        ticketService.update(
            ticket.id, "Renamed", null, false, null, false, null, null, false, "t");
    assertEquals(TicketStatus.REPORTED, edited.status);
  }
}
