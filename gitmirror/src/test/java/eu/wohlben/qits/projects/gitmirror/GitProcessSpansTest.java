package eu.wohlben.qits.projects.gitmirror;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The redaction and naming rules every git process span is made by (qits-1202), as pure functions.
 * The two spawners that use them — {@link GitCli} and the domain's {@code GitExecutor} — are proven
 * to <em>call</em> them by their own tests; what is pinned here is what they answer, because a
 * redaction rule that is only ever exercised through a real process is one nobody reads.
 */
class GitProcessSpansTest {

  // -----------------------------------------------------------------------------------------
  // the name
  // -----------------------------------------------------------------------------------------

  @Test
  void theNameIsTheSubcommandAfterTheBinary() {
    assertEquals("git rev-parse", GitProcessSpans.spanName("git", "rev-parse", "HEAD"));
    assertEquals("git fetch", GitProcessSpans.spanName("/usr/bin/git", "fetch", "--prune"));
  }

  @Test
  void theNameSkipsGlobalOptionsAndTheValuesTheyTake() {
    assertEquals(
        "git push",
        GitProcessSpans.spanName(
            "git",
            "-c",
            "http.extraHeader=Authorization: Bearer s3cr3t",
            "-C",
            "/some/dir",
            "--git-dir",
            "/a/b.git",
            "--work-tree",
            "/a/tree",
            "--no-pager",
            "--git-dir=/c.git",
            "push",
            "origin"));
  }

  @Test
  void aGitWithNoSubcommandIsJustGit() {
    assertEquals("git", GitProcessSpans.spanName("git", "--version"));
    assertEquals("git", GitProcessSpans.spanName("git", "-c", "a=b"));
    assertEquals("git", GitProcessSpans.spanName());
  }

  @Test
  void aCommandThatIsNotGitIsNamedByItsBinary() {
    assertEquals("sh", GitProcessSpans.spanName("/bin/sh", "-c", "printf x"));
    assertEquals("", GitProcessSpans.redactedArgs("/bin/sh", "-c", "printf x"));
  }

  // -----------------------------------------------------------------------------------------
  // the redaction
  // -----------------------------------------------------------------------------------------

  @Test
  void theArgsAreWhatFollowsTheSubcommand() {
    assertEquals(
        "--verify --quiet --end-of-options HEAD",
        GitProcessSpans.redactedArgs(
            "git",
            "-c",
            "gc.autoDetach=false",
            "rev-parse",
            "--verify",
            "--quiet",
            "--end-of-options",
            "HEAD"));
  }

  @Test
  void everyConfigPairIsDroppedWholeWhereverItSits() {
    String args =
        GitProcessSpans.redactedArgs(
            "git",
            "-c",
            "credential.helper=!f() { echo password=s3cr3t; }; f",
            "clone",
            "-c",
            "http.extraHeader=Authorization: Bearer s3cr3t",
            "--config",
            "http.extraHeader=Authorization: Bearer s3cr3t",
            "--config=http.extraHeader=Authorization: Bearer s3cr3t",
            "--mirror",
            "/src.git",
            "/dst.git");
    assertEquals("--mirror /src.git /dst.git", args);
  }

  @Test
  void aUrlLosesItsUserinfoAndKeepsTheRest() {
    assertEquals("https://host/x", GitProcessSpans.stripUserinfo("https://user:tok@host/x"));
    assertEquals(
        "http://host:8080/a/b?c#d", GitProcessSpans.stripUserinfo("http://tok@host:8080/a/b?c#d"));
    assertEquals("https://host", GitProcessSpans.stripUserinfo("https://u:p@ss@host"));
    assertEquals(
        "--url=https://host/x and ssh://h/y",
        GitProcessSpans.stripUserinfo("--url=https://u:p@host/x and ssh://git@h/y"));
  }

  @Test
  void whatHasNoUserinfoIsLeftExactlyAlone() {
    assertEquals("https://host/x@y", GitProcessSpans.stripUserinfo("https://host/x@y"));
    assertEquals("git@github.com:o/r.git", GitProcessSpans.stripUserinfo("git@github.com:o/r.git"));
    assertEquals("+refs/*:refs/*", GitProcessSpans.stripUserinfo("+refs/*:refs/*"));
    assertEquals("/a/b.git", GitProcessSpans.stripUserinfo("/a/b.git"));
  }

  @Test
  void describeIsTheNameAndTheRedactedArgsAndNothingSecret() {
    String described =
        GitProcessSpans.describe(
            "git",
            "-c",
            "http.extraHeader=Authorization: Bearer s3cr3t",
            "ls-remote",
            "https://user:tok@githost/r.git");
    assertEquals("git ls-remote https://githost/r.git", described);
  }

  // -----------------------------------------------------------------------------------------
  // the span
  // -----------------------------------------------------------------------------------------

  @Test
  void theSpanCarriesTheRedactedArgsTheCallersAttributesAndTheTimeout() {
    RecordingTracing tracing = new RecordingTracing();
    try (GitTracing.Span span =
        GitProcessSpans.start(
            tracing,
            new String[] {
              "git", "-c", "http.extraHeader=Bearer s3cr3t", "fetch", "https://u:tok@h/r"
            },
            Map.of(GitProcessSpans.REPO_ID, "repo-1"),
            Duration.ofSeconds(3))) {
      GitProcessSpans.exited(span, 0, "é\n");
    }
    RecordingTracing.Recorded span = tracing.only("git fetch");
    assertEquals("https://h/r", span.attributes.get(GitProcessSpans.ARGS));
    assertEquals("repo-1", span.attributes.get(GitProcessSpans.REPO_ID));
    assertEquals(3000L, span.attributes.get(GitProcessSpans.TIMEOUT_MS));
    assertEquals(0L, span.attributes.get(GitProcessSpans.EXIT_CODE));
    assertEquals(3L, span.attributes.get(GitProcessSpans.OUTPUT_BYTES), "UTF-8 bytes, not chars");
    assertFalse(span.failed());
    assertTrue(span.closed);
  }

  @Test
  void anExitBelow128IsAnAnswerAndFromThereOnAFailure() {
    RecordingTracing tracing = new RecordingTracing();
    for (int code : new int[] {1, 127, 128, 255}) {
      try (GitTracing.Span span =
          GitProcessSpans.start(tracing, new String[] {"git", "merge-tree"}, Map.of(), null)) {
        GitProcessSpans.exited(span, code, "");
      }
    }
    var spans = tracing.named("git merge-tree");
    assertFalse(spans.get(0).failed(), "merge-tree's conflict answer");
    assertFalse(spans.get(1).failed());
    assertTrue(spans.get(2).failed(), "git's own fatal:");
    assertTrue(spans.get(3).failed());
    assertFalse(spans.get(0).attributes.containsKey(GitProcessSpans.TIMEOUT_MS), "no bound given");
  }

  @Test
  void utf8LengthCountsBytes() {
    assertEquals(0, GitProcessSpans.utf8Length(null));
    assertEquals(3, GitProcessSpans.utf8Length("abc"));
    assertEquals(3, GitProcessSpans.utf8Length("€"));
    assertEquals(4, GitProcessSpans.utf8Length("😀"));
  }
}
