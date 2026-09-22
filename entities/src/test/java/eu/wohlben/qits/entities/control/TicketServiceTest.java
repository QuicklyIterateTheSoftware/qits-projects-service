package eu.wohlben.qits.entities.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.entities.entity.AuditEntityType;
import eu.wohlben.qits.entities.entity.AuditOperation;
import eu.wohlben.qits.entities.entity.TicketComment;
import eu.wohlben.qits.entities.entity.TicketStatus;
import eu.wohlben.qits.entities.entity.TicketType;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.entities.error.BadRequestException;
import eu.wohlben.qits.entities.error.NotFoundException;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.Test;

@QuarkusTest
class TicketServiceTest extends EntitiesTestSupport {

  @Inject TicketService ticketService;
  @Inject AuditService auditService;

  private WorkEntity bug(String title) {
    return ticketService.create(
        "proj-1",
        title,
        "something occurs on the login page",
        "what went wrong",
        "BUG",
        null,
        "alice");
  }

  // --- Tickets ---------------------------------------------------------------------------------

  @Test
  void createReadUpdateDelete() {
    WorkEntity ticket = bug("Login button does nothing");
    assertNotNull(ticket.id);
    assertEquals("proj-1", ticket.projectId);
    assertEquals(TicketType.BUG, ticket.ticketType);
    assertEquals(TicketStatus.REPORTED.name(), ticket.status);
    assertNull(ticket.assignee);
    assertNotNull(ticket.createdAt);
    assertNotNull(ticket.updatedAt);

    assertEquals("Login button does nothing", ticketService.get(ticket.id).title);

    WorkEntity updated =
        ticketService.update(
            ticket.id, "Login button is inert", null, false, null, false, "IMPROVEMENT", "bob", false, "bob");
    assertEquals("Login button is inert", updated.title);
    assertEquals(TicketType.IMPROVEMENT, updated.ticketType);
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
        ticketService
            .create(
                "proj-1",
                "Unattributed",
                "something occurs on the login page",
                null,
                "BUG",
                null,
                null)
            .createdBy,
        "an unattributed caller is an ordinary caller");
  }

  @Test
  void createdByIsNotRewrittenByALaterEdit() {
    WorkEntity ticket = bug("Filed by alice");
    WorkEntity edited =
        ticketService.update(ticket.id, "Edited by bob", null, false, null, false, null, null, false, "bob");
    assertEquals("alice", edited.createdBy);
  }

  @Test
  void listByProjectScopesToTheProject() {
    ticketService.create("proj-a", "A1", "something occurs on the login page", null, "BUG", null, "t");
    ticketService.create("proj-a", "A2", "something occurs on the login page", null, "IMPROVEMENT", null, "t");
    ticketService.create("proj-b", "B1", "something occurs on the login page", null, "BUG", null, "t");

    assertEquals(2, ticketService.listByProject("proj-a").size());
    assertEquals(1, ticketService.listByProject("proj-b").size());
    assertTrue(ticketService.listByProject("proj-none").isEmpty());
  }

  @Test
  void listByProjectIsOldestFirst() {
    WorkEntity first = bug("First");
    WorkEntity second = bug("Second");
    WorkEntity third = bug("Third");
    assertEquals(
        List.of(first.id, second.id, third.id),
        ticketService.listByProject("proj-1").stream().map(t -> t.id).toList());
  }

  @Test
  void listByProjectFiltersByStatus() {
    WorkEntity reported = bug("Still broken");
    WorkEntity refined = bug("Described");
    ticketService.transition(refined.id, "REFINED", "t");

    assertEquals(2, ticketService.listByProject("proj-1").size());
    assertEquals(
        List.of(reported.id),
        ticketService.listByProject("proj-1", "REPORTED").stream().map(t -> t.id).toList());
    assertEquals(
        List.of(refined.id),
        ticketService.listByProject("proj-1", "REFINED").stream().map(t -> t.id).toList());
    // A blank filter is no filter.
    assertEquals(2, ticketService.listByProject("proj-1", "  ").size());
  }

  @Test
  void anUnknownStatusFilterIsRejected() {
    // A typo must not read as "no tickets".
    assertThrows(
        BadRequestException.class, () -> ticketService.listByProject("proj-1", "REFIND"));
    assertThrows(BadRequestException.class, () -> ticketService.listByProject("proj-1", "reported"));
    // The old vocabulary is a typo now like any other.
    assertThrows(BadRequestException.class, () -> ticketService.listByProject("proj-1", "OPEN"));
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
            .create(
                "proj-2",
                "Login button does nothing",
                "something occurs on the login page",
                null,
                "BUG",
                null,
                "t")
            .slug);
  }

  @Test
  void updateLeavesTheSlugAlone() {
    WorkEntity ticket = bug("Login button does nothing");
    WorkEntity renamed =
        ticketService.update(
            ticket.id, "Something else entirely", null, false, null, false, null, null, false, "t");
    // The slug is the row's stable address; retitling must not move it.
    assertEquals("login-button-does-nothing", renamed.slug);
  }

  @Test
  void theClearFlagsAreWhatEmptyTheNullableFields() {
    WorkEntity ticket =
        ticketService.create(
            "proj-1", "Assigned", "the list is unsorted", "a body", "BUG", "alice", "alice");

    // A title-only edit touches none of the three.
    WorkEntity retitled =
        ticketService.update(ticket.id, "Renamed", null, false, null, false, null, null, false, "t");
    assertEquals("the list is unsorted", retitled.impetus);
    assertEquals("a body", retitled.description);
    assertEquals("alice", retitled.assignee);

    WorkEntity cleared =
        ticketService.update(ticket.id, null, null, true, null, true, null, null, true, "t");
    assertNull(cleared.impetus);
    assertNull(cleared.description);
    assertNull(cleared.assignee);
  }

  @Test
  void theImpetusIsWhatAFiledTicketConsistsOf() {
    // A REPORTED ticket is an impetus and nothing else: the description is the refinement's output
    // and is ordinarily written later, by the phase this status starts.
    WorkEntity filed =
        ticketService.create(
            "proj-1",
            "Login button does nothing",
            "clicking the login button does nothing on the sign-in page",
            null,
            "BUG",
            null,
            "alice");
    assertEquals(TicketStatus.REPORTED.name(), filed.status);
    assertEquals("clicking the login button does nothing on the sign-in page", filed.impetus);
    assertNull(filed.description, "refinement has not run yet");

    // It survives the round trip, and a read of the row says what the create answered.
    assertEquals(filed.impetus, ticketService.get(filed.id).impetus);
  }

  @Test
  void anAbsentImpetusIsRejectedAtCreate() {
    // Required here rather than at the surfaces alone: a ticket with nothing said about why it
    // exists is a row nobody can refine.
    assertThrows(
        BadRequestException.class,
        () -> ticketService.create("proj-1", "T", null, "a body", "BUG", null, "t"));
    assertThrows(
        BadRequestException.class,
        () -> ticketService.create("proj-1", "T", "   ", "a body", "BUG", null, "t"));
  }

  @Test
  void theImpetusIsEditableAndTheRefinementIsWrittenBesideit() {
    WorkEntity filed =
        ticketService.create(
            "proj-1", "Inert button", "the login button does nothing", null, "BUG", null, "alice");

    // Triage corrects the words; the refinement writes its own field. Neither overwrites the other.
    WorkEntity refined =
        ticketService.update(
            filed.id,
            null,
            "the login button does nothing while a session is expired",
            false,
            "Re-issue the session before the click handler runs.",
            false,
            null,
            null,
            false,
            "bob");
    assertEquals("the login button does nothing while a session is expired", refined.impetus);
    assertEquals("Re-issue the session before the click handler runs.", refined.description);

    // And the audit entry carries the impetus like any other field.
    var history = auditService.listForEntity(AuditEntityType.TICKET, filed.id);
    assertTrue(
        history.get(0).snapshot.contains("while a session is expired"), history.get(0).snapshot);
  }

  @Test
  void aBlankAssigneeMeansNobody() {
    WorkEntity ticket =
        ticketService.create(
            "proj-1", "T", "something occurs on the login page", null, "BUG", "   ", "t");
    assertNull(ticket.assignee);
    assertNull(ticketService.update(ticket.id, null, null, false, null, false, null, "  ", false, "t").assignee);
  }

  @Test
  void blankTitleAndUnknownTypeAreRejected() {
    assertThrows(
        BadRequestException.class,
        () -> ticketService.create("proj-1", "  ", "something occurs on the login page", null, "BUG", null, "t"));
    assertThrows(
        BadRequestException.class,
        () -> ticketService.create("proj-1", "T", "something occurs on the login page", null, "  ", null, "t"));
    assertThrows(
        BadRequestException.class,
        () -> ticketService.create("proj-1", "T", "something occurs on the login page", null, "DEFECT", null, "t"));

    WorkEntity ticket = bug("Live");
    assertThrows(
        BadRequestException.class,
        () -> ticketService.update(ticket.id, "  ", null, false, null, false, null, null, false, "t"));
    assertThrows(
        BadRequestException.class,
        () -> ticketService.update(ticket.id, null, null, false, null, false, "bug", null, false, "t"));
  }

  @Test
  void getUnknownTicketThrowsNotFound() {
    assertThrows(NotFoundException.class, () -> ticketService.get("nope"));
    assertThrows(NotFoundException.class, () -> ticketService.getComment("nope"));
  }

  // --- Blocked ---------------------------------------------------------------------------------

  @Test
  void aTicketIsBlockedAndUnblockedThroughItsOwnDoor() {
    WorkEntity ticket = bug("Waiting on the vendor");
    assertFalse(ticket.blocked, "every ticket is born unblocked; the column's default says so too");

    assertTrue(ticketService.setBlocked(ticket.id, true, "alice").blocked);
    // Read back, because the flag is worth nothing unless the row holds it: a caller picking work
    // up reads the row, not the answer to somebody else's write.
    inFreshTx(() -> assertTrue(ticketService.get(ticket.id).blocked));

    assertFalse(ticketService.setBlocked(ticket.id, false, "alice").blocked);
    inFreshTx(() -> assertFalse(ticketService.get(ticket.id).blocked));
  }

  @Test
  void everyTransitionClearsTheBlockForwardAndBackward() {
    // A block is scoped to the phase it blocks. The moment the status moves that phase is over and
    // the next one has not been tried, so a flag carried across would assert a blocker nobody
    // re-checked — against work nobody has attempted yet.
    WorkEntity forward = bug("Blocked while being refined");
    ticketService.setBlocked(forward.id, true, "alice");
    assertFalse(ticketService.transition(forward.id, "REFINED", "alice").blocked);

    // The backward arm, which is the one a reader expects to preserve it: sending a ticket back
    // from IMPLEMENTED to REFINED looks like a return to where it was, block and all. It is not —
    // it is an ask to implement again, and whether THAT is blocked is a question for whoever
    // tries. There is no rule here for backward moves; there is one rule, and this pins it.
    WorkEntity backward = bug("Blocked while being implemented");
    ticketService.transition(backward.id, "REFINED", "alice");
    ticketService.transition(backward.id, "IMPLEMENTED", "alice");
    ticketService.setBlocked(backward.id, true, "alice");
    assertFalse(ticketService.transition(backward.id, "REFINED", "alice").blocked);
    inFreshTx(() -> assertFalse(ticketService.get(backward.id).blocked));
  }

  @Test
  void anEditDoesNotTouchTheBlock() {
    // The same separation the status has, for the same reason: a statement about where the work
    // stands is not the same act as editing the text describing it, so a retitle that could also
    // unblock would let somebody clear a blocker without ever saying they had.
    WorkEntity ticket = bug("Stuck and misnamed");
    ticketService.setBlocked(ticket.id, true, "alice");

    WorkEntity renamed =
        ticketService.update(
            ticket.id, "Stuck, correctly named", null, false, null, false, null, null, false, "bob");
    assertTrue(renamed.blocked);
    assertEquals(TicketStatus.REPORTED.name(), renamed.status, "nor does it move the status");
  }

  @Test
  void blockingATicketThatDoesNotExistIsNotFound() {
    // Like every other write there, and worth stating because the door is new: an id naming
    // nothing is a 404 and not a silently created row or a quiet no-op.
    assertThrows(NotFoundException.class, () -> ticketService.setBlocked("nope", true, "t"));
    assertThrows(NotFoundException.class, () -> ticketService.setBlocked("nope", false, "t"));
  }

  // --- Comments --------------------------------------------------------------------------------

  @Test
  void commentsAreReadOldestFirst() {
    WorkEntity ticket = bug("Threaded");
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
    WorkEntity one = bug("One");
    WorkEntity two = bug("Two");
    ticketService.addComment(one.id, "on one", "t");
    ticketService.addComment(two.id, "on two", "t");

    assertEquals(1, ticketService.listComments(one.id).size());
    assertEquals("on one", ticketService.listComments(one.id).get(0).body);
  }

  @Test
  void theAuthorIsStampedAndAnEditDoesNotRewriteIt() {
    WorkEntity ticket = bug("Attributed");
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
    WorkEntity ticket = bug("T");
    assertThrows(BadRequestException.class, () -> ticketService.addComment(ticket.id, "  ", "t"));
    TicketComment comment = ticketService.addComment(ticket.id, "real", "t");
    assertThrows(
        BadRequestException.class, () -> ticketService.updateComment(comment.id, "", "t"));
  }

  @Test
  void deletingACommentLeavesTheTicketAndItsSiblings() {
    WorkEntity ticket = bug("T");
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
    WorkEntity ticket = bug("Doomed");
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
    WorkEntity ticket = bug("Audited");
    ticketService.update(ticket.id, "Audited twice", null, false, null, false, null, null, false, "bob");

    var history = auditService.listForEntity(AuditEntityType.TICKET, ticket.id);
    assertEquals(2, history.size());
    // Newest first, like every other entity's history.
    assertEquals(AuditOperation.UPDATE, history.get(0).operation);
    assertEquals("bob", history.get(0).changedBy);
    assertEquals(AuditOperation.CREATE, history.get(1).operation);
    assertEquals("alice", history.get(1).changedBy);
    assertTrue(history.get(1).snapshot.contains("\"status\":\"REPORTED\""));
  }

  @Test
  void theTicketIsItsOwnSubtreeRoot() {
    // AuditEntry.epicId is the subtree key rather than a foreign key to an epic: a ticket's own
    // rows and its comments' rows carry the TICKET's id, so one indexed query answers "the whole
    // history of this thing" — and still answers after the live rows are gone.
    WorkEntity ticket = bug("Rooted");
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
    WorkEntity ticket = bug("Doomed");
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
