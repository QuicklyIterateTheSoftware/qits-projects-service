package eu.wohlben.qits.projects.gitmirror;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * One span per git process (qits-1202), proven against real processes: the span is named, measured,
 * opened on the calling thread, failed only when git failed — and carries no secret from the argv,
 * whichever path the invocation ended on.
 */
class GitCliTracingTest {

  private static final String SECRET = "s3cr3t-token";

  @TempDir Path tmp;

  private final RecordingTracing tracing = new RecordingTracing();
  private final GitCli cli = new GitCli(tracing);

  @Test
  void theNoArgumentSpawnerTracesNothing() {
    assertSame(GitTracing.NONE, new GitCli().tracing());
    assertSame(GitTracing.NONE, new GitCli(null).tracing());
  }

  @Test
  void aRealRevParseIsOneSpanWithItsExitCodeAndOutputSize() throws Exception {
    new GitCli().run(tmp.toFile(), null, null, null, "git", "init", "--bare", "--quiet");

    GitCli.Result result =
        cli.run(
            Map.of(GitProcessSpans.REPO_ID, "repo-1"),
            tmp.toFile(),
            null,
            null,
            null,
            "git",
            "rev-parse",
            "--is-bare-repository");

    assertEquals(0, result.exitCode(), result.output());
    RecordingTracing.Recorded span = tracing.only("git rev-parse");
    assertEquals(0L, span.attributes.get(GitProcessSpans.EXIT_CODE));
    assertEquals(
        (long) result.output().length(), span.attributes.get(GitProcessSpans.OUTPUT_BYTES));
    assertEquals("--is-bare-repository", span.attributes.get(GitProcessSpans.ARGS));
    assertEquals("repo-1", span.attributes.get(GitProcessSpans.REPO_ID));
    assertNull(span.attributes.get(GitProcessSpans.TIMEOUT_MS), "a local call is unbounded");
    assertEquals(Thread.currentThread().getName(), span.thread, "opened on the caller's thread");
    assertFalse(span.failed());
    assertTrue(span.closed);
  }

  @Test
  void anAnswerOnExitOneIsNotAFailure() throws Exception {
    new GitCli().run(tmp.toFile(), null, null, null, "git", "init", "--bare", "--quiet");

    GitCli.Result result =
        cli.run(
            tmp.toFile(),
            null,
            null,
            Duration.ofSeconds(30),
            "git",
            "rev-parse",
            "--verify",
            "--quiet",
            "refs/heads/absent");

    assertEquals(1, result.exitCode());
    RecordingTracing.Recorded span = tracing.only("git rev-parse");
    assertEquals(1L, span.attributes.get(GitProcessSpans.EXIT_CODE));
    assertEquals(30_000L, span.attributes.get(GitProcessSpans.TIMEOUT_MS));
    assertFalse(span.failed());
  }

  @Test
  void aBearerAndAUrlsUserinfoReachNoAttributeAndAFatalExitFailsTheSpan() throws Exception {
    // Nothing listens on port 1, so this is git's own exit 128 within milliseconds — the real argv
    // shape RepoMirror.platformArgv builds, with a forge-style token in the URL as well.
    GitCli.Result result =
        cli.run(
            tmp.toFile(),
            null,
            null,
            Duration.ofSeconds(30),
            "git",
            "-c",
            "http.extraHeader=Authorization: Bearer " + SECRET,
            "ls-remote",
            "http://someone:" + SECRET + "@127.0.0.1:1/repo.git");

    assertTrue(result.exitCode() >= 128, result.output());
    RecordingTracing.Recorded span = tracing.only("git ls-remote");
    assertEquals("http://127.0.0.1:1/repo.git", span.attributes.get(GitProcessSpans.ARGS));
    assertNoSecretIn(span);
    assertTrue(span.failed(), "an exit of 128 or above is a failure");
  }

  @Test
  void aTimeoutFailsTheSpanAndItsMessageCarriesNoBearer() {
    TimeoutException timeout =
        assertThrows(
            TimeoutException.class,
            () ->
                cli.run(
                    tmp.toFile(),
                    null,
                    null,
                    Duration.ofMillis(300),
                    "git",
                    "-c",
                    "alias.hang=!sleep 2", // a grandchild: it holds the pipe until it ends
                    "-c",
                    "http.extraHeader=Authorization: Bearer " + SECRET,
                    "hang"));

    assertFalse(timeout.getMessage().contains(SECRET), timeout.getMessage());
    assertTrue(timeout.getMessage().contains("git hang"), timeout.getMessage());
    RecordingTracing.Recorded span = tracing.only("git hang");
    assertTrue(span.failed());
    assertNoSecretIn(span);
    assertTrue(span.closed);
  }

  private static void assertNoSecretIn(RecordingTracing.Recorded span) {
    span.attributes.forEach(
        (key, value) -> {
          String text = String.valueOf(value);
          assertFalse(text.contains(SECRET), key + " leaks the secret: " + text);
          assertFalse(text.contains("Bearer"), key + " leaks the header: " + text);
          assertFalse(text.contains("someone"), key + " leaks the userinfo: " + text);
        });
    span.errors.forEach(
        error -> assertFalse(String.valueOf(error).contains(SECRET), "error leaks: " + error));
  }
}
