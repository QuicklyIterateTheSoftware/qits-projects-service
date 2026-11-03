package eu.wohlben.qits.projects.control;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.error.BadRequestException;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * qits-367's guard on its own, with the kill switch in the test's hands: the twin a blank component
 * would be born with must answer an {@code ls-remote} before the create door writes anything —
 * unless there is no twin, or backups are switched off. Real git against real throwaway bares, as
 * everywhere else in this suite; the create door's end of it is {@code WrapperSubmoduleWriterTest}.
 */
class BackupTwinCheckTest {

  @TempDir Path tmp;

  private BackupTwinCheck check;

  @BeforeEach
  void wire() {
    GitRemoteAuth remoteAuth = new GitRemoteAuth();
    remoteAuth.credentialsFile = tmp.resolve("git-credentials").toString();
    check = new BackupTwinCheck();
    check.git = new GitExecutor();
    check.remoteAuth = remoteAuth;
    check.backupEnabled = true;
    check.timeout = Duration.ofSeconds(30);
  }

  /** The commonest twin of all: created on the forge, never pushed to — no refs, exit 0. */
  @Test
  void anEmptyForgeRepositoryIsATwinThatExists() throws Exception {
    Path twin = tmp.resolve("brand-new.git");
    check.git.exec(tmp.toFile(), "git", "init", "--bare", "--quiet", twin.toString());

    assertDoesNotThrow(() -> check.requireExists(twin.toString(), "brand-new"));
  }

  @Test
  void aMissingTwinIsRefusedNamingItTheDerivationBothCausesAndGitsAnswer() {
    String twin = tmp.resolve("never-forged.git").toString();

    BadRequestException refused =
        assertThrows(BadRequestException.class, () -> check.requireExists(twin, "never-forged"));

    assertEquals(400, refused.statusCode());
    String message = refused.getMessage();
    assertTrue(message.contains(twin), message);
    assertTrue(message.contains("wrapper's forge url plus ../never-forged.git"), message);
    assertTrue(message.contains("does not exist"), message);
    assertTrue(message.contains("sign in to its remote"), message);
    assertTrue(message.contains("git: fatal:"), "ends with git's own first error line: " + message);
  }

  @Test
  void switchedOffBackupsCheckNothing() {
    check.backupEnabled = false;

    assertDoesNotThrow(
        () -> check.requireExists(tmp.resolve("never-forged.git").toString(), "never-forged"));
  }

  /** A greenfield wrapper derives no twin, and there is nothing to ask a forge about. */
  @Test
  void noTwinChecksNothing() {
    assertDoesNotThrow(() -> check.requireExists(null, "orphan"));
  }
}
