package eu.wohlben.qits.epics.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * <b>The audit vocabulary and the archetype vocabulary move together, or the build fails.</b>
 *
 * <p>{@code AuditEntityType}'s four planning words used to be a hand-written parallel list of the
 * four kinds, translated by a hand-written switch in {@code EntityTransitionService}. A fifth
 * archetype would have compiled against the enum, compiled against the switch's exhaustiveness only
 * if somebody noticed, and otherwise failed at the moment a row of that kind was first audited — in
 * production, inside a transaction, on somebody's write.
 *
 * <p>{@link AuditEntityType#of(Archetype)} derives the word instead, which turns that into a
 * question this class can ask: every {@link Archetype} constant must have a constant of the same
 * name here. It is a plain JUnit test and deliberately not a {@code @QuarkusTest} — it reads two
 * enums and needs no application, and a {@code @TestProfile} is roughly 125 MB of retained metaspace
 * inside a 4 GB CI step.
 */
class AuditEntityTypeTest {

  @Test
  void everyArchetypeHasAnAuditWordAndOfAnswersIt() {
    for (Archetype archetype : Archetype.values()) {
      AuditEntityType word = AuditEntityType.of(archetype);
      assertEquals(
          archetype.name(),
          word.name(),
          "the audit word for " + archetype + " is not spelled the same");
    }
  }

  /**
   * The other direction, and the reason the enum is not simply replaced by {@link Archetype}: two
   * words here name things that are not rows in {@code entity} at all. Stated so that a later reader
   * finds the asymmetry deliberate rather than left over.
   */
  @Test
  void theOnlyWordsThatAreNotArchetypesAreTheTwoThatAreNotEntities() {
    Set<String> archetypes =
        Arrays.stream(Archetype.values()).map(Enum::name).collect(Collectors.toSet());
    Set<String> extra =
        Arrays.stream(AuditEntityType.values())
            .map(Enum::name)
            .filter(word -> !archetypes.contains(word))
            .collect(Collectors.toSet());

    assertEquals(Set.of("TICKET_COMMENT", "DOSSIER_PAGE"), extra);
  }

  /**
   * The database's half of the same vocabulary. {@code ck_audit_entity_type} (V13, restating V5's
   * set unchanged) permits exactly these six words, so a constant added here without the migration
   * would be a row the column refuses.
   */
  @Test
  void theEnumIsExactlyWhatTheCheckConstraintPermits() {
    assertEquals(
        Set.of("EPIC", "FEATURE", "TASK", "TICKET", "TICKET_COMMENT", "DOSSIER_PAGE"),
        Arrays.stream(AuditEntityType.values()).map(Enum::name).collect(Collectors.toSet()));
    assertTrue(Archetype.values().length == 4, "a fifth archetype needs V14 as well as a constant");
  }
}
