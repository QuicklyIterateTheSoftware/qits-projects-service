package eu.wohlben.qits.projects.control;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.control.ReleaseArchetypeParser.ReleaseArchetypeException;
import org.junit.jupiter.api.Test;

/**
 * The pure parse of {@link ReleaseArtifacts#SLOT_CONFIG}'s one field this class reads.
 *
 * <p>Shaped after {@link ReleaseRequestSettingsParserTest}: the load-bearing half is the negative,
 * and it points the opposite way from that class's. There, a file that will not parse must throw, so
 * a typo can never delete the approval gate silently. Here, {@link ReleaseGates} is the one that
 * decides what a throw means, and it decides to hold the CI gate rather than release ungated — so
 * this suite only has to prove the throw happens; {@code ReleaseGateResolutionTest} is where the
 * "held rather than ungated" half is proved.
 */
class ReleaseArchetypeParserTest {

  private final ReleaseArchetypeParser parser = new ReleaseArchetypeParser();

  @Test
  void anArchetypeNamedIsTrue() {
    assertTrue(parser.declaresArchetype("archetype: spa-frontend\n"));
  }

  @Test
  void absentContentAndAnAbsentOrBlankKeyAreAllFalse() {
    assertFalse(parser.declaresArchetype(null));
    assertFalse(parser.declaresArchetype(""));
    assertFalse(parser.declaresArchetype("   \n"));
    assertFalse(parser.declaresArchetype("# nothing but a comment\n"));
    assertFalse(parser.declaresArchetype("artifacts: []\n"));
    assertFalse(parser.declaresArchetype("archetype: \"\"\n"));
    assertFalse(parser.declaresArchetype("archetype: \n"));
  }

  @Test
  void anUnrelatedKeyIsIgnoredSoDeclaringArtifactsAloneComposesNothing() {
    // ReleaseArtifacts reads this same file for artifacts:/userflows: with no archetype: at all —
    // a repository that publishes without a composed pipeline is the ordinary case, not a failure.
    assertFalse(parser.declaresArchetype("artifacts:\n  - type: oci\n    name: qits/thing\n"));
  }

  @Test
  void aFileThatWillNotParseThrowsNamingTheFile() {
    ReleaseArchetypeException e =
        assertThrows(
            ReleaseArchetypeException.class, () -> parser.declaresArchetype("archetype: [unclosed\n"));
    assertTrue(e.getMessage().contains(ReleaseArtifacts.SLOT_CONFIG));
  }

  @Test
  void aDocumentThatIsNotAMappingThrowsNamingTheFile() {
    ReleaseArchetypeException e =
        assertThrows(ReleaseArchetypeException.class, () -> parser.declaresArchetype("- archetype\n"));
    assertTrue(e.getMessage().contains(ReleaseArtifacts.SLOT_CONFIG));
  }
}
