package eu.wohlben.qits.projects.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.control.ReleaseRequestSettingsParser.ReleaseRequestSettingsException;
import org.junit.jupiter.api.Test;

/**
 * The pure parse of {@code .config/qits/release-requests.yml}. Instantiated directly (no CDI), like
 * {@link QitsConfigParserTest} beside it.
 *
 * <p>The load-bearing half of this suite is the negative: every way the file can be wrong has to
 * <b>throw</b> rather than answer {@code false}, because answering false is silently deleting an
 * approval gate.
 */
class ReleaseRequestSettingsParserTest {

  private final ReleaseRequestSettingsParser parser = new ReleaseRequestSettingsParser();

  @Test
  void manualReviewTrueIsTheApprovalGate() {
    assertTrue(parser.parse("manual-review: true\n").manualReview());
  }

  @Test
  void absentContentAbsentKeyAndFalseAreOneAnswer() {
    assertFalse(parser.parse(null).manualReview());
    assertFalse(parser.parse("").manualReview());
    assertFalse(parser.parse("   \n").manualReview());
    assertFalse(parser.parse("# nothing but a comment\n").manualReview());
    assertFalse(parser.parse("something-else: 1\n").manualReview());
    assertFalse(parser.parse("manual-review: false\n").manualReview());
    assertEquals(ReleaseRequestSettings.NONE, parser.parse("manual-review: false\n"));
  }

  @Test
  void anUnknownKeyIsIgnoredSoAnOlderReaderStillAnswers() {
    assertTrue(parser.parse("manual-review: true\nsome-later-key: whatever\n").manualReview());
  }

  @Test
  void aFileThatWillNotParseThrowsNamingTheFile() {
    ReleaseRequestSettingsException e =
        assertThrows(
            ReleaseRequestSettingsException.class, () -> parser.parse("manual-review: [unclosed\n"));
    assertTrue(e.getMessage().contains(ReleaseRequestSettingsParser.SETTINGS_PATH));
  }

  @Test
  void aDocumentThatIsNotAMappingThrowsNamingTheFile() {
    ReleaseRequestSettingsException e =
        assertThrows(ReleaseRequestSettingsException.class, () -> parser.parse("- manual-review\n"));
    assertTrue(e.getMessage().contains(ReleaseRequestSettingsParser.SETTINGS_PATH));
  }

  @Test
  void aManualReviewThatIsNotABooleanThrowsRatherThanReadingAsFalse() {
    ReleaseRequestSettingsException e =
        assertThrows(
            ReleaseRequestSettingsException.class, () -> parser.parse("manual-review: maybe\n"));
    assertTrue(e.getMessage().contains(ReleaseRequestSettingsParser.MANUAL_REVIEW));
    assertTrue(e.getMessage().contains(ReleaseRequestSettingsParser.SETTINGS_PATH));
  }

  // ---- feature_freeze (epic qits-1580) ---------------------------------------------------------

  @Test
  void anAbsentFeatureFreezeIsNullSoTheProjectDecides() {
    assertNull(parser.parse(null).featureFreeze());
    assertNull(parser.parse("manual-review: true\n").featureFreeze());
    assertEquals(new ReleaseRequestSettings(true, null), parser.parse("manual-review: true\n"));
  }

  @Test
  void aStatedFeatureFreezeIsReadBesideManualReviewAndZeroIsAValue() {
    ReleaseRequestSettings both = parser.parse("manual-review: true\nfeature_freeze: 5\n");
    assertTrue(both.manualReview());
    assertEquals(5, both.featureFreeze());
    assertEquals(0, parser.parse("feature_freeze: 0\n").featureFreeze());
    assertEquals(20, parser.parse("feature_freeze: 20\n").featureFreeze());
    assertFalse(parser.parse("feature_freeze: 2\n").manualReview());
  }

  @Test
  void aMalformedFeatureFreezeThrowsNamingTheKeyTheRangeAndTheFile() {
    for (String bad :
        new String[] {
          "feature_freeze: -1\n",
          "feature_freeze: 21\n",
          "feature_freeze: x\n",
          "feature_freeze: \"3\"\n",
          "feature_freeze: 2.5\n",
        }) {
      ReleaseRequestSettingsException e =
          assertThrows(ReleaseRequestSettingsException.class, () -> parser.parse(bad), bad);
      assertTrue(e.getMessage().contains("an integer from 0 to 20"), e.getMessage());
      assertTrue(e.getMessage().contains(ReleaseRequestSettingsParser.FEATURE_FREEZE), e.getMessage());
      assertTrue(e.getMessage().contains(ReleaseRequestSettingsParser.SETTINGS_PATH), e.getMessage());
    }
  }
}
