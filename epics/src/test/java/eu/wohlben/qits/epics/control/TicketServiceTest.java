package eu.wohlben.qits.epics.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.epics.entity.AuditEntityType;
import eu.wohlben.qits.epics.entity.AuditOperation;
import eu.wohlben.qits.epics.entity.Ticket;
import eu.wohlben.qits.epics.entity.TicketComment;
import eu.wohlben.qits.epics.entity.TicketStatus;
import eu.wohlben.qits.epics.entity.TicketType;
import eu.wohlben.qits.epics.error.BadRequestException;
import eu.wohlben.qits.epics.error.NotFoundException;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.Test;

@QuarkusTest
class TicketServiceTest extends EpicsTestSupport {

  @Inject TicketService ticketService;
  @Inject AuditService auditService;

  private Ticket bug(String title) {
    return ticketService.create("proj-1", title, "what went wrong", "BUG", null, "alice");
  }

  // --- Tickets ---------------------------------------------------------------------------------

  @Test
  void createReadUpdateDelete() {
    Ticket ticket = bug("Login button does nothing");
    assertNotNull(ticket.id);
    assertEquals("proj-1", ticket.projectId);
    assertEquals(TicketType.BUG, ticket.type);
    assertEquals(TicketStatus.OPEN, ticket.status);
    assertNull(ticket.assignee);
    assertNotNull(ticket.createdAt);
    assertNotNull(ticket.updatedAt);

    assertEquals("Login button does nothing", ticketService.get(ticket.id).title);

    Ticket updated =
        ticketService.update(
            ticket.id, "Login button is inert", null, false, "IMPROVEMENT", "bob", false, "bob");
    assertEquals("Login button is inert", updated.title);
    assertEquals(TicketType.IMPROVEMENT, updated.type);
    assertEquals("bob", updated.assignee);
    // The body was not named in the call, so it is untouched.
    assertEquals("what went wrong", updated.description);
    assertEquals(ticket.createdAt, updated.createdAt);
    assertFalse(updated.updatedAt.isBefore(updated.createdAt));

    ticketService.delete(ticket.id, "bob");
    inFreshTx(() -> assertThrows(NotFoundException.class, () -> ticketService.get(ticket.id)));
  }

  @Test
  void createdByIsStampedFromTheCaller() {
    // The column exists so a list has a reporter without a join against the audit log, and it is
    // never client-supplied — the service takes it from the same value it audits with.
    assertEquals("alice", bug("Stamped").createdBy);
    assertNull(
        ticketService.create("proj-1", "Unattributed", null, "BUG", null, null).createdBy,
        "an unattributed caller is an ordinary caller");
  }

  @Test
  void createdByIsNotRewrittenByALaterEdit() {
    Ticket ticket = bug("Filed by alice");
    Ticket edited =
        ticketService.update(ticket.id, "Edited by bob", null, false, null, null, false, "bob");
    assertEquals("alice", edited.createdBy);
  }

  @Test
  void listByProjectScopesToTheProject() {
    ticketService.create("proj-a", "A1", null, "BUG", null, "t");
    ticketService.create("proj-a", "A2", null, "IMPROVEMENT", null, "t");
    ticketService.create("proj-b", "B1", null, "BUG", null, "t");

    assertEquals(2, ticketService.listByProject("proj-a").size());
    assertEquals(1, ticketService.listByProject("proj-b").size());
    assertTrue(ticketService.listByProject("proj-none").isEmpty());
  }

  @Test
  void listByProjectIsOldestFirst() {
    Ticket first = bug("First");
    Ticket second = bug("Second");
    Ticket third = bug("Third");
    assertEquals(
        List.of(first.id, second.id, third.id),
        ticketService.listByProject("proj-1").stream().map(t -> t.id).toList());
  }

  @Test
  void listByProjectFiltersByStatus() {
    Ticket open = bug("Still broken");
    Ticket resolved = bug("Fixed");
    ticketService.transition(resolved.id, "RESOLVED", "t");

    assertEquals(2, ticketService.listByProject("proj-1").size());
    assertEquals(
        List.of(open.id),
        ticketService.listByProject("proj-1", "OPEN").stream().map(t -> t.id).toList());
    assertEquals(
        List.of(resolved.id),
        ticketService.listByProject("proj-1", "RESOLVED").stream().map(t -> t.id).toList());
    // A blank filter is no filter.
    assertEquals(2, ticketService.listByProject("proj-1", "  ").size());
  }

  @Test
  void anUnknownStatusFilterIsRejected() {
    // A typo must not read as "no tickets".
    assertThrows(
        BadRequestException.class, () -> ticketService.listByProject("proj-1", "RESOLVD"));
    assertThrows(BadRequestException.class, () -> ticketService.listByProject("proj-1", "open"));
  }

  @Test
  void slugIsDerivedFromTheTitleAndUniqueWithinTheProject() {
    assertEquals("login-button-does-nothing", bug("Login button does nothing").slug);
    // Same slug in the same project → the next free suffix; the oldest keeps the clean one.
    assertEquals("login-button-does-nothing-2", bug("Login   BUTTON does nothing!").slug);
    // Another project is another scope, so the clean slug is free again.
    assertEquals(
        "login-button-does-nothing",
        ticketService
            .create("proj-2", "Login button does nothing", null, "BUG", null, "t")
            .slug);
  }

  @Test
  void updateLeavesTheSlugAlone() {
    Ticket ticket = bug("Login button does nothing");
    Ticket renamed =
        ticketService.update(
            ticket.id, "Something else entirely", null, false, null, null, false, "t");
    // The slug is the row's stable address; retitling must not move it.
    assertEquals("login-button-does-nothing", renamed.slug);
  }

  @Test
  void theClearFlagsAreWhatEmptyTheNullableFields() {
    Ticket ticket =
        ticketService.create("proj-1", "Assigned", "a body", "BUG", "alice", "alice");

    // A title-only edit touches neither.
    Ticket retitled =
        ticketService.update(ticket.id, "Renamed", null, false, null, null, false, "t");
    assertEquals("a body", retitled.description);
    assertEquals("alice", retitled.assignee);

    Ticket cleared =
        ticketService.update(ticket.id, null, null, true, null, null, true, "t");
    assertNull(cleared.description);
    assertNull(cleared.assignee);
  }

  @Test
  void aBlankAssigneeMeansNobody() {
    Ticket ticket = ticketService.create("proj-1", "T", null, "BUG", "   ", "t");
    assertNull(ticket.assignee);
    assertNull(ticketService.update(ticket.id, null, null, false, null, "  ", false, "t").assignee);
  }

  @Test
  void blankTitleAndUnknownTypeAreRejected() {
    assertThrows(
        BadRequestException.class,
        () -> ticketService.create("proj-1", "  ", null, "BUG", null, "t"));
    assertThrows(
        BadRequestException.class,
        () -> ticketService.create("proj-1", "T", null, "  ", null, "t"));
    assertThrows(
        BadRequestException.class,
        () -> ticketService.create("proj-1", "T", null, "DEFECT", null, "t"));

    Ticket ticket = bug("Live");
    assertThrows(
        BadRequestException.class,
        () -> ticketService.update(ticket.id, "  ", null, false, null, null, false, "t"));
    assertThrows(
        BadRequestException.class,
        () -> ticketService.update(ticket.id, null, null, false, "bug", null, false, "t"));
  }

  @Test
  void getUnknownTicketThrowsNotFound() {
    assertThrows(NotFoundException.class, () -> ticketService.get("nope"));
    assertThrows(NotFoundException.class, () -> ticketService.getComment("nope"));
  }

  // --- Comments --------------------------------------------------------------------------------

  @Test
  void commentsAreReadOldestFirst() {
    Ticket ticket = bug("Threaded");
    TicketComment first = ticketService.addComment(ticket.id, "I can reproduce it", "alice");
    TicketComment second = ticketService.addComment(ticket.id, "It is the cache", "bob");
    TicketComment third = ticketService.addComment(ticket.id, "Fixed on main", "alice");

    // A thread is a sequence: reading it backwards is reading a different thread.
    assertEquals(
        List.of(first.id, second.id, third.id),
        ticketService.listComments(ticket.id).stream().map(c -> c.id).toList());
  }

  @Test
  void commentsAreScopedToTheirTicket() {
    Ticket one = bug("One");
    Ticket two = bug("Two");
    ticketService.addComment(one.id, "on one", "t");
    ticketService.addComment(two.id, "on two", "t");

    assertEquals(1, ticketService.listComments(one.id).size());
    assertEquals("on one", ticketService.listComments(one.id).get(0).body);
  }

  @Test
  void theAuthorIsStampedAndAnEditDoesNotRewriteIt() {
    Ticket ticket = bug("Attributed");
    TicketComment comment = ticketService.addComment(ticket.id, "mine", "alice");
    assertEquals("alice", comment.author);

    TicketComment edited = ticketService.updateComment(comment.id, "mine, corrected", "bob");
    assertEquals("mine, corrected", edited.body);
    // Who wrote it and who last changed it are different facts; the second one is the audit log's.
    assertEquals("alice", edited.author);
  }

  @Test
  void aCommentOnAnUnknownTicketIsNotFound() {
    assertThrows(NotFoundException.class, () -> ticketService.addComment("ghost", "hello", "t"));
  }

  @Test
  void blankCommentBodiesAreRejected() {
    Ticket ticket = bug("T");
    assertThrows(BadRequestException.class, () -> ticketService.addComment(ticket.id, "  ", "t"));
    TicketComment comment = ticketService.addComment(ticket.id, "real", "t");
    assertThrows(
        BadRequestException.class, () -> ticketService.updateComment(comment.id, "", "t"));
  }

  @Test
  void deletingACommentLeavesTheTicketAndItsSiblings() {
    Ticket ticket = bug("T");
    TicketComment kept = ticketService.addComment(ticket.id, "kept", "t");
    TicketComment gone = ticketService.addComment(ticket.id, "gone", "t");

    ticketService.deleteComment(gone.id, "t");

    inFreshTx(
        () -> {
          assertThrows(NotFoundException.class, () -> ticketService.getComment(gone.id));
          assertEquals(
              List.of(kept.id),
              ticketService.listComments(ticket.id).stream().map(c -> c.id).toList());
          assertNotNull(ticketService.get(ticket.id));
        });
  }

  @Test
  void deletingATicketCascadesToItsComments() {
    Ticket ticket = bug("Doomed");
    TicketComment comment = ticketService.addComment(ticket.id, "still here", "t");

    ticketService.delete(ticket.id, "t");

    inFreshTx(
        () -> {
          assertThrows(NotFoundException.class, () -> ticketService.get(ticket.id));
          assertThrows(NotFoundException.class, () -> ticketService.getComment(comment.id));
        });
  }

  // --- Audit -----------------------------------------------------------------------------------

  @Test
  void everyMutationIsAudited() {
    Ticket ticket = bug("Audited");
    ticketService.update(ticket.id, "Audited twice", null, false, null, null, false, "bob");

    var history = auditService.listForEntity(AuditEntityType.TICKET, ticket.id);
    assertEquals(2, history.size());
    // Newest first, like every other entity's history.
    assertEquals(AuditOperation.UPDATE, history.get(0).operation);
    assertEquals("bob", history.get(0).changedBy);
    assertEquals(AuditOperation.CREATE, history.get(1).operation);
    assertEquals("alice", history.get(1).changedBy);
    assertTrue(history.get(1).snapshot.contains("\"status\":\"OPEN\""));
  }

  @Test
  void theTicketIsItsOwnSubtreeRoot() {
    // AuditEntry.epicId is the subtree key rather than a foreign key to an epic: a ticket's own
    // rows and its comments' rows carry the TICKET's id, so one indexed query answers "the whole
    // history of this thing" — and still answers after the live rows are gone.
    Ticket ticket = bug("Rooted");
    TicketComment comment = ticketService.addComment(ticket.id, "a remark", "alice");
    ticketService.updateComment(comment.id, "a better remark", "alice");

    var history = auditService.listForEpic(ticket.id);
    assertEquals(3, history.size());
    assertTrue(history.stream().allMatch(e -> ticket.id.equals(e.epicId)));
    assertEquals(
        2,
        history.stream().filter(e -> e.entityType == AuditEntityType.TICKET_COMMENT).count());
  }

  @Test
  void deleteAuditsEveryRemovedRowAndSurvivesTheDeletion() {
    Ticket ticket = bug("Doomed");
    TicketComment comment = ticketService.addComment(ticket.id, "goes with it", "carol");

    ticketService.delete(ticket.id, "carol");

    // The comments are deleted IN-SERVICE rather than by the DB cascade, precisely so each removal
    // leaves a row here. The log is the git replacement and outlives what it describes.
    var history = auditService.listForEpic(ticket.id);
    assertTrue(
        history.stream()
            .anyMatch(
                e ->
                    e.operation == AuditOperation.DELETE
                        && e.entityType == AuditEntityType.TICKET_COMMENT
                        && comment.id.equals(e.entityId)));
    assertTrue(
        history.stream()
            .anyMatch(
                e ->
                    e.operation == AuditOperation.DELETE
                        && e.entityType == AuditEntityType.TICKET
                        && ticket.id.equals(e.entityId)));
  }
}
