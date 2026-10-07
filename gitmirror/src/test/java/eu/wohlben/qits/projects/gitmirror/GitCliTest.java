package eu.wohlben.qits.projects.gitmirror;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * qits-1066: every git invocation this module makes must carry {@code -c gc.autoDetach=false -c
 * maintenance.autoDetach=false} ahead of its subcommand, so a {@code git maintenance run --auto
 * --detach} that git itself would otherwise fire off after almost any command never detaches —
 * the detached grandchild used to be reparented onto this process's native-image PID 1, which
 * never reaps, and that is how thousands of zombie {@code git}s piled up. Two claims, in the same
 * shape as {@link PushCausationHeaderTest}: the exact argv the guard builds, driven directly as a
 * pure function, and that a real invocation still runs correctly with it spliced in.
 */
class GitCliTest {

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
        GitCli.withMaintenanceGuard(new String[] {"git", "rev-parse", "HEAD"}));
  }

  /**
   * {@code RepoMirror} splices its own {@code -c} flags (the causation header, the platform
   * bearer) in before the subcommand too — ahead of the maintenance guard here, since {@link
   * RepoMirror#push} and {@link RepoMirror#platformArgv} build those first and hand the whole
   * argv to {@code GitCli.run}. Whatever landed there, the maintenance guard still has to come
   * right after {@code "git"} and before all of it, never behind a caller-supplied {@code -c}.
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
          "http.extraHeader=X-Qits-Causation-Id: abc",
          "push",
          "--porcelain"
        },
        GitCli.withMaintenanceGuard(
            new String[] {
              "git", "-c", "http.extraHeader=X-Qits-Causation-Id: abc", "push", "--porcelain"
            }));
  }

  @Test
  void anEmptyArgvIsLeftAlone() {
    assertArrayEquals(new String[0], GitCli.withMaintenanceGuard(new String[0]));
  }

  @Test
  void aCommandThatIsNotGitIsLeftAlone() {
    String[] sh = {"sh", "-c", "printf x > f"};
    assertArrayEquals(sh, GitCli.withMaintenanceGuard(sh));
  }

  @Test
  void aGitBinaryGivenByPathIsStillGuarded() {
    assertArrayEquals(
        new String[] {
          "/usr/bin/git", "-c", "gc.autoDetach=false", "-c", "maintenance.autoDetach=false", "fetch"
        },
        GitCli.withMaintenanceGuard(new String[] {"/usr/bin/git", "fetch"}));
  }

  @Test
  void aRealInvocationStillRunsWithTheGuardSplicedIn() throws Exception {
    GitCli.Result result =
        new GitCli().run(tmp.toFile(), null, null, Duration.ofSeconds(30), "git", "init", "--bare", "--quiet");
    assertEquals(0, result.exitCode(), result.output());
    assertTrue(tmp.resolve("HEAD").toFile().isFile(), "a bare repo was really initialized");
  }
}
