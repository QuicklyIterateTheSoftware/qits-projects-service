package eu.wohlben.qits.projects.control;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * qits-1066: every git invocation {@link GitExecutor} spawns must carry {@code -c
 * gc.autoDetach=false -c maintenance.autoDetach=false} ahead of its subcommand, so a {@code git
 * maintenance run --auto --detach} that git itself would otherwise fire off after almost any
 * command never detaches — the detached grandchild used to be reparented onto this process's
 * native-image PID 1, which never reaps, and that is how thousands of zombie {@code git}s piled
 * up. Two claims: the exact argv the guard builds, driven directly as a pure function, and that a
 * real invocation still runs correctly with it spliced in.
 */
class GitExecutorTest {

  @TempDir Path tmp;

  @Test
  void theGuardLandsRightAfterTheBinaryAndBeforeTheSubcommand() {
    assertArrayEquals(
        new String[] {
          "git",
          "-c",
          "gc.autoDetach=false",
          "-c",
          "maintenance.autoDetach=false",
          "rev-parse",
          "HEAD"
        },
        GitExecutor.withMaintenanceGuard(new String[] {"git", "rev-parse", "HEAD"}));
  }

  /**
   * {@code GitRemoteAuth.gitWithCredentials} already splices its own {@code -c
   * credential.helper=…} flag in right after {@code "git"} and before the subcommand, for every
   * remote-touching call. The maintenance guard has to land ahead of that flag too, never behind
   * it — both are {@code -c} flags and order between the two does not change git's behaviour, but
   * the guard's own contract ("before the subcommand") must hold regardless of what else the
   * caller already put there.
   */
  @Test
  void theGuardLandsAheadOfAnyCallerSuppliedConfigFlagsToo() {
    assertArrayEquals(
        new String[] {
          "git",
          "-c",
          "gc.autoDetach=false",
          "-c",
          "maintenance.autoDetach=false",
          "-c",
          "credential.helper=store --file=/tmp/x",
          "push",
          "origin",
          "main"
        },
        GitExecutor.withMaintenanceGuard(
            new String[] {
              "git",
              "-c",
              "credential.helper=store --file=/tmp/x",
              "push",
              "origin",
              "main"
            }));
  }

  @Test
  void anEmptyCommandIsLeftAlone() {
    assertArrayEquals(new String[0], GitExecutor.withMaintenanceGuard(new String[0]));
  }

  @Test
  void aRealInvocationStillRunsWithTheGuardSplicedIn() throws Exception {
    GitExecutor git = new GitExecutor();
    String output = git.exec(tmp.toFile(), "git", "init", "--bare", "--quiet");
    assertEquals("", output);
    assertTrue(tmp.resolve("HEAD").toFile().isFile(), "a bare repo was really initialized");
  }
}
