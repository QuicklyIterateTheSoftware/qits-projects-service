package eu.wohlben.qits.projects.bus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.eventstream.control.EventEnvelope;
import eu.wohlben.qits.projects.control.ReleaseRequestStateAnnouncer;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The wire contract of {@link ReleaseRequestStateChanged} (qits-1153), in {@link
 * EntityTransitionedContractTest}'s shape: a consumer (qits-workspaces' wait matcher, the CLI's
 * presets) builds its selection against the keys asserted here. <b>Renaming one is a change
 * there.</b> Serialised through {@link EventEnvelope#of}, the path {@code QitsEventBus.publish}
 * takes, from the adapter's own mapping.
 */
public class ReleaseRequestStateChangedContractTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final Instant AT = Instant.parse("2026-10-10T10:15:30Z");

  private static ReleaseRequestStateChanged released() {
    return ReleaseRequestStateChangedAnnouncer.event(
        new ReleaseRequestStateAnnouncer.StateChange(
            "rr-1",
            "p-1",
            "r-1",
            "qits-ci-service",
            "RELEASED",
            "READY",
            AT,
            "abc123",
            "2026.1010.101530",
            null,
            List.of("main", "ticket/qits-1153")));
  }

  @Test
  public void theSignatureIsReleaseRequestStateChangedAndOccurredAtIsChangedAt() {
    assertEquals("ReleaseRequestStateChanged", released().signature());
    assertEquals("ReleaseRequestStateChanged", EventEnvelope.of(released()).name());
    assertEquals(AT, EventEnvelope.of(released()).occurredAt());
  }

  @Test
  public void aReleaseCarriesItsStatesItsVersionAndItsBranches() throws Exception {
    JsonNode payload = payload(released());

    assertEquals(
        List.of(
            "branches",
            "changedAt",
            "mergedSha",
            "previousState",
            "projectId",
            "releaseRequestId",
            "repoId",
            "repoName",
            "state",
            "version"),
        sortedFieldNames(payload),
        "detail is null here, so it is an absent key");
    assertEquals("rr-1", payload.get("releaseRequestId").asText());
    assertEquals("RELEASED", payload.get("state").asText());
    assertEquals("READY", payload.get("previousState").asText());
    assertEquals("2026.1010.101530", payload.get("version").asText());
    assertEquals("2026-10-10T10:15:30Z", payload.get("changedAt").asText());
    assertEquals("ticket/qits-1153", payload.get("branches").get(1).asText());
    assertFalse(payload.has("eventId"), "identity travels in the envelope");
  }

  /** A request just created moves from nothing: no previousState key. */
  @Test
  public void aNewRequestHasNoPreviousState() throws Exception {
    JsonNode payload =
        payload(
            new ReleaseRequestStateChanged(
                "rr-2", null, "r-1", null, "PENDING", null, AT, null, null, null, List.of("main")));

    assertEquals(
        List.of("branches", "changedAt", "releaseRequestId", "repoId", "state"),
        sortedFieldNames(payload));
  }

  @Test
  public void theRecordComponentsAreThePublishedOnes() {
    List<String> components =
        Arrays.stream(ReleaseRequestStateChanged.class.getRecordComponents())
            .map(RecordComponent::getName)
            .toList();
    assertEquals(
        List.of(
            "eventId",
            "releaseRequestId",
            "projectId",
            "repoId",
            "repoName",
            "state",
            "previousState",
            "changedAt",
            "mergedSha",
            "version",
            "detail",
            "branches"),
        components);
    for (String envelopeWord : List.of("signature", "name", "occurredAt")) {
      assertFalse(components.contains(envelopeWord), envelopeWord);
    }
  }

  private static JsonNode payload(ReleaseRequestStateChanged event) throws Exception {
    return MAPPER.readTree(EventEnvelope.of(event).payload());
  }

  private static List<String> sortedFieldNames(JsonNode node) {
    List<String> names = new ArrayList<>();
    node.fieldNames().forEachRemaining(names::add);
    return names.stream().sorted().toList();
  }
}
