package eu.wohlben.qits.projects.control;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.control.ReleaseArchetypeParser.ReleaseArchetypeException;
import org.junit.jupiter.api.Test;

/**
 * The pure parse of the two fields of {@link ReleaseArtifacts#SLOT_CONFIG} this class reads — the
 * {@code archetype:} a pipeline is composed from, and the {@code release-request:} slot a
 * repository may inline instead.
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
    assertTrue(parser.declaresQaPipeline("archetype: spa-frontend\n"));
  }

  @Test
  void absentContentAndAnAbsentOrBlankKeyAreAllFalse() {
    assertFalse(parser.declaresQaPipeline(null));
    assertFalse(parser.declaresQaPipeline(""));
    assertFalse(parser.declaresQaPipeline("   \n"));
    assertFalse(parser.declaresQaPipeline("# nothing but a comment\n"));
    assertFalse(parser.declaresQaPipeline("artifacts: []\n"));
    assertFalse(parser.declaresQaPipeline("archetype: \"\"\n"));
    assertFalse(parser.declaresQaPipeline("archetype: \n"));
  }

  @Test
  void anUnrelatedKeyIsIgnoredSoDeclaringArtifactsAloneComposesNothing() {
    // ReleaseArtifacts reads this same file for artifacts:/userflows: with no archetype: at all —
    // a repository that publishes without a composed pipeline is the ordinary case, not a failure.
    assertFalse(parser.declaresQaPipeline("artifacts:\n  - type: oci\n    name: qits/thing\n"));
  }

  @Test
  void anInlinedReleaseRequestSlotIsTrueWithNoArchetypeAtAll() {
    // qits-ci's CiReleaseComposer.choose takes a repository's own slot list outright when it is
    // there, archetype or not — so this file composes a QA pipeline and must be read as one.
    assertTrue(
        parser.declaresQaPipeline(
            "release-request:\n  - name: qa\n    run: npm ci && npm test\n"));
    assertTrue(parser.declaresQaPipeline("release-request:\n  steps:\n    - run: npm test\n"));
    assertTrue(parser.declaresQaPipeline("release-request: qa\n"));
  }

  @Test
  void aReleaseRequestKeyDeclaringNothingIsFalse() {
    // A key with nothing under it is not a pipeline.
    assertFalse(parser.declaresQaPipeline("release-request:\n"));
    assertFalse(parser.declaresQaPipeline("release-request: \"\"\n"));
    assertFalse(parser.declaresQaPipeline("release-request: []\n"));
    assertFalse(parser.declaresQaPipeline("release-request: {}\n"));
  }

  @Test
  void eitherKeyAloneIsEnoughAndBothTogetherAreTrueToo() {
    assertTrue(parser.declaresQaPipeline("archetype: java-service\n"));
    assertTrue(
        parser.declaresQaPipeline("archetype: java-service\nrelease-request:\n  - run: mvn verify\n"));
    // An archetype named beside an empty slot is still an archetype.
    assertTrue(parser.declaresQaPipeline("archetype: java-service\nrelease-request: []\n"));
  }

  @Test
  void aFileThatWillNotParseThrowsNamingTheFile() {
    ReleaseArchetypeException e =
        assertThrows(
            ReleaseArchetypeException.class, () -> parser.declaresQaPipeline("archetype: [unclosed\n"));
    assertTrue(e.getMessage().contains(ReleaseArtifacts.SLOT_CONFIG));
  }

  @Test
  void aDocumentThatIsNotAMappingThrowsNamingTheFile() {
    ReleaseArchetypeException e =
        assertThrows(ReleaseArchetypeException.class, () -> parser.declaresQaPipeline("- archetype\n"));
    assertTrue(e.getMessage().contains(ReleaseArtifacts.SLOT_CONFIG));
  }
}
