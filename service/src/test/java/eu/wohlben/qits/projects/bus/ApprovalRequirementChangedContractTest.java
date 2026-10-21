package eu.wohlben.qits.projects.bus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.eventstream.control.EventEnvelope;
import eu.wohlben.qits.projects.control.ApprovalRequirementAnnouncer;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * <b>{@code ApprovalRequirementChanged}'s wire</b> (epic qits-1038): the signature, the occurredAt,
 * and the field list a reviewer dispatcher will decode into a record of its own. No vocabulary jar
 * carries the type, so this test is the contract.
 */
public class ApprovalRequirementChangedContractTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final Instant AT = Instant.parse("2026-10-21T10:15:30Z");

  private static ApprovalRequirementChanged opened() {
    return ApprovalRequirementChangedAnnouncer.event(
        new ApprovalRequirementAnnouncer.RequirementChange(
            "rr-1",
            "qits-landing-app-rr-9",
            "p-1",
            "r-1",
            "qits-landing-app",
            "abc123",
            "artifact-declarations",
            "declares what the release publishes in .config/qits/release.yml",
            List.of("agent", "person"),
            false,
            List.of("agent-client-1"),
            "OPEN",
            AT));
  }

  @Test
  public void theSignatureIsApprovalRequirementChangedAndOccurredAtIsChangedAt() {
    assertEquals("ApprovalRequirementChanged", opened().signature());
    assertEquals("ApprovalRequirementChanged", EventEnvelope.of(opened()).name());
    assertEquals(AT, EventEnvelope.of(opened()).occurredAt());
  }

  @Test
  public void anOpenRequirementCarriesEveryField() throws Exception {
    JsonNode payload = MAPPER.readTree(EventEnvelope.of(opened()).payload());

    assertEquals(
        List.of(
            "admits",
            "authors",
            "changedAt",
            "excludesAuthors",
            "mergedSha",
            "projectId",
            "reason",
            "releaseRequestId",
            "releaseRequestQualifiedId",
            "repoId",
            "repoName",
            "requirementId",
            "state"),
        sortedFieldNames(payload));
    assertEquals("rr-1", payload.get("releaseRequestId").asText());
    assertEquals("qits-landing-app-rr-9", payload.get("releaseRequestQualifiedId").asText());
    assertEquals("artifact-declarations", payload.get("requirementId").asText());
    assertEquals("agent", payload.get("admits").get(0).asText());
    assertEquals("person", payload.get("admits").get(1).asText());
    assertFalse(payload.get("excludesAuthors").asBoolean());
    assertEquals("agent-client-1", payload.get("authors").get(0).asText());
    assertEquals("OPEN", payload.get("state").asText());
    assertEquals("2026-10-21T10:15:30Z", payload.get("changedAt").asText());
    assertFalse(payload.has("eventId"), "identity travels in the envelope");
  }

  @Test
  public void theRecordComponentsAreThePublishedOnes() {
    List<String> components =
        Arrays.stream(ApprovalRequirementChanged.class.getRecordComponents())
            .map(RecordComponent::getName)
            .toList();
    assertEquals(
        List.of(
            "eventId",
            "releaseRequestId",
            "releaseRequestQualifiedId",
            "projectId",
            "repoId",
            "repoName",
            "mergedSha",
            "requirementId",
            "reason",
            "admits",
            "excludesAuthors",
            "authors",
            "state",
            "changedAt"),
        components);
    for (String envelopeWord : List.of("signature", "name", "occurredAt")) {
      assertFalse(components.contains(envelopeWord), envelopeWord);
    }
  }

  private static List<String> sortedFieldNames(JsonNode node) {
    List<String> names = new ArrayList<>();
    node.fieldNames().forEachRemaining(names::add);
    return names.stream().sorted().toList();
  }
}
