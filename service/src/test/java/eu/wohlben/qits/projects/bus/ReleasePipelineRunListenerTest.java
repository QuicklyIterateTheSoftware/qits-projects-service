package eu.wohlben.qits.projects.bus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.projects.control.ReleasePipelineRuns;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The listener's own half, without a container: the wire names as literals, what is bound, and above
 * all <b>which frames are dropped in silence</b>. What the mirror does with a transition — the
 * correlation, the ordering guard, the columns — is {@code ReleasePipelineReportingTest} with the
 * real database and the real read surface behind it.
 *
 * <p>The payloads here are hand-written JSON rather than round-tripped records, deliberately and for
 * {@code BuildStatusListenerTest}'s reason: this service holds no qits-ci vocabulary jar, so the
 * strings below ARE the contract as consumed, and a spelling change over there has to be a diff
 * here.
 *
 * <p>A plain JUnit class rather than a {@code @QuarkusTest}: everything it asserts is this class's
 * own reading of a frame, and a Quarkus application per assertion of that kind is a hundred and
 * twenty-five megabytes of metaspace for nothing.
 */
class ReleasePipelineRunListenerTest {

  private ReleasePipelineRunListener listener;
  private RecordingPipelineRuns runs;

  /**
   * The mirror's far side. It records what it was handed rather than deciding anything, because
   * everything the real one decides — which request a run belongs to, whether a frame is newer than
   * the row — needs a database and is asserted where there is one.
   */
  private static final class RecordingPipelineRuns extends ReleasePipelineRuns {
    final List<Transition> recorded = new ArrayList<>();

    @Override
    public void record(Transition transition) {
      recorded.add(transition);
    }
  }

  @BeforeEach
  void setUp() {
    listener = new ReleasePipelineRunListener();
    runs = new RecordingPipelineRuns();
    listener.pipelineRuns = runs;
  }

  @Test
  void theWireNamesAreTheLiteralsQitsCiPublishesUnder() {
    // The wire contract, pinned as strings: a rename on either side has to be a diff here.
    assertEquals(Set.of("BuildStatusChanged"), listener.signatures());
  }

  /**
   * <b>The consumer id is storage and it is deliberately not {@code BuildStatusListener}'s.</b> Two
   * consumptions of one service's events claim and settle independently, so the verdict ledger is
   * never held behind this mirror and this mirror is never handed a watermark saying it has already
   * seen transitions it was never offered. Changing either value mints a brand-new consumer that
   * initializes at the head of the log.
   */
  @Test
  void theConsumerIdIsItsOwnAndIsNotTheVerdictListeners() {
    assertEquals("projects-release-pipeline-run", listener.consumerId());
    assertEquals("projects-build-status", new BuildStatusListener().consumerId());
  }

  /**
   * The ordinary QA transition: everything the mirror stores, plus the branch it correlates on —
   * and the frame's own instant, which is where {@code startedAt}, {@code finishedAt} and the
   * ordering all come from, because this event carries no timestamp of its own in the payload.
   */
  @Test
  void aPhasedTransitionIsHandedOverWithTheFramesOwnTime() {
    EventFrame running =
        frame(
            "{\"branch\":\"release/req-1\",\"commitSha\":\"abc123\",\"phase\":\"RELEASE_REQUEST\","
                + "\"previousStatus\":\"QUEUED\",\"projectId\":\"qits\",\"repoId\":\"repo-1\","
                + "\"repoName\":\"qits-ci\",\"runId\":\"run-1\",\"status\":\"RUNNING\"}");
    listener.onFrame(running);

    assertEquals(1, runs.recorded.size());
    ReleasePipelineRuns.Transition transition = runs.recorded.get(0);
    assertEquals("run-1", transition.runId());
    assertEquals("repo-1", transition.repoId());
    assertEquals("RELEASE_REQUEST", transition.phase());
    assertEquals("RUNNING", transition.status());
    assertEquals("release/req-1", transition.branch());
    assertNull(transition.releaseRequestId(), "BuildStatusChanged carries none; the branch is why");
    assertEquals(running.occurredAt(), transition.occurredAt());
    assertEquals(UUID.fromString(running.id()), transition.causationId());
  }

  /**
   * <b>A {@code releaseRequestId} on the payload wins.</b> The field is absent today and is bound
   * anyway, so the day qits-ci carries it the publisher's own answer is used with no change on this
   * side — which is the whole reason to bind a field nothing sends.
   */
  @Test
  void aPayloadThatNamesItsRequestIsBeliefedOverTheBranch() {
    listener.onFrame(
        frame(
            "{\"branch\":\"release/req-from-branch\",\"phase\":\"RELEASE\",\"repoId\":\"repo-1\","
                + "\"releaseRequestId\":\"req-from-payload\",\"runId\":\"run-2\","
                + "\"status\":\"SUCCESS\"}"));

    assertEquals("req-from-payload", runs.recorded.get(0).releaseRequestId());
  }

  /**
   * <b>The phase word is the whole membership test, and its absence is SILENT.</b> That is every
   * ordinary run on the platform and every transition of every release published before the cutover;
   * a warning there would be several lines per build forever, which is how a log stops being read.
   */
  @Test
  void aTransitionWithNoPhaseIsSkippedRatherThanMirroredOrWarnedAbout() {
    listener.onFrame(
        frame(
            "{\"branch\":\"main\",\"commitSha\":\"abc123\",\"repoId\":\"repo-1\","
                + "\"runId\":\"run-3\",\"status\":\"RUNNING\"}"));

    assertTrue(runs.recorded.isEmpty(), "a run with no phase is no part of a release");
  }

  @Test
  void aTransitionNamingNoRunOrNoRepositoryIsSkipped() {
    listener.onFrame(frame("{\"phase\":\"RELEASE\",\"repoId\":\"repo-1\",\"status\":\"SUCCESS\"}"));
    listener.onFrame(frame("{\"phase\":\"RELEASE\",\"runId\":\"run-4\",\"status\":\"SUCCESS\"}"));

    assertTrue(runs.recorded.isEmpty());
  }

  /** The column is not null and qits-ci writes the word on every transition, so this one is loud. */
  @Test
  void aTransitionWithNoStatusIsSkipped() {
    listener.onFrame(
        frame("{\"phase\":\"RELEASE\",\"repoId\":\"repo-1\",\"runId\":\"run-5\"}"));

    assertTrue(runs.recorded.isEmpty());
  }

  @Test
  void anUnreadablePayloadIsSwallowedRatherThanWedgingTheWatermark() {
    listener.onFrame(frame("not json at all"));

    assertTrue(runs.recorded.isEmpty());
  }

  @Test
  void aFrameIdThatIsNotAUuidCostsTheTraceEdgeAndNothingElse() {
    listener.onFrame(
        new EventFrame(
            "not-a-uuid",
            "BuildStatusChanged",
            Instant.parse("2026-09-16T12:00:00Z"),
            "{\"branch\":\"release/req-1\",\"phase\":\"RELEASE_REQUEST\",\"repoId\":\"repo-1\","
                + "\"runId\":\"run-6\",\"status\":\"QUEUED\"}",
            null,
            null,
            null));

    assertEquals(1, runs.recorded.size());
    assertNull(runs.recorded.get(0).causationId());
  }

  private static EventFrame frame(String payload) {
    return new EventFrame(
        UUID.randomUUID().toString(),
        "BuildStatusChanged",
        Instant.parse("2026-09-16T12:00:00Z"),
        payload,
        null,
        null,
        null);
  }
}
