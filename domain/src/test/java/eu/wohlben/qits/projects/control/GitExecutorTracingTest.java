package eu.wohlben.qits.projects.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.gitmirror.GitProcessSpans;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link GitExecutor}'s process span (qits-1202): one per invocation, made by the same {@link
 * GitProcessSpans} {@code gitmirror}'s {@code GitCli} uses — so a credential helper's {@code -c}
 * never reaches it — plus the working directory, and absent entirely for an executor built with
 * {@code new}, which every plain test here does.
 */
class GitExecutorTracingTest {

  @TempDir Path tmp;

  private final StackTracing tracing = new StackTracing();

  private GitExecutor traced() {
    GitExecutor git = new GitExecutor();
    git.tracing = new FixedInstance<>(tracing);
    return git;
  }

  @Test
  void anUntracedExecutorStillRuns() throws Exception {
    assertEquals("", new GitExecutor().exec(tmp.toFile(), "git", "init", "--bare", "--quiet"));
  }

  @Test
  void everyOverloadIsOneSpanWithTheCwdAndNoCredential() throws Exception {
    GitExecutor git = traced();
    git.exec(tmp.toFile(), "git", "init", "--bare", "--quiet");

    GitExecutor.ExecResult result =
        git.execAllowNonZero(
            tmp.toFile(),
            "git",
            "-c",
            "credential.helper=!f() { echo password=s3cr3t; }; f",
            "rev-parse",
            "--verify",
            "--quiet",
            "refs/heads/absent");

    assertEquals(1, result.exitCode(), "the answer 'absent'");
    assertEquals(2, tracing.spans.size(), tracing.spans.toString());
    assertEquals("git init", tracing.spans.get(0).name);
    StackTracing.Recorded span = tracing.only("git rev-parse");
    assertEquals(tmp.toFile().getPath(), span.attributes.get(GitProcessSpans.CWD));
    assertEquals(1L, span.attributes.get(GitProcessSpans.EXIT_CODE));
    assertEquals(0L, span.attributes.get(GitProcessSpans.OUTPUT_BYTES));
    assertEquals("--verify --quiet refs/heads/absent", span.attributes.get(GitProcessSpans.ARGS));
    assertFalse(span.attributes.toString().contains("s3cr3t"), span.toString());
    assertTrue(span.errors.isEmpty(), "exit 1 is an answer");
    assertTrue(span.closed);
  }

  @Test
  void aFatalExitAndAFailedSpawnBothFailTheSpan() throws Exception {
    GitExecutor git = traced();

    GitExecutor.ExecResult fatal =
        git.execAllowNonZero(tmp.toFile(), "git", "rev-parse", "HEAD"); // not a repository
    assertTrue(fatal.exitCode() >= 128, fatal.output());

    try {
      git.execAllowNonZero(tmp.toFile(), "/no/such/binary", "x");
    } catch (java.io.IOException expected) {
      // the spawn itself failed
    }

    assertFalse(tracing.only("git rev-parse").errors.isEmpty());
    StackTracing.Recorded spawn = tracing.only("binary");
    assertFalse(spawn.errors.isEmpty());
    assertTrue(spawn.closed);
  }
}
