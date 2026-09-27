package eu.wohlben.qits.entities.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.AuditEntityType;
import eu.wohlben.qits.entities.entity.AuditOperation;
import eu.wohlben.qits.entities.entity.TicketComment;
import eu.wohlben.qits.entities.entity.EntityStatus;
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

  @Inject WorkEntityService workEntities;

  @Inject TicketCommentService ticketComments;
  @Inject AuditService auditService;

  private WorkEntity bug(String title) {
    return workEntities
        .create(
            Archetype.TICKET,
            "proj-1",
            EntityWrite.ticket(
                title, "something occurs on the login page", "what went wrong", "BUG", null),
            "alice")
        .entity();
  }

  // --- Tickets ---------------------------------------------------------------------------------

  @Test
  void createReadUpdateDelete() {
    WorkEntity ticket = bug("Login button does nothing");
    assertNotNull(ticket.id);
    assertEquals("proj-1", ticket.projectId);
    assertEquals(TicketType.BUG, ticket.ticketType);
    assertEquals(EntityStatus.REPORTED.name(), ticket.status);
    assertNull(ticket.assignee);
    assertNotNull(ticket.createdAt);
    assertNotNull(ticket.updatedAt);

    assertEquals("Login button does nothing", workEntities.get(Archetype.TICKET, ticket.id).title);

    WorkEntity updated =
        workEntities
            .update(
                Archetype.TICKET,
                ticket.id,
                EntityWrite.ticketEdit(
                    "Login button is inert", null, false, null, false, "IMPROVEMENT", "bob", false),
                "bob")
            .entity();
    assertEquals("Login button is inert", updated.title);
    assertEquals(TicketType.IMPROVEMENT, updated.ticketType);
    assertEquals("bob", updated.assignee);
    // The body was not named in the call, so it is untouched.
    assertEquals("what went wrong", updated.description);
    assertEquals(ticket.createdAt, updated.createdAt);
    assertFalse(updated.updatedAt.isBefore(updated.createdAt));

    workEntities.delete(Archetype.TICKET, ticket.id, "bob");
    inFreshTx(
        () ->
            assertThrows(
                NotFoundException.class, () -> workEntities.get(Archetype.TICKET, ticket.id)));
  }

  @Test
  void createdByIsStampedFromTheCaller() {
    // The column exists so a list has a reporter without a join against the audit log, and it is
    // never client-supplied — the service takes it from the same value it audits with.
    assertEquals("alice", bug("Stamped").createdBy);
    assertNull(
        workEntities
            .create(
                Archetype.TICKET,
                "proj-1",
                EntityWrite.ticket(
                    "Unattributed", "something occurs on the login page", null, "BUG", null),
                null)
            .entity()
            .createdBy,
        "an unattributed caller is an ordinary caller");
  }

  @Test
  void createdByIsNotRewrittenByALaterEdit() {
    WorkEntity ticket = bug("Filed by alice");
    WorkEntity edited =
        workEntities
            .update(
                Archetype.TICKET,
                ticket.id,
                EntityWrite.ticketEdit(
                    "Edited by bob", null, false, null, false, null, null, false),
                "bob")
            .entity();
    assertEquals("alice", edited.createdBy);
  }

  @Test
  void listByProjectScopesToTheProject() {
    workEntities
        .create(
            Archetype.TICKET,
            "proj-a",
            EntityWrite.ticket("A1", "something occurs on the login page", null, "BUG", null),
            "t")
        .entity();
    workEntities
        .create(
            Archetype.TICKET,
            "proj-a",
            EntityWrite.ticket(
                "A2", "something occurs on the login page", null, "IMPROVEMENT", null),
            "t")
        .entity();
    workEntities
        .create(
            Archetype.TICKET,
            "proj-b",
            EntityWrite.ticket("B1", "something occurs on the login page", null, "BUG", null),
            "t")
        .entity();

    assertEquals(2, workEntities.listByProject(Archetype.TICKET, "proj-a").size());
    assertEquals(1, workEntities.listByProject(Archetype.TICKET, "proj-b").size());
    assertTrue(workEntities.listByProject(Archetype.TICKET, "proj-none").isEmpty());
  }

  @Test
  void listByProjectIsOldestFirst() {
    WorkEntity first = bug("First");
    WorkEntity second = bug("Second");
    WorkEntity third = bug("Third");
    assertEquals(
        List.of(first.id, second.id, third.id),
        workEntities.listByProject(Archetype.TICKET, "proj-1").stream().map(t -> t.id).toList());
  }

  @Test
  void listByProjectFiltersByStatus() {
    WorkEntity reported = bug("Still broken");
    WorkEntity refined = bug("Described");
    workEntities.transition(Archetype.TICKET, refined.id, "REFINED", "t").entity();

    assertEquals(2, workEntities.listByProject(Archetype.TICKET, "proj-1").size());
    assertEquals(
        List.of(reported.id),
        workEntities.listByProject(Archetype.TICKET, "proj-1", "REPORTED").stream()
            .map(t -> t.id)
            .toList());
    assertEquals(
        List.of(refined.id),
        workEntities.listByProject(Archetype.TICKET, "proj-1", "REFINED").stream()
            .map(t -> t.id)
            .toList());
    // A blank filter is no filter.
    assertEquals(2, workEntities.listByProject(Archetype.TICKET, "proj-1", "  ").size());
  }

  @Test
  void anUnknownStatusFilterIsRejected() {
    // A typo must not read as "no tickets".
    assertThrows(
        BadRequestException.class,
        () -> workEntities.listByProject(Archetype.TICKET, "proj-1", "REFIND"));
    assertThrows(
        BadRequestException.class,
        () -> workEntities.listByProject(Archetype.TICKET, "proj-1", "reported"));
    // The old vocabulary is a typo now like any other.
    assertThrows(
        BadRequestException.class,
        () -> workEntities.listByProject(Archetype.TICKET, "proj-1", "OPEN"));
  }

  @Test
  void slugIsDerivedFromTheTitleAndUniqueWithinTheProject() {
    assertEquals("login-button-does-nothing", bug("Login button does nothing").slug);
    // Same slug in the same project → the next free suffix; the oldest keeps the clean one.
    assertEquals("login-button-does-nothing-2", bug("Login   BUTTON does nothing!").slug);
    // Another project is another scope, so the clean slug is free again.
    assertEquals(
        "login-button-does-nothing",
        workEntities
            .create(
                Archetype.TICKET,
                "proj-2",
                EntityWrite.ticket(
                    "Login button does nothing",
                    "something occurs on the login page",
                    null,
                    "BUG",
                    null),
                "t")
            .entity()
            .slug);
  }

  @Test
  void updateLeavesTheSlugAlone() {
    WorkEntity ticket = bug("Login button does nothing");
    WorkEntity renamed =
        workEntities
            .update(
                Archetype.TICKET,
                ticket.id,
                EntityWrite.ticketEdit(
                    "Something else entirely", null, false, null, false, null, null, false),
                "t")
            .entity();
    // The slug is the row's stable address; retitling must not move it.
    assertEquals("login-button-does-nothing", renamed.slug);
  }

  @Test
  void theClearFlagsAreWhatEmptyTheNullableFields() {
    WorkEntity ticket =
        workEntities
            .create(
                Archetype.TICKET,
                "proj-1",
                EntityWrite.ticket("Assigned", "the list is unsorted", "a body", "BUG", "alice"),
                "alice")
            .entity();

    // A title-only edit touches none of the three.
    WorkEntity retitled =
        workEntities
            .update(
                Archetype.TICKET,
                ticket.id,
                EntityWrite.ticketEdit("Renamed", null, false, null, false, null, null, false),
                "t")
            .entity();
    assertEquals("the list is unsorted", retitled.impetus);
    assertEquals("a body", retitled.description);
    assertEquals("alice", retitled.assignee);

    WorkEntity cleared =
        workEntities
            .update(
                Archetype.TICKET,
                ticket.id,
                EntityWrite.ticketEdit(null, null, true, null, true, null, null, true),
                "t")
            .entity();
    assertNull(cleared.impetus);
    assertNull(cleared.description);
    assertNull(cleared.assignee);
  }

  @Test
  void theImpetusIsWhatAFiledTicketConsistsOf() {
    // A REPORTED ticket is an impetus and nothing else: the description is the refinement's output
    // and is ordinarily written later, by the phase this status starts.
    WorkEntity filed =
        workEntities
            .create(
                Archetype.TICKET,
                "proj-1",
                EntityWrite.ticket(
                    "Login button does nothing",
                    "clicking the login button does nothing on the sign-in page",
                    null,
                    "BUG",
                    null),
                "alice")
            .entity();
    assertEquals(EntityStatus.REPORTED.name(), filed.status);
    assertEquals("clicking the login button does nothing on the sign-in page", filed.impetus);
    assertNull(filed.description, "refinement has not run yet");

    // It survives the round trip, and a read of the row says what the create answered.
    assertEquals(filed.impetus, workEntities.get(Archetype.TICKET, filed.id).impetus);
  }

  @Test
  void anAbsentImpetusIsRejectedAtCreate() {
    // Required here rather than at the surfaces alone: a ticket with nothing said about why it
    // exists is a row nobody can refine.
    assertThrows(
        BadRequestException.class,
        () ->
            workEntities
                .create(
                    Archetype.TICKET,
                    "proj-1",
                    EntityWrite.ticket("T", null, "a body", "BUG", null),
                    "t")
                .entity());
    assertThrows(
        BadRequestException.class,
        () ->
            workEntities
                .create(
                    Archetype.TICKET,
                    "proj-1",
                    EntityWrite.ticket("T", "   ", "a body", "BUG", null),
                    "t")
                .entity());
  }

  @Test
  void theImpetusIsEditableAndTheRefinementIsWrittenBesideit() {
    WorkEntity filed =
        workEntities
            .create(
                Archetype.TICKET,
                "proj-1",
                EntityWrite.ticket(
                    "Inert button", "the login button does nothing", null, "BUG", null),
                "alice")
            .entity();

    // Triage corrects the words; the refinement writes its own field. Neither overwrites the other.
    WorkEntity refined =
        workEntities
            .update(
                Archetype.TICKET,
                filed.id,
                EntityWrite.ticketEdit(
                    null,
                    "the login button does nothing while a session is expired",
                    false,
                    "Re-issue the session before the click handler runs.",
                    false,
                    null,
                    null,
                    false),
                "bob")
            .entity();
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
        workEntities
            .create(
                Archetype.TICKET,
                "proj-1",
                EntityWrite.ticket("T", "something occurs on the login page", null, "BUG", "   "),
                "t")
            .entity();
    assertNull(ticket.assignee);
    assertNull(
        workEntities
            .update(
                Archetype.TICKET,
                ticket.id,
                EntityWrite.ticketEdit(null, null, false, null, false, null, "  ", false),
                "t")
            .entity()
            .assignee);
  }

  @Test
  void blankTitleAndUnknownTypeAreRejected() {
    assertThrows(
        BadRequestException.class,
        () ->
            workEntities
                .create(
                    Archetype.TICKET,
                    "proj-1",
                    EntityWrite.ticket(
                        "  ", "something occurs on the login page", null, "BUG", null),
                    "t")
                .entity());
    assertThrows(
        BadRequestException.class,
        () ->
            workEntities
                .create(
                    Archetype.TICKET,
                    "proj-1",
                    EntityWrite.ticket("T", "something occurs on the login page", null, "  ", null),
                    "t")
                .entity());
    assertThrows(
        BadRequestException.class,
        () ->
            workEntities
                .create(
                    Archetype.TICKET,
                    "proj-1",
                    EntityWrite.ticket(
                        "T", "something occurs on the login page", null, "DEFECT", null),
                    "t")
                .entity());

    WorkEntity ticket = bug("Live");
    assertThrows(
        BadRequestException.class,
        () ->
            workEntities
                .update(
                    Archetype.TICKET,
                    ticket.id,
                    EntityWrite.ticketEdit("  ", null, false, null, false, null, null, false),
                    "t")
                .entity());
    assertThrows(
        BadRequestException.class,
        () ->
            workEntities
                .update(
                    Archetype.TICKET,
                    ticket.id,
                    EntityWrite.ticketEdit(null, null, false, null, false, "bug", null, false),
                    "t")
                .entity());
  }

  @Test
  void getUnknownTicketThrowsNotFound() {
    assertThrows(NotFoundException.class, () -> workEntities.get(Archetype.TICKET, "nope"));
    assertThrows(NotFoundException.class, () -> ticketComments.getComment("nope"));
  }

  // --- Blocked ---------------------------------------------------------------------------------

  @Test
  void aTicketIsBlockedAndUnblockedThroughItsOwnDoor() {
    WorkEntity ticket = bug("Waiting on the vendor");
    assertFalse(ticket.blocked, "every ticket is born unblocked; the column's default says so too");

    assertTrue(workEntities.setBlocked(Archetype.TICKET, ticket.id, true, "alice").blocked);
    // Read back, because the flag is worth nothing unless the row holds it: a caller picking work
    // up reads the row, not the answer to somebody else's write.
    inFreshTx(() -> assertTrue(workEntities.get(Archetype.TICKET, ticket.id).blocked));

    assertFalse(workEntities.setBlocked(Archetype.TICKET, ticket.id, false, "alice").blocked);
    inFreshTx(() -> assertFalse(workEntities.get(Archetype.TICKET, ticket.id).blocked));
  }

  @Test
  void everyTransitionClearsTheBlockForwardAndBackward() {
    // A block is scoped to the phase it blocks. The moment the status moves that phase is over and
    // the next one has not been tried, so a flag carried across would assert a blocker nobody
    // re-checked — against work nobody has attempted yet.
    WorkEntity forward = bug("Blocked while being refined");
    workEntities.setBlocked(Archetype.TICKET, forward.id, true, "alice");
    assertFalse(
        workEntities.transition(Archetype.TICKET, forward.id, "REFINED", "alice").entity().blocked);

    // The backward arm, which is the one a reader expects to preserve it: sending a ticket back
    // from IMPLEMENTED to REFINED looks like a return to where it was, block and all. It is not —
    // it is an ask to implement again, and whether THAT is blocked is a question for whoever
    // tries. There is no rule here for backward moves; there is one rule, and this pins it.
    WorkEntity backward = bug("Blocked while being implemented");
    workEntities.transition(Archetype.TICKET, backward.id, "REFINED", "alice").entity();
    workEntities.transition(Archetype.TICKET, backward.id, "IMPLEMENTED", "alice").entity();
    workEntities.setBlocked(Archetype.TICKET, backward.id, true, "alice");
    assertFalse(
        workEntities
            .transition(Archetype.TICKET, backward.id, "REFINED", "alice")
            .entity()
            .blocked);
    inFreshTx(() -> assertFalse(workEntities.get(Archetype.TICKET, backward.id).blocked));
  }

  @Test
  void anEditDoesNotTouchTheBlock() {
    // The same separation the status has, for the same reason: a statement about where the work
    // stands is not the same act as editing the text describing it, so a retitle that could also
    // unblock would let somebody clear a blocker without ever saying they had.
    WorkEntity ticket = bug("Stuck and misnamed");
    workEntities.setBlocked(Archetype.TICKET, ticket.id, true, "alice");

    WorkEntity renamed =
        workEntities
            .update(
                Archetype.TICKET,
                ticket.id,
                EntityWrite.ticketEdit(
                    "Stuck, correctly named", null, false, null, false, null, null, false),
                "bob")
            .entity();
    assertTrue(renamed.blocked);
    assertEquals(EntityStatus.REPORTED.name(), renamed.status, "nor does it move the status");
  }

  @Test
  void blockingATicketThatDoesNotExistIsNotFound() {
    // Like every other write there, and worth stating because the door is new: an id naming
    // nothing is a 404 and not a silently created row or a quiet no-op.
    assertThrows(
        NotFoundException.class,
        () -> workEntities.setBlocked(Archetype.TICKET, "nope", true, "t"));
    assertThrows(
        NotFoundException.class,
        () -> workEntities.setBlocked(Archetype.TICKET, "nope", false, "t"));
  }

  // --- Comments --------------------------------------------------------------------------------

  @Test
  void commentsAreReadOldestFirst() {
    WorkEntity ticket = bug("Threaded");
    TicketComment first = ticketComments.addComment(ticket.id, "I can reproduce it", "alice");
    TicketComment second = ticketComments.addComment(ticket.id, "It is the cache", "bob");
    TicketComment third = ticketComments.addComment(ticket.id, "Fixed on main", "alice");

    // A thread is a sequence: reading it backwards is reading a different thread.
    assertEquals(
        List.of(first.id, second.id, third.id),
        ticketComments.listComments(ticket.id).stream().map(c -> c.id).toList());
  }

  @Test
  void commentsAreScopedToTheirTicket() {
    WorkEntity one = bug("One");
    WorkEntity two = bug("Two");
    ticketComments.addComment(one.id, "on one", "t");
    ticketComments.addComment(two.id, "on two", "t");

    assertEquals(1, ticketComments.listComments(one.id).size());
    assertEquals("on one", ticketComments.listComments(one.id).get(0).body);
  }

  @Test
  void theAuthorIsStampedAndAnEditDoesNotRewriteIt() {
    WorkEntity ticket = bug("Attributed");
    TicketComment comment = ticketComments.addComment(ticket.id, "mine", "alice");
    assertEquals("alice", comment.author);

    TicketComment edited = ticketComments.updateComment(comment.id, "mine, corrected", "bob");
    assertEquals("mine, corrected", edited.body);
    // Who wrote it and who last changed it are different facts; the second one is the audit log's.
    assertEquals("alice", edited.author);
  }

  @Test
  void aCommentOnAnUnknownTicketIsNotFound() {
    assertThrows(NotFoundException.class, () -> ticketComments.addComment("ghost", "hello", "t"));
  }

  @Test
  void blankCommentBodiesAreRejected() {
    WorkEntity ticket = bug("T");
    assertThrows(BadRequestException.class, () -> ticketComments.addComment(ticket.id, "  ", "t"));
    TicketComment comment = ticketComments.addComment(ticket.id, "real", "t");
    assertThrows(
        BadRequestException.class, () -> ticketComments.updateComment(comment.id, "", "t"));
  }

  @Test
  void deletingACommentLeavesTheTicketAndItsSiblings() {
    WorkEntity ticket = bug("T");
    TicketComment kept = ticketComments.addComment(ticket.id, "kept", "t");
    TicketComment gone = ticketComments.addComment(ticket.id, "gone", "t");

    ticketComments.deleteComment(gone.id, "t");

    inFreshTx(
        () -> {
          assertThrows(NotFoundException.class, () -> ticketComments.getComment(gone.id));
          assertEquals(
              List.of(kept.id),
              ticketComments.listComments(ticket.id).stream().map(c -> c.id).toList());
          assertNotNull(workEntities.get(Archetype.TICKET, ticket.id));
        });
  }

  @Test
  void deletingATicketCascadesToItsComments() {
    WorkEntity ticket = bug("Doomed");
    TicketComment comment = ticketComments.addComment(ticket.id, "still here", "t");

    workEntities.delete(Archetype.TICKET, ticket.id, "t");

    inFreshTx(
        () -> {
          assertThrows(
              NotFoundException.class, () -> workEntities.get(Archetype.TICKET, ticket.id));
          assertThrows(NotFoundException.class, () -> ticketComments.getComment(comment.id));
        });
  }

  // --- Audit -----------------------------------------------------------------------------------

  @Test
  void everyMutationIsAudited() {
    WorkEntity ticket = bug("Audited");
    workEntities
        .update(
            Archetype.TICKET,
            ticket.id,
            EntityWrite.ticketEdit("Audited twice", null, false, null, false, null, null, false),
            "bob")
        .entity();

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
    TicketComment comment = ticketComments.addComment(ticket.id, "a remark", "alice");
    ticketComments.updateComment(comment.id, "a better remark", "alice");

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
    TicketComment comment = ticketComments.addComment(ticket.id, "goes with it", "carol");

    workEntities.delete(Archetype.TICKET, ticket.id, "carol");

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
