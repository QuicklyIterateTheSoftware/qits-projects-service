package eu.wohlben.qits.projects.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link RepositoryService#shortDetail}, against the exact shapes a {@link GitExecutor} failure
 * takes (qits-1847) — no Quarkus boot, because the method reaches no database and no process.
 *
 * <p>These are the shapes {@code BackupPushServiceTest}'s own cases can't force on demand: a
 * message whose decisive line is buried under an {@code error:} line that is not the real reason,
 * one with neither {@code fatal:} nor {@code error:} at all, and one long enough that the column
 * cap actually bites.
 */
public class RepositoryServiceBackupDetailTest {

  /** The exact live message quoted in qits-1847: the command sentence plus git's one line. */
  @Test
  public void theLiveTwoLineMessageKeepsBothLines() {
    String message =
        "Backup of qits-qits failed: Command failed [128]: git -c credential.helper=store"
            + " --file=/data/git-credentials push --end-of-options"
            + " https://github.com/QuicklyIterateTheSoftware/qits-qits.git"
            + " refs/heads/main:refs/heads/main refs/tags/*:refs/tags/*\n"
            + "fatal: could not read Username for 'https://github.com': terminal prompts disabled";

    String detail = RepositoryService.shortDetail(message);

    assertTrue(
        detail.contains(
            "Backup of qits-qits failed: Command failed [128]: git -c credential.helper=store"),
        detail);
    assertTrue(
        detail.contains(
            "fatal: could not read Username for 'https://github.com': terminal prompts disabled"),
        detail);
    assertEquals(2, detail.lines().count(), detail);
  }

  /** A {@code fatal:} line wins over an {@code error:} line, whichever came first. */
  @Test
  public void aLaterFatalLineWinsOverAnEarlierErrorLine() {
    String message =
        "Backup of qits-demo failed: Command failed [1]: git push --end-of-options"
            + " https://forge.example.test/demo.git main:main refs/tags/*:refs/tags/*\n"
            + "Enumerating objects: 5, done.\n"
            + "error: failed to push some refs to 'https://forge.example.test/demo.git'\n"
            + "fatal: the remote end hung up unexpectedly";

    String detail = RepositoryService.shortDetail(message);

    assertTrue(detail.endsWith("fatal: the remote end hung up unexpectedly"), detail);
    assertTrue(
        !detail.contains("error: failed to push some refs"),
        "the fatal: line is the one kept, not the error: line: " + detail);
  }

  /**
   * What git really prints for a twin that is not there: the specific {@code fatal:} first, then
   * the generic one. The first is the cause and the one kept.
   */
  @Test
  public void theFirstOfTwoFatalLinesIsKept() {
    String message =
        "Backup of qits-demo failed: Command failed [128]: git push --end-of-options"
            + " /nonexistent/no-such-twin.git main:main refs/tags/*:refs/tags/*\n"
            + "fatal: '/nonexistent/no-such-twin.git' does not appear to be a git repository\n"
            + "fatal: Could not read from remote repository.\n"
            + "\n"
            + "Please make sure you have the correct access rights\n"
            + "and the repository exists.";

    String detail = RepositoryService.shortDetail(message);

    assertTrue(
        detail.endsWith(
            "fatal: '/nonexistent/no-such-twin.git' does not appear to be a git repository"),
        detail);
  }

  /** No {@code fatal:} and no {@code error:} anywhere: the last non-blank line of the rest. */
  @Test
  public void withNoFatalOrErrorLineTheLastNonBlankLineWins() {
    String message =
        "Backup of qits-demo failed: Command failed [1]: git push --end-of-options"
            + " https://forge.example.test/demo.git main:main refs/tags/*:refs/tags/*\n"
            + "Enumerating objects: 5, done.\n"
            + "Writing objects: 100% (5/5), done.\n"
            + "\n"
            + "remote: this repository is read-only.";

    String detail = RepositoryService.shortDetail(message);

    assertTrue(detail.endsWith("remote: this repository is read-only."), detail);
    assertEquals(2, detail.lines().count(), detail);
  }

  /**
   * The command sentence alone is 1500 characters — well past the column — and git's own {@code
   * fatal:} line must still survive intact at the end, with the command sentence cut short
   * (ending {@code …}) to make room for it.
   */
  @Test
  public void aLongArgvLineIsCutSoTheFatalLineSurvivesIntact() {
    String longArgv = "Backup of qits-demo failed: Command failed [128]: git " + "x".repeat(1500);
    String fatalLine = "fatal: could not read Username for 'https://github.com': terminal prompts disabled";
    String message = longArgv + "\n" + fatalLine;

    String detail = RepositoryService.shortDetail(message);

    assertTrue(detail.length() <= 1000, "it fits the column: " + detail.length());
    assertTrue(detail.endsWith(fatalLine), "git's own line is intact at the end: " + detail);
    java.util.List<String> lines = detail.lines().toList();
    assertEquals(2, lines.size(), detail);
    assertTrue(lines.get(0).endsWith("…"), "the cut command sentence ends with an ellipsis: " + lines.get(0));
  }

  /** Even git's own line alone, past the cap, is simply cut — never dropped. */
  @Test
  public void aFatalLineAloneAtOrPastTheCapIsCutRatherThanTheCommandLineSurviving() {
    String fatalLine = "fatal: " + "y".repeat(1200);
    String message = "Backup of qits-demo failed: Command failed [1]: git push\n" + fatalLine;

    String detail = RepositoryService.shortDetail(message);

    assertEquals(1000, detail.length());
    assertTrue(detail.startsWith("fatal: "), detail.substring(0, Math.min(20, detail.length())));
  }

  @Test
  public void nullIsNull() {
    assertNull(RepositoryService.shortDetail(null));
  }

  @Test
  public void blankIsNull() {
    assertNull(RepositoryService.shortDetail("   \n  \t "));
  }

  /** A single-line message behaves as it always did: capped, unchanged otherwise. */
  @Test
  public void aSingleLineMessageIsUnchanged() {
    String message = "Backup of qits-demo failed: Command failed [1]: git push";

    assertEquals(message, RepositoryService.shortDetail(message));
  }

  /** A single-line message over the cap is still cut the plain way: no ellipsis, no git line. */
  @Test
  public void aSingleLineMessageOverTheCapIsPlainlyCut() {
    String message = "x".repeat(1200);

    String detail = RepositoryService.shortDetail(message);

    assertEquals(1000, detail.length());
    assertEquals(message.substring(0, 1000), detail);
  }

  /** git's line, found, but identical to the command sentence: never duplicated. */
  @Test
  public void aGitLineIdenticalToTheFirstLineIsNotDuplicated() {
    String line = "Backup of qits-demo failed: Command failed [1]: git push";
    String message = line + "\n" + line;

    assertEquals(line, RepositoryService.shortDetail(message));
  }
}
