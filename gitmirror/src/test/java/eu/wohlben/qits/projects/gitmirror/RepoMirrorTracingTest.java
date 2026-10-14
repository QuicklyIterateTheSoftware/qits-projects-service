package eu.wohlben.qits.projects.gitmirror;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code git.mirror.refresh} (qits-1202): one span around {@link RepoMirror#refreshNow()}, saying
 * which branch ran and how long the caller queued for the fetch lock, with the clone or fetch
 * process span opened while it is — so nested under it in a real tracer.
 */
class RepoMirrorTracingTest {

  @TempDir Path tmp;

  private final RecordingTracing tracing = new RecordingTracing();
  private final String repoId = "repo-under-test";
  private LocalBares remotes;

  @BeforeEach
  void setUp() throws Exception {
    Path bare = TestBare.create(tmp, "origin");
    remotes = new LocalBares();
    remotes.register(repoId, bare);
  }

  private RepoMirror mirror(Duration freshness) {
    return new GitMirrors(
            new GitCli(tracing),
            remotes,
            tmp.resolve("projects-data"),
            Duration.ofSeconds(60),
            freshness)
        .of(repoId);
  }

  @Test
  void aFirstRefreshIsACloneNestedUnderTheRefreshSpanAndASecondAFetch() {
    RepoMirror mirror = mirror(Duration.ZERO);

    mirror.refreshNow();

    RecordingTracing.Recorded refresh = tracing.only(RepoMirror.REFRESH_SPAN);
    assertEquals(repoId, refresh.attributes.get(GitProcessSpans.REPO_ID));
    assertEquals("clone", refresh.attributes.get(RepoMirror.REFRESH_OP));
    assertInstanceOf(Long.class, refresh.attributes.get(RepoMirror.FETCH_LOCK_WAIT_MS));
    assertTrue((Long) refresh.attributes.get(RepoMirror.FETCH_LOCK_WAIT_MS) >= 0);
    assertTrue(refresh.closed);
    assertSame(null, refresh.parent, "the refresh is the root here");

    RecordingTracing.Recorded clone = tracing.only("git clone");
    assertSame(refresh, clone.parent, "the clone process ran inside the refresh");
    assertEquals(repoId, clone.attributes.get(GitProcessSpans.REPO_ID));
    assertEquals(60_000L, clone.attributes.get(GitProcessSpans.TIMEOUT_MS));
    assertEquals(0L, clone.attributes.get(GitProcessSpans.EXIT_CODE));

    tracing.spans.clear();
    mirror.refreshNow();

    RecordingTracing.Recorded second = tracing.only(RepoMirror.REFRESH_SPAN);
    assertEquals("fetch", second.attributes.get(RepoMirror.REFRESH_OP));
    assertSame(second, tracing.only("git fetch").parent);
  }

  @Test
  void aRefreshAnsweredFromTheFreshnessWindowEmitsNothing() {
    RepoMirror mirror = mirror(Duration.ofHours(1));
    mirror.refreshNow();
    tracing.spans.clear();

    mirror.refresh();

    assertEquals(0, tracing.spans.size(), "nothing ran, so nothing was traced: " + tracing.spans);
  }

  @Test
  void aRefreshThatFailsFailsItsSpan() {
    remotes.register(repoId, tmp.resolve("no-such-bare.git"));
    RepoMirror mirror = mirror(Duration.ZERO);

    assertThrows(GitMirrorException.class, mirror::refreshNow);

    RecordingTracing.Recorded refresh = tracing.only(RepoMirror.REFRESH_SPAN);
    assertTrue(refresh.failed());
    assertTrue(refresh.closed);
    assertEquals("clone", refresh.attributes.get(RepoMirror.REFRESH_OP));
  }
}
