package eu.wohlben.qits.projects.bus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.projects.control.BuildStatusLedger;
import eu.wohlben.qits.projects.control.ReleaseFinalization;
import eu.wohlben.qits.projects.control.ReleaseRequests;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The listener's own half, without a container: the wire names as literals, the decode, the status
 * word, and the poison rule. What the ledger does with a verdict is {@code CommitBuildStatusApiTest}
 * with the real database behind it.
 *
 * <p>The payloads here are hand-written JSON rather than round-tripped records, deliberately: this
 * service holds no qits-ci vocabulary jar, so the strings below ARE the contract as consumed — a
 * spelling change in qits-ci has to be a diff here, which is all a cross-repo string contract can
 * offer.
 */
class BuildStatusListenerTest {

  private BuildStatusListener listener;
  private RecordingLedger ledger;
  private RecordingRequests requests;
  private RecordingFinalization finalization;

  /**
   * The ledger stands in for the whole supersession walk here: this class is about what the listener
   * binds and hands over, and {@code answerSuperseded} is how a test says "the ledger reported that
   * this verdict cleared an ancestry" without a database to clear one in.
   */
  private static final class RecordingLedger extends BuildStatusLedger {
    final List<Verdict> recorded = new ArrayList<>();
    Set<String> superseded = Set.of();

    @Override
    public Set<String> record(Verdict verdict) {
      recorded.add(verdict);
      return superseded;
    }
  }

  private static final class RecordingRequests extends ReleaseRequests {
    final List<String> resolved = new ArrayList<>();
    final List<Set<String>> supersededSeen = new ArrayList<>();

    @Override
    public void onVerdict(String repoId, String commitSha, Set<String> supersededRunIds) {
      resolved.add(repoId + "@" + commitSha);
      supersededSeen.add(supersededRunIds);
    }
  }

  /**
   * The publish arm's far side. Every verdict is offered to it — a run's branch is either a released
   * version or it is not, and only this class knows which — so it records what it was told rather
   * than deciding anything.
   */
  private static final class RecordingFinalization extends ReleaseFinalization {
    final List<String> offered = new ArrayList<>();

    @Override
    public void onPublishVerdict(String repoId, String branch, String runId, boolean green) {
      offered.add(repoId + "@" + branch + "/" + runId + (green ? " green" : " red"));
    }
  }

  @BeforeEach
  void setUp() {
    listener = new BuildStatusListener();
    ledger = new RecordingLedger();
    requests = new RecordingRequests();
    finalization = new RecordingFinalization();
    listener.ledger = ledger;
    listener.releaseRequests = requests;
    listener.finalization = finalization;
  }

  /**
   * A verdict is offered to <b>both</b> arms, and the branch is what tells them apart: the fold gate
   * correlates on the commit sha, the publish gate on the branch naming a released version. This
   * listener decides neither — it hands the same event to each, which is what keeps the two
   * correlations in the classes that own the rows they read.
   */
  @Test
  void everyVerdictIsOfferedToThePublishGateWithItsBranchAndItsRun() {
    listener.onFrame(
        frame(
            "BuildSuccessful",
            "{\"runId\":\"run-9\",\"repoId\":\"repo-1\",\"branch\":\"2026.915.101010\","
                + "\"commitSha\":\"abc123\"}"));

    assertEquals(List.of("repo-1@2026.915.101010/run-9 green"), finalization.offered);
    assertEquals(List.of("repo-1@abc123"), requests.resolved, "and the fold arm is asked too");
  }

  @Test
  void aRedVerdictReachesThePublishGateAsRed() {
    listener.onFrame(
        frame(
            "BuildFailed",
            "{\"runId\":\"run-10\",\"repoId\":\"repo-1\",\"branch\":\"2026.915.101010\","
                + "\"commitSha\":\"abc123\",\"outcome\":\"FAILED\"}"));

    assertEquals(List.of("repo-1@2026.915.101010/run-10 red"), finalization.offered);
  }

  private static EventFrame frame(String name, String payload) {
    return new EventFrame(
        UUID.randomUUID().toString(),
        name,
        Instant.parse("2026-08-30T12:00:00Z"),
        payload,
        null,
        null,
        null);
  }

  @Test
  void theWireNamesAreTheLiteralsQitsCiPublishesUnder() {
    // The wire contract, pinned as strings: a rename on either side has to be a diff here.
    assertEquals(Set.of("BuildSuccessful", "BuildFailed"), listener.signatures());
    assertEquals("projects-build-status", listener.consumerId());
  }

  @Test
  void aGreenBuildRecordsSuccessWithTheFramesOwnTime() {
    EventFrame green =
        frame(
            "BuildSuccessful",
            "{\"branch\":\"main\",\"commitSha\":\"abc123\",\"finishedAt\":\"2026-08-30T12:00:00Z\","
                + "\"projectId\":\"qits\",\"repoId\":\"repo-1\",\"repoName\":\"qits-ci\","
                + "\"runId\":\"run-1\"}");
    listener.onFrame(green);

    assertEquals(1, ledger.recorded.size());
    BuildStatusLedger.Verdict verdict = ledger.recorded.get(0);
    assertEquals("run-1", verdict.runId());
    assertEquals("repo-1", verdict.repoId());
    assertEquals("qits", verdict.projectId());
    assertEquals("qits-ci", verdict.repoName());
    assertEquals("main", verdict.branch());
    assertEquals("abc123", verdict.commitSha());
    assertEquals("SUCCESS", verdict.status());
    assertEquals(green.occurredAt(), verdict.finishedAt());
    assertEquals(UUID.fromString(green.id()), verdict.causationId());
    assertEquals(
        List.of("repo-1@abc123"),
        requests.resolved,
        "the verdict that was just recorded resolves whatever request was waiting on it");
  }

  /**
   * <b>The crossing contract.</b> qits-ci still carries {@code gating} on both build events while
   * its own release of ticket 9441bc6e is in flight, and this service went first — so a payload
   * saying {@code "gating":false} has to be accepted and recorded as the ordinary red verdict it
   * is, rather than parsed specially, refused, or read as something the gate may skip. Nothing
   * handles the field: the wire mapper ignores unknown properties, which is exactly what makes a
   * removal safe to land ahead of the publisher's.
   */
  @Test
  void anIncomingGatingFieldIsIgnoredAndTheVerdictIsAnOrdinaryRedOne() {
    listener.onFrame(
        frame(
            "BuildFailed",
            "{\"commitSha\":\"abc123\",\"gating\":false,\"outcome\":\"FAILED\","
                + "\"repoId\":\"repo-1\",\"runId\":\"run-uf\"}"));

    assertEquals(1, ledger.recorded.size(), "the frame is not poison and is not skipped");
    assertEquals("FAILED", ledger.recorded.get(0).status());
    assertEquals("run-uf", ledger.recorded.get(0).runId());
  }

  /**
   * <b>The retry lineage is bound, and an absent one is null.</b> qits-ci carries {@code
   * retryOfRunId} on both build events from ticket qits-309 on; this service may land first, so the
   * two payloads below are the two states of the estate while the releases cross and both have to
   * be an ordinary verdict.
   */
  @Test
  void aRetrysVerdictCarriesTheRunItReFiresAndAnOrdinaryOneCarriesNull() {
    listener.onFrame(
        frame(
            "BuildSuccessful",
            "{\"commitSha\":\"abc123\",\"repoId\":\"repo-1\",\"retryOfRunId\":\"run-5\","
                + "\"runId\":\"run-6\"}"));
    assertEquals("run-5", ledger.recorded.get(0).retryOfRunId());

    listener.onFrame(
        frame("BuildSuccessful", "{\"commitSha\":\"abc123\",\"repoId\":\"repo-1\",\"runId\":\"run-7\"}"));
    assertNull(
        ledger.recorded.get(1).retryOfRunId(),
        "a qits-ci that has not released the field yet binds null, which is 'not a retry'");
  }

  /**
   * <b>What the request side is told is the LEDGER's answer, not the payload's.</b> The payload
   * names one superseded run; the ledger walked the ancestry that run belongs to, and a request
   * rejected two retries back is reachable only from the walk. The listener re-derives nothing — it
   * carries what it was handed.
   */
  @Test
  void theSupersededAncestryTheLedgerClearedIsWhatReachesTheRequests() {
    ledger.superseded = Set.of("run-1", "run-2");

    listener.onFrame(
        frame(
            "BuildSuccessful",
            "{\"commitSha\":\"abc123\",\"repoId\":\"repo-1\",\"retryOfRunId\":\"run-2\","
                + "\"runId\":\"run-3\"}"));

    assertEquals(List.of(Set.of("run-1", "run-2")), requests.supersededSeen);
  }

  @Test
  void aRedBuildRecordsItsOwnOutcomeWord() {
    listener.onFrame(
        frame(
            "BuildFailed",
            "{\"branch\":\"main\",\"commitSha\":\"abc123\",\"outcome\":\"TIMED_OUT\","
                + "\"repoId\":\"repo-1\",\"runId\":\"run-2\"}"));

    assertEquals("TIMED_OUT", ledger.recorded.get(0).status());
    assertNull(ledger.recorded.get(0).projectId(), "an id-addressed push announces no name pair");
  }

  @Test
  void aRedBuildWithNoOutcomeStillLandsAsFailedRatherThanAsPoison() {
    listener.onFrame(
        frame(
            "BuildFailed",
            "{\"branch\":\"main\",\"commitSha\":\"abc123\",\"repoId\":\"repo-1\","
                + "\"runId\":\"run-3\"}"));

    assertEquals("FAILED", ledger.recorded.get(0).status());
  }

  @Test
  void anUnreadablePayloadIsSwallowedRatherThanWedgingTheWatermark() {
    listener.onFrame(frame("BuildSuccessful", "not json at all"));

    assertTrue(ledger.recorded.isEmpty());
  }

  @Test
  void aVerdictNamingNoCoordinatesIsSkipped() {
    listener.onFrame(frame("BuildSuccessful", "{\"branch\":\"main\"}"));

    assertTrue(ledger.recorded.isEmpty());
  }

  @Test
  void aFrameIdThatIsNotAUuidCostsTheTraceEdgeAndNothingElse() {
    listener.onFrame(
        new EventFrame(
            "not-a-uuid",
            "BuildSuccessful",
            Instant.parse("2026-08-30T12:00:00Z"),
            "{\"commitSha\":\"abc123\",\"repoId\":\"repo-1\",\"runId\":\"run-4\"}",
            null,
            null,
            null));

    assertEquals(1, ledger.recorded.size());
    assertNull(ledger.recorded.get(0).causationId());
  }
}
