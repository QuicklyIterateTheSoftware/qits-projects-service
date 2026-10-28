package eu.wohlben.qits.projects.entitieshost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.projects.control.ReleaseRequestWithdrawalComments.Lost;
import eu.wohlben.qits.projects.control.ReleaseRequestWithdrawalComments.Withdrawn;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link WorkItemWithdrawalComments} (qits-1671) without an application: the body for each verdict,
 * one comment per distinct work item, no comment for a branch naming none, and a failed comment that
 * neither stops the others nor throws. The resolution and the write are the class's two seams
 * ({@code lookup}, {@code comment}), replaced here by a map and a list; the branch grammar between
 * them is the real {@link WorkItemBranches}.
 */
class WorkItemWithdrawalCommentsTest {

  /** The class with its two seams scripted. */
  static final class Scripted extends WorkItemWithdrawalComments {
    final Map<String, CommitSubjectEntities.NamedEntity> items;
    final Set<String> failing;
    final List<String[]> written = new ArrayList<>();

    Scripted(Map<String, CommitSubjectEntities.NamedEntity> items, Set<String> failing) {
      this.items = items;
      this.failing = failing;
    }

    @Override
    Optional<CommitSubjectEntities.NamedEntity> lookup(CommitSubjectEntities.QualifiedId id) {
      return Optional.ofNullable(items.get(id.rendered()));
    }

    @Override
    void comment(String itemId, String body) {
      if (failing.contains(itemId)) {
        throw new IllegalStateException("the thread could not be written");
      }
      written.add(new String[] {itemId, body});
    }
  }

  private static CommitSubjectEntities.NamedEntity ticket(long number) {
    return new CommitSubjectEntities.NamedEntity(
        "ticket-id-" + number,
        "qits-" + number,
        "project-qits",
        "qits",
        number,
        Archetype.TICKET,
        "IMPLEMENTING",
        "ticket " + number,
        null);
  }

  private static Withdrawn withdrawn(List<String> branches, String deleted, Lost lost) {
    return new Withdrawn(
        "rr-uuid",
        "qits-qits-rr-226",
        "repo-id",
        "qits-qits",
        "project-qits",
        deleted,
        branches,
        "main",
        lost);
  }

  @Test
  void nothingSaysNoWorkWasLost() {
    String body =
        WorkItemWithdrawalComments.body(
            withdrawn(List.of("main", "ticket/qits-1675"), "ticket/qits-1675", Lost.NOTHING));
    assertEquals(
        "Release request `qits-qits-rr-226` was withdrawn: its source `ticket/qits-1675` was"
            + " deleted on the git host, which left only `main`.\n\n"
            + "Its fold added nothing to `main`, so no work of this item was lost: what it carried"
            + " is already released.",
        body);
  }

  @Test
  void unreleasedSaysTheWorkWasNotReleased() {
    String body =
        WorkItemWithdrawalComments.body(
            withdrawn(List.of("main", "ticket/qits-1675"), "ticket/qits-1675", Lost.UNRELEASED));
    assertTrue(body.startsWith("Release request `qits-qits-rr-226` was withdrawn:"), body);
    assertTrue(
        body.endsWith(
            "Its fold held commits `main` does not have, so the work on `ticket/qits-1675` was"
                + " not released; ask for its release again."),
        body);
    assertFalse(body.contains("lost"), "never says nothing was lost: " + body);
  }

  @Test
  void unknownSaysItCouldNotBeEstablished() {
    String body =
        WorkItemWithdrawalComments.body(
            withdrawn(List.of("main", "ticket/qits-1675"), "ticket/qits-1675", Lost.UNKNOWN));
    assertTrue(
        body.endsWith("Whether its fold added anything to `main` could not be established."), body);
    assertFalse(body.contains("lost"), "never says nothing was lost: " + body);
    assertFalse(body.contains("released"), body);
  }

  @Test
  void eachWorkItemIsToldOnceAndABranchNamingNoneIsIgnored() {
    Scripted comments =
        new Scripted(Map.of("qits-1675", ticket(1675), "qits-1676", ticket(1676)), Set.of());

    comments.withdrawn(
        withdrawn(
            List.of(
                "main",
                "ticket/qits-1675",
                "ticket/qits-1675-fix-totals",
                "work",
                "ticket/qits-1676"),
            "ticket/qits-1675",
            Lost.NOTHING));

    assertEquals(2, comments.written.size(), "one comment per distinct work item");
    assertEquals("ticket-id-1675", comments.written.get(0)[0]);
    assertEquals("ticket-id-1676", comments.written.get(1)[0]);
    assertTrue(comments.written.get(0)[1].contains("`qits-qits-rr-226`"));
  }

  @Test
  void aRequestNamingNoWorkItemWritesNothing() {
    Scripted comments = new Scripted(Map.of(), Set.of());

    comments.withdrawn(withdrawn(List.of("main", "work"), "work", Lost.UNRELEASED));

    assertTrue(comments.written.isEmpty());
  }

  @Test
  void aFailedCommentNeitherStopsTheOthersNorThrows() {
    Scripted comments =
        new Scripted(
            Map.of("qits-1675", ticket(1675), "qits-1676", ticket(1676)), Set.of("ticket-id-1675"));

    comments.withdrawn(
        withdrawn(
            List.of("main", "ticket/qits-1675", "ticket/qits-1676"),
            "ticket/qits-1675",
            Lost.UNKNOWN));

    assertEquals(1, comments.written.size());
    assertEquals("ticket-id-1676", comments.written.get(0)[0]);
  }

  @Test
  void theDeletedBranchIsToldEvenWhenTheSourcesLeftItOut() {
    Scripted comments = new Scripted(Map.of("qits-1675", ticket(1675)), Set.of());

    comments.withdrawn(withdrawn(List.of("main"), "ticket/qits-1675", Lost.NOTHING));

    assertEquals(1, comments.written.size());
  }
}
