package eu.wohlben.qits.projects.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.control.ProjectConfigParser.ProjectConfigException;
import org.junit.jupiter.api.Test;

/**
 * The pure parse of {@code .config/qits/project.yml}. Instantiated directly (no CDI), like {@link
 * ReleaseRequestSettingsParserTest} and {@link QitsConfigParserTest} beside it.
 *
 * <p>The load-bearing half of this suite is the negative: every way the file can be wrong has to
 * <b>throw</b> rather than answer either value, because answering silently re-routes a project's
 * deployments off a typo.
 */
class ProjectConfigParserTest {

  private final ProjectConfigParser parser = new ProjectConfigParser();

  @Test
  void absentContentAbsentKeyAndTrueAreOneAnswer() {
    assertTrue(parser.parse(null).supportsEnvironments());
    assertTrue(parser.parse("").supportsEnvironments());
    assertTrue(parser.parse("   \n").supportsEnvironments());
    assertTrue(parser.parse("# nothing but a comment\n").supportsEnvironments());
    assertTrue(parser.parse("something-else: 1\n").supportsEnvironments());
    assertTrue(parser.parse("supports_environments: true\n").supportsEnvironments());
    assertEquals(ProjectConfig.DEFAULT, parser.parse("supports_environments: true\n"));
  }

  @Test
  void onlyAnExplicitFalseChangesAnything() {
    assertFalse(parser.parse("supports_environments: false\n").supportsEnvironments());
    assertEquals(
        ProjectConfig.SINGLE_ENVIRONMENT, parser.parse("supports_environments: false\n"));
  }

  @Test
  void anUnknownKeyIsIgnoredSoAnOlderReaderStillAnswers() {
    assertFalse(
        parser
            .parse("supports_environments: false\nsome-later-key: whatever\n")
            .supportsEnvironments());
  }

  @Test
  void aFileThatWillNotParseThrowsNamingTheFile() {
    ProjectConfigException e =
        assertThrows(
            ProjectConfigException.class, () -> parser.parse("supports_environments: [unclosed\n"));
    assertTrue(e.getMessage().contains(ProjectConfigParser.CONFIG_PATH));
  }

  @Test
  void aDocumentThatIsNotAMappingThrowsNamingTheFile() {
    ProjectConfigException e =
        assertThrows(ProjectConfigException.class, () -> parser.parse("- supports_environments\n"));
    assertTrue(e.getMessage().contains(ProjectConfigParser.CONFIG_PATH));
  }

  /**
   * The typo case this whole class is shaped around: {@code "false"} quoted, {@code no}, a number —
   * none of them is a boolean, and reading any of them as one would re-route a project on a
   * misspelling. It throws, the reconcile keeps the stored flag, and a person fixes the file.
   */
  @Test
  void aSupportsEnvironmentsThatIsNotABooleanThrowsRatherThanBeingGuessed() {
    for (String bad :
        new String[] {
          "supports_environments: 'false'\n",
          "supports_environments: nope\n",
          "supports_environments: 0\n",
          "supports_environments:\n  nested: true\n"
        }) {
      ProjectConfigException e =
          assertThrows(ProjectConfigException.class, () -> parser.parse(bad), bad);
      assertTrue(e.getMessage().contains(ProjectConfigParser.CONFIG_PATH), bad);
      assertTrue(e.getMessage().contains(ProjectConfigParser.SUPPORTS_ENVIRONMENTS), bad);
    }
  }
}
