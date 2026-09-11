package eu.wohlben.qits.projects.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
