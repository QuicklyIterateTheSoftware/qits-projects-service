package eu.wohlben.qits.projects.bus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.eventstream.control.EventEnvelope;
import eu.wohlben.qits.projects.control.BranchDeliveryStep;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The wire contract of {@link BranchDeliveryChanged} (qits-1719), in {@link
 * ReleaseRequestStateChangedContractTest}'s shape: a consumer (qits-workspaces' wait matcher, the
 * CLI's presets) builds its selection against the keys asserted here, and every one of them is top
 * level. <b>Renaming one is a change there.</b> Serialised through {@link EventEnvelope#of}, the
 * path {@code QitsEventBus.publish} takes, from the adapter's own mapping.
 */
public class BranchDeliveryChangedContractTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final Instant AT = Instant.parse("2026-10-28T12:37:14Z");

  private static BranchDeliveryChanged moved() {
    return BranchDeliveryChangedAnnouncer.event(
        new BranchDeliveryStep(
            "p-1",
            "r-1",
            "qits-projects-service",
            "ticket/qits-1615",
            "rr-369",
            "qits-projects-service-rr-369",
            "abc123",
            null,
            "MOVED",
            "rr-368",
            "qits-projects-service-rr-368",
            null,
            null,
            null,
            "JOINED",
            AT));
  }

  private static BranchDeliveryChanged failed() {
    return BranchDeliveryChangedAnnouncer.event(
        new BranchDeliveryStep(
            "p-1",
            "r-1",
            "qits-projects-service",
            "ticket/qits-1615",
            "rr-369",
            "qits-projects-service-rr-369",
            "def456",
            null,
            "FAILED",
            null,
            null,
            "CI",
            "run-7",
            null,
            "JOINED",
            AT));
  }

  @Test
  public void theSignatureIsBranchDeliveryChangedAndOccurredAtIsChangedAt() {
    assertEquals("BranchDeliveryChanged", moved().signature());
    assertEquals("BranchDeliveryChanged", EventEnvelope.of(moved()).name());
    assertEquals(AT, EventEnvelope.of(moved()).occurredAt());
  }

  @Test
  public void aMoveCarriesBothRequestsAndNoFailure() throws Exception {
    JsonNode payload = payload(moved());

    assertEquals(
        List.of(
            "branch",
            "changedAt",
            "everywhere",
            "foldSha",
            "previousQualifiedId",
            "previousRequestId",
            "projectId",
            "qualifiedId",
            "repoId",
            "repository",
            "requestId",
            "step"),
        sortedFieldNames(payload),
        "foldAttempt, failure, runId and version are null on a MOVED, so they are absent keys");
    assertEquals("ticket/qits-1615", payload.get("branch").asText());
    assertEquals("qits-projects-service", payload.get("repository").asText());
    assertEquals("MOVED", payload.get("step").asText());
    assertEquals("qits-projects-service-rr-369", payload.get("qualifiedId").asText());
    assertEquals("qits-projects-service-rr-368", payload.get("previousQualifiedId").asText());
    assertEquals("JOINED", payload.get("everywhere").asText());
    assertEquals("2026-10-28T12:37:14Z", payload.get("changedAt").asText());
    assertFalse(payload.has("eventId"), "identity travels in the envelope");
  }

  @Test
  public void aFailureNamesItsKindAndItsRun() throws Exception {
    JsonNode payload = payload(failed());

    assertEquals("FAILED", payload.get("step").asText());
    assertEquals("CI", payload.get("failure").asText());
    assertEquals("run-7", payload.get("runId").asText());
    assertFalse(payload.has("previousRequestId"));
    assertFalse(payload.has("version"));
    assertFalse(payload.has("foldAttempt"), "a CI failure names its fold by the sha alone");
  }

  /**
   * qits-1725: a conflict names its attempt, because the foldSha a conflict leaves is the last fold
   * that landed and cannot tell two conflicts apart.
   */
  @Test
  public void aConflictNamesItsFoldAttempt() throws Exception {
    JsonNode payload =
        payload(
            BranchDeliveryChangedAnnouncer.event(
                new BranchDeliveryStep(
                    "p-1",
                    "r-1",
                    "qits-projects-service",
                    "ticket/qits-1615",
                    "rr-369",
                    "qits-projects-service-rr-369",
                    "def456",
                    "0123456789abcdef",
                    "CONFLICTED",
                    null,
                    null,
                    null,
                    null,
                    null,
                    "JOINED",
                    AT)));

    assertEquals("CONFLICTED", payload.get("step").asText());
    assertEquals("0123456789abcdef", payload.get("foldAttempt").asText());
    assertEquals("def456", payload.get("foldSha").asText());
    assertFalse(payload.has("failure"));
  }

  /** qits-1725: a source missing on the git host is FAILED with failure FOLD and no run. */
  @Test
  public void aMissingSourceIsAFoldFailureNamingTheRef() throws Exception {
    JsonNode payload =
        payload(
            BranchDeliveryChangedAnnouncer.event(
                new BranchDeliveryStep(
                    "p-1",
                    "r-1",
                    "qits-projects-service",
                    "ticket/qits-1615",
                    "rr-369",
                    "qits-projects-service-rr-369",
                    null,
                    "missing:refs/heads/epic/qits-1681",
                    "FAILED",
                    null,
                    null,
                    "FOLD",
                    null,
                    null,
                    "JOINED",
                    AT)));

    assertEquals("FAILED", payload.get("step").asText());
    assertEquals("FOLD", payload.get("failure").asText());
    assertEquals("missing:refs/heads/epic/qits-1681", payload.get("foldAttempt").asText());
    assertFalse(payload.has("runId"));
    assertFalse(payload.has("foldSha"), "nothing was ever folded");
  }

  /** A release carries its tag; a request with no fold yet carries no foldSha. */
  @Test
  public void aReleaseCarriesItsVersion() throws Exception {
    JsonNode payload =
        payload(
            BranchDeliveryChangedAnnouncer.event(
                new BranchDeliveryStep(
                    "p-1",
                    "r-1",
                    "qits-projects-service",
                    "ticket/qits-1615",
                    "rr-369",
                    "qits-projects-service-rr-369",
                    null,
                    null,
                    "RELEASED",
                    null,
                    null,
                    null,
                    null,
                    "2026.1028.123714",
                    "RELEASED",
                    AT)));

    assertEquals("2026.1028.123714", payload.get("version").asText());
    assertFalse(payload.has("foldSha"));
    assertEquals("RELEASED", payload.get("everywhere").asText());
  }

  @Test
  public void theRecordComponentsAreThePublishedOnes() {
    List<String> components =
        Arrays.stream(BranchDeliveryChanged.class.getRecordComponents())
            .map(RecordComponent::getName)
            .toList();
    assertEquals(
        List.of(
            "eventId",
            "projectId",
            "repoId",
            "repository",
            "branch",
            "requestId",
            "qualifiedId",
            "foldSha",
            "foldAttempt",
            "step",
            "previousRequestId",
            "previousQualifiedId",
            "failure",
            "runId",
            "version",
            "everywhere",
            "changedAt"),
        components);
    for (String envelopeWord : List.of("signature", "name", "occurredAt")) {
      assertFalse(components.contains(envelopeWord), envelopeWord);
    }
  }

  private static JsonNode payload(BranchDeliveryChanged event) throws Exception {
    return MAPPER.readTree(EventEnvelope.of(event).payload());
  }

  private static List<String> sortedFieldNames(JsonNode node) {
    List<String> names = new ArrayList<>();
    node.fieldNames().forEachRemaining(names::add);
    return names.stream().sorted().toList();
  }
}
