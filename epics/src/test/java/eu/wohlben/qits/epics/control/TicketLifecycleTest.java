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
import org.junit.jupiter.api.Test;

/**
 * The ticket lifecycle: which moves are legal, and what a ticket deliberately does <em>not</em>
 * freeze. The second half is the one worth testing hardest — the epic lifecycle beside it refuses
 * almost every write once its scope is committed to, and every case here is a write that a reader
 * of that class would expect to be refused and which must not be.
 */
@QuarkusTest
class TicketLifecycleTest extends EpicsTestSupport {

  @Inject TicketService ticketService;
  @Inject AuditService auditService;

  private Ticket open() {
    return ticketService.create("proj-1", "Login button does nothing", "body", "BUG", null, "t");
  }

  private Ticket resolved() {
    Ticket ticket = open();
    return ticketService.transition(ticket.id, "RESOLVED", "t");
  }

  // --- legal moves ---------------------------------------------------------------------------

  @Test
  void anOpenTicketResolves() {
    Ticket ticket = open();
    assertEquals(TicketStatus.OPEN, ticket.status);
    assertEquals(
        TicketStatus.RESOLVED, ticketService.transition(ticket.id, "RESOLVED", "alice").status);
  }

  @Test
  void aResolvedTicketReopens() {
    // The move back is what makes RESOLVED a status rather than a grave. A ticket closed by mistake
    // reopens; the alternative is a second row saying the same thing.
    Ticket ticket = resolved();
    assertEquals(TicketStatus.OPEN, ticketService.transition(ticket.id, "OPEN", "alice").status);
  }

  @Test
  void aTicketCanBeResolvedAndReopenedRepeatedly() {
    Ticket ticket = open();
    for (int round = 0; round < 3; round++) {
      assertEquals(
          TicketStatus.RESOLVED, ticketService.transition(ticket.id, "RESOLVED", "t").status);
      assertEquals(TicketStatus.OPEN, ticketService.transition(ticket.id, "OPEN", "t").status);
    }
  }

  @Test
  void transitionIsAuditedAsAnUpdate() {
    Ticket ticket = open();
    ticketService.transition(ticket.id, "RESOLVED", "alice");

    var history = auditService.listForEntity(AuditEntityType.TICKET, ticket.id);
    assertEquals(AuditOperation.UPDATE, history.get(0).operation);
    assertEquals("alice", history.get(0).changedBy);
    assertTrue(history.get(0).snapshot.contains("\"status\":\"RESOLVED\""));
  }

  // --- illegal moves -------------------------------------------------------------------------

  @Test
  void movingToTheStatusItAlreadyHasIsRejected() {
    Ticket ticket = open();
    assertThrows(ConflictException.class, () -> ticketService.transition(ticket.id, "OPEN", "t"));

    Ticket done = resolved();
    assertThrows(
        ConflictException.class, () -> ticketService.transition(done.id, "RESOLVED", "t"));
  }

  @Test
  void anUnknownTargetIsAConflict() {
    Ticket ticket = open();
    // The caller asked for a state that does not exist, which is the same kind of answer as asking
    // for one that is not reachable — so 409, exactly as an epic answers.
    assertThrows(ConflictException.class, () -> ticketService.transition(ticket.id, "CLOSED", "t"));
    assertThrows(
        ConflictException.class, () -> ticketService.transition(ticket.id, "resolved", "t"));
  }

  @Test
  void anAbsentTargetIsABadRequest() {
    Ticket ticket = open();
    // A malformed request rather than a refused move, and the split matters to the surfaces above.
    assertThrows(BadRequestException.class, () -> ticketService.transition(ticket.id, null, "t"));
    assertThrows(BadRequestException.class, () -> ticketService.transition(ticket.id, "  ", "t"));
  }

  // --- what does NOT freeze ------------------------------------------------------------------

  @Test
  void aResolvedTicketIsStillEditable() {
    // The whole difference from EpicLifecycle. A ticket carries one small thing rather than a scope
    // that was committed to, so resolving it commits to nothing and freezes nothing.
    Ticket ticket = resolved();

    Ticket edited =
        ticketService.update(
            ticket.id, "Better title", "more detail", false, "IMPROVEMENT", "bob", false, "bob");
    assertEquals("Better title", edited.title);
    assertEquals("more detail", edited.description);
    assertEquals("bob", edited.assignee);
    assertEquals(TicketStatus.RESOLVED, edited.status, "an edit does not move the status");
  }

  @Test
  void aResolvedTicketStillTakesComments() {
    Ticket ticket = resolved();
    assertNotNull(ticketService.addComment(ticket.id, "it came back", "alice"));
    assertEquals(1, ticketService.listComments(ticket.id).size());
  }

  @Test
  void aResolvedTicketCanStillBeDeleted() {
    Ticket ticket = resolved();
    ticketService.delete(ticket.id, "t");
    inFreshTx(() -> assertTrue(ticketService.listByProject("proj-1").isEmpty()));
  }

  @Test
  void theUpdateEndpointCannotMoveTheStatus() {
    // transition is the ONLY writer of the column, which is why UpdateTicketRequest has no status
    // field at all — there is no argument here that could carry one.
    Ticket ticket = open();
    Ticket edited =
        ticketService.update(ticket.id, "Renamed", null, false, null, null, false, "t");
    assertEquals(TicketStatus.OPEN, edited.status);
  }
}
