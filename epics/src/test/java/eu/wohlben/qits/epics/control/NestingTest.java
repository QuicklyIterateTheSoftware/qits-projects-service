package eu.wohlben.qits.epics.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.epics.entity.Archetype;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The nesting rule, and the post-state check that is the reason the transition API exists.
 *
 * <p>Plain JUnit for {@link ArchetypesTest}'s reason: these are pure functions over declarations and
 * an in-memory {@link EntityFacts}, so a {@code @QuarkusTest} would spend a whole application's
 * metaspace to ask them.
 */
class NestingTest {

  // ---- the rule, one pair at a time ------------------------------------------------------------

  @Test
  void anEpicCannotBePartOfAnEpic() {
    assertRefused(
        Archetype.EPIC,
        Archetype.EPIC,
        NestingViolation.Reason.NOT_NESTABLE);
  }

  @Test
  void aTicketCannotBePartOfATicket() {
    assertRefused(Archetype.TICKET, Archetype.TICKET, NestingViolation.Reason.NOT_NESTABLE);
  }

  @Test
  void aTicketCannotBePartOfAnEpic() {
    // V4's "nothing joins the two tables and nothing should", said as a rule instead of as prose:
    // the two roots are declared at the same depth, so the ordinary comparison refuses it and
    // nothing here names either word.
    assertRefused(Archetype.EPIC, Archetype.TICKET, NestingViolation.Reason.NOT_NESTABLE);
  }

  @Test
  void aTaskCannotBePartOfATask() {
    assertRefused(Archetype.TASK, Archetype.TASK, NestingViolation.Reason.NOT_NESTABLE);
  }

  @Test
  void anEpicMayHoldATaskDirectly() {
    // A SKIPPED LEVEL, and it is legal on purpose: the rule is about containment, not about a fixed
    // number of rungs. Forbidding this would mean inventing a feature nobody wanted every time an
    // epic needs one concrete piece of work.
    assertAccepted(Archetype.EPIC, Archetype.TASK);
  }

  @Test
  void theOrdinaryTreeIsAccepted() {
    assertAccepted(Archetype.EPIC, Archetype.FEATURE);
    assertAccepted(Archetype.FEATURE, Archetype.TASK);
    assertAccepted(Archetype.TICKET, Archetype.FEATURE);
  }

  // ---- roots -----------------------------------------------------------------------------------

  @Test
  void aFeatureCannotStandOnItsOwn() {
    List<NestingViolation> violations =
        Nesting.check(List.of(EntityFact.root("f", Archetype.FEATURE)));

    assertEquals(1, violations.size(), () -> violations.toString());
    assertEquals(NestingViolation.Reason.NOT_A_ROOT, violations.get(0).reason());
  }

  @Test
  void theTwoRootsStandOnTheirOwn() {
    assertEquals(
        List.of(),
        Nesting.check(
            List.of(
                EntityFact.root("e", Archetype.EPIC), EntityFact.root("t", Archetype.TICKET))));
  }

  @Test
  void aParentThatIsNowhereIsItsOwnAnswerAndNotAnIllegalNesting() {
    List<NestingViolation> violations =
        Nesting.check(List.of(new EntityFact("f", Archetype.FEATURE, "gone")));

    assertEquals(1, violations.size(), () -> violations.toString());
    assertEquals(NestingViolation.Reason.UNKNOWN_PARENT, violations.get(0).reason());
  }

  @Test
  void anEntityCannotBePartOfItself() {
    List<NestingViolation> violations =
        Nesting.check(List.of(new EntityFact("f", Archetype.FEATURE, "f")));

    assertEquals(1, violations.size(), () -> violations.toString());
    assertEquals(NestingViolation.Reason.CYCLE, violations.get(0).reason());
  }

  @Test
  void statingOneEntityTwiceIsACallerBugAndNotAViolation() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            Nesting.check(
                List.of(
                    EntityFact.root("e", Archetype.EPIC),
                    new EntityFact("e", Archetype.EPIC, "other"))));
  }

  // ---- the case the whole design exists for ----------------------------------------------------

  /**
   * <b>The promotion, and the test that justifies the transition API.</b>
   *
   * <p>A feature is promoted to an epic and detaches from the one it was part of, and a task that
   * was hanging directly under that epic is reparented onto it. Neither half of the promotion is
   * legal on its own — re-archetype first and there is an epic under an epic; reparent first and
   * there is a feature at the root — and the post-state with both applied is legal. A validator
   * that judged one row at a time would refuse an operation that is correct, whichever order it
   * was handed.
   */
  @Test
  void aPromotionIsLegalOnlyWhenItsHalvesAreAppliedTogether() {
    // The store: an epic, a feature under it, and a task hanging directly under the epic.
    EntityFacts store =
        EntityFacts.of(
            List.of(
                EntityFact.root("e", Archetype.EPIC),
                new EntityFact("f", Archetype.FEATURE, "e"),
                new EntityFact("t", Archetype.TASK, "e")));

    // Re-archetype first: an epic under an epic.
    List<NestingViolation> reArchetypeFirst =
        Nesting.check(List.of(new EntityFact("f", Archetype.EPIC, "e")), store);
    assertEquals(1, reArchetypeFirst.size(), () -> reArchetypeFirst.toString());
    assertEquals("f", reArchetypeFirst.get(0).entityId());
    assertEquals(NestingViolation.Reason.NOT_NESTABLE, reArchetypeFirst.get(0).reason());

    // Reparent first: a feature at the root.
    List<NestingViolation> reparentFirst =
        Nesting.check(List.of(EntityFact.root("f", Archetype.FEATURE)), store);
    assertEquals(1, reparentFirst.size(), () -> reparentFirst.toString());
    assertEquals("f", reparentFirst.get(0).entityId());
    assertEquals(NestingViolation.Reason.NOT_A_ROOT, reparentFirst.get(0).reason());

    // Both at once, with the task moved onto the promoted row: a legal tree.
    List<NestingViolation> together =
        Nesting.check(
            List.of(
                EntityFact.root("f", Archetype.EPIC), new EntityFact("t", Archetype.TASK, "f")),
            store);
    assertEquals(List.of(), together);
  }

  // ---- the store's half ------------------------------------------------------------------------

  @Test
  void aChildTheCallerNeverMentionedIsJudgedAgainstItsParentsNewKind() {
    // Downwards resolution, and the reason EntityFacts is a lookup in two directions: re-archetyping
    // a row re-judges every child it already has. Nothing here mentions the task, and the task is
    // what the refusal names.
    EntityFacts store =
        EntityFacts.of(
            List.of(
                EntityFact.root("e", Archetype.EPIC),
                new EntityFact("f", Archetype.FEATURE, "e"),
                new EntityFact("t", Archetype.TASK, "f")));

    List<NestingViolation> violations =
        Nesting.check(List.of(new EntityFact("f", Archetype.TASK, "e")), store);

    assertEquals(1, violations.size(), () -> violations.toString());
    assertEquals("t", violations.get(0).entityId());
    assertEquals(NestingViolation.Reason.NOT_NESTABLE, violations.get(0).reason());
  }

  @Test
  void aParentTheCallerNeverMentionedIsReadFromTheStore() {
    // Upwards resolution: the task states a parent whose archetype only the store knows.
    EntityFacts store =
        EntityFacts.of(
            List.of(
                EntityFact.root("e", Archetype.EPIC),
                new EntityFact("f", Archetype.FEATURE, "e")));

    assertEquals(
        List.of(), Nesting.check(List.of(new EntityFact("t", Archetype.TASK, "f")), store));
  }

  @Test
  void everyViolationInAPostStateComesBackFromOneCall() {
    // One call, every problem — the same rule the property gate carries, and it matters more here
    // because the fixes are moves: told one at a time, a caller walks a tree through several
    // invalid shapes to reach a valid one.
    List<NestingViolation> violations =
        Nesting.check(
            List.of(
                EntityFact.root("e", Archetype.EPIC),
                new EntityFact("e2", Archetype.EPIC, "e"),
                EntityFact.root("f", Archetype.FEATURE),
                new EntityFact("t", Archetype.TASK, "nowhere")));

    assertEquals(3, violations.size(), () -> violations.toString());
    assertEquals(List.of("e2", "f", "t"), violations.stream().map(NestingViolation::entityId).toList());
  }

  // ---- the pair helper, for the caller that genuinely has one pair -------------------------------

  @Test
  void theSmallestFormOfTheRuleAgreesWithTheCheck() {
    assertTrue(Nesting.mayContain(Archetype.EPIC, Archetype.TASK));
    assertFalse(Nesting.mayContain(Archetype.EPIC, Archetype.TICKET));
  }

  // ---- helpers ---------------------------------------------------------------------------------

  private static void assertAccepted(Archetype parent, Archetype child) {
    assertEquals(List.of(), Nesting.check(pair(parent, child)), parent + " may hold " + child);
  }

  private static void assertRefused(
      Archetype parent, Archetype child, NestingViolation.Reason reason) {
    List<NestingViolation> violations = Nesting.check(pair(parent, child));
    assertEquals(1, violations.size(), () -> violations.toString());
    assertEquals("child", violations.get(0).entityId());
    assertEquals(reason, violations.get(0).reason());
    assertEquals(child, violations.get(0).archetype());
    assertEquals(parent, violations.get(0).parentArchetype());
  }

  /**
   * A self-contained post-state in which {@code parent}'s kind holds {@code child}'s, and in which
   * nothing else is wrong — so a violation that comes back is about the pair and about nothing
   * around it. A parent kind that may not stand alone is given a legal anchor above it rather than
   * being left at the root, which would add a NOT_A_ROOT finding to every such case.
   */
  private static List<EntityFact> pair(Archetype parent, Archetype child) {
    EntityFact edge = new EntityFact("child", child, "parent");
    if (Archetypes.mayBeRoot(parent)) {
      return List.of(EntityFact.root("parent", parent), edge);
    }
    return List.of(
        EntityFact.root("anchor", Archetype.EPIC),
        new EntityFact("parent", parent, "anchor"),
        edge);
  }
}
