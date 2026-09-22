package eu.wohlben.qits.entities.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The two withers, and the one failure they are shaped to have.
 *
 * <p>Each of them re-lists <em>every</em> component positionally to change one — which means the
 * argument list is a hand-written copy of the record's component order, and a component added to
 * the record but forgotten in a wither does not fail to compile. It falls off the end of nothing:
 * the arity check passes because the new component took a slot the old list already filled with
 * the value next to it, or, for a {@code boolean} sitting beside other booleans and strings, the
 * list still type-checks and the answer is quietly {@code false}. That is the whole defect this
 * file exists for — a ticket somebody blocked reading back unblocked, through a read path that
 * merely told it which workspaces are on it, with nothing anywhere saying a value was dropped.
 *
 * <p>So each case asserts both halves: the component the wither is <b>named for</b> changed, and
 * nothing else did. Plain JUnit, because a record has no container in it.
 */
class TicketDtoTest {

  private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00Z");

  private static final Instant UPDATED = Instant.parse("2026-01-02T00:00:00Z");

  private static final List<WorkspaceReferenceDto> CUT =
      List.of(new WorkspaceReferenceDto(7L, "repo-1", "ws-1", "ticket/inert-button", "ACTIVE"));

  private static TicketDto ticket(boolean blocked) {
    return new TicketDto(
        "t-1",
        "proj-1",
        1337L,
        null,
        "Login button does nothing",
        "login-button-does-nothing",
        "BUG",
        "REFINED",
        blocked,
        "alice",
        "bob",
        "clicking the login button does nothing",
        "re-issue the session first",
        CREATED,
        UPDATED,
        List.of());
  }

  @Test
  void tellingATicketWhichWorkspacesAreOnItDoesNotUnblockIt() {
    assertTrue(ticket(true).withWorkspaces(CUT).blocked());
    // And the false case with it: a wither that dropped the component would pass the assertion
    // above only if it happened to hard-code true, which nothing does — it is the pair that says
    // the value travelled rather than a constant did.
    assertFalse(ticket(false).withWorkspaces(CUT).blocked());
  }

  @Test
  void tellingATicketWhatItIsCalledInACommitSubjectDoesNotUnblockIt() {
    assertTrue(ticket(true).withQualifiedId("qits-1337").blocked());
    assertFalse(ticket(false).withQualifiedId("qits-1337").blocked());
  }

  @Test
  void eachWitherChangesOnlyTheComponentItIsNamedFor() {
    TicketDto blocked = ticket(true);

    // The record's own equality is the assertion: two DTOs are equal exactly when all sixteen
    // components are, so restating the original with the one component swapped catches a wither
    // that shifted any of the other fifteen along by a slot — the failure mode a per-field
    // assertion list would itself have to be kept in step with.
    assertEquals(
        new TicketDto(
            "t-1",
            "proj-1",
            1337L,
            null,
            "Login button does nothing",
            "login-button-does-nothing",
            "BUG",
            "REFINED",
            true,
            "alice",
            "bob",
            "clicking the login button does nothing",
            "re-issue the session first",
            CREATED,
            UPDATED,
            CUT),
        blocked.withWorkspaces(CUT));

    assertEquals(
        new TicketDto(
            "t-1",
            "proj-1",
            1337L,
            "qits-1337",
            "Login button does nothing",
            "login-button-does-nothing",
            "BUG",
            "REFINED",
            true,
            "alice",
            "bob",
            "clicking the login button does nothing",
            "re-issue the session first",
            CREATED,
            UPDATED,
            List.of()),
        blocked.withQualifiedId("qits-1337"));
  }

  @Test
  void anAbsentWorkspaceListIsAnEmptyOneAndNothingElseMoves() {
    // The one normalisation either wither does, and the place a null would otherwise reach a
    // caller iterating the list: it must stay a normalisation and not become a reset.
    TicketDto answered = ticket(true).withWorkspaces(null);
    assertEquals(List.of(), answered.workspaces());
    assertTrue(answered.blocked());
  }
}
