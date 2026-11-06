package eu.wohlben.qits.projects.bus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.eventstream.control.CanonicalJson;
import eu.wohlben.qits.eventstream.control.EventEnvelope;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The <b>wire contract</b> of {@link WorkNeedMet} (qits-1903), pinned at the source — {@link
 * MilestoneEventsContractTest}'s shape, for the same reason: no jar carries the record, so a
 * consumer (the delivery desk's {@code qits wait --event WorkNeedMet --when 'qualifiedId=…
 * allMet=true'}) selects it by these field names. <b>Renaming a field here is a change over
 * there.</b>
 *
 * <p>Serialised through {@link EventEnvelope#of}, the path {@code QitsEventBus.publish} takes. A
 * plain JUnit test: {@code CanonicalJson} builds its own {@code ObjectMapper} outside CDI.
 */
public class WorkNeedEventsContractTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final Instant AT = Instant.parse("2026-11-06T10:15:30Z");

  private static WorkNeedMet met() {
    return new WorkNeedMet(
        UUID.fromString("11111111-2222-3333-4444-777777777777"),
        "p-1",
        "e-1898",
        "qits-1898",
        "e-1897",
        "qits-1897",
        "VERIFIED",
        "VERIFIED",
        true,
        AT);
  }

  @Test
  public void theSignatureIsTheSimpleClassNameAndOccurredAtIsTheFact() {
    assertEquals("WorkNeedMet", met().signature());
    assertEquals("WorkNeedMet", EventEnvelope.of(met()).name());
    assertEquals(AT, EventEnvelope.of(met()).occurredAt(), "occurredAt is metAt");
  }

  @Test
  public void aWorkNeedMetCarriesBothItemsBothStatusesAndWhetherAllAreMet() throws Exception {
    JsonNode payload = MAPPER.readTree(EventEnvelope.of(met()).payload());
    assertEquals(
        List.of(
            "allMet",
            "entityId",
            "metAt",
            "neededEntityId",
            "neededQualifiedId",
            "neededStatus",
            "projectId",
            "qualifiedId",
            "status"),
        sortedFieldNames(payload));
    assertEquals("p-1", payload.get("projectId").asText());
    assertEquals("e-1898", payload.get("entityId").asText());
    assertEquals("qits-1898", payload.get("qualifiedId").asText());
    assertEquals("e-1897", payload.get("neededEntityId").asText());
    assertEquals("qits-1897", payload.get("neededQualifiedId").asText());
    assertEquals("VERIFIED", payload.get("status").asText());
    assertEquals("VERIFIED", payload.get("neededStatus").asText());
    assertTrue(payload.get("allMet").isBoolean(), "allMet is a JSON boolean, not a string");
    assertTrue(payload.get("allMet").booleanValue());
    assertEquals("2026-11-06T10:15:30Z", payload.get("metAt").asText());
    assertFalse(payload.has("eventId"), "identity travels in the envelope, never in the payload");
  }

  @Test
  public void aWorkNeedMetReadsBackAsItself() {
    WorkNeedMet read =
        CanonicalJson.payloadTo(EventEnvelope.of(met()).payload(), WorkNeedMet.class);
    assertEquals("qits-1898", read.qualifiedId());
    assertEquals("qits-1897", read.neededQualifiedId());
    assertTrue(read.allMet());
    assertEquals(AT, read.metAt());
  }

  @Test
  public void theRecordComponentsAreThePublishedOnes() {
    List<String> components =
        Arrays.stream(WorkNeedMet.class.getRecordComponents())
            .map(RecordComponent::getName)
            .toList();
    assertEquals(
        List.of(
            "eventId",
            "projectId",
            "entityId",
            "qualifiedId",
            "neededEntityId",
            "neededQualifiedId",
            "status",
            "neededStatus",
            "allMet",
            "metAt"),
        components);
    for (String envelopeWord : List.of("signature", "name", "occurredAt")) {
      assertFalse(
          components.contains(envelopeWord),
          "WorkNeedMet has a component the canonical mix-in drops: " + envelopeWord);
    }
  }

  private static List<String> sortedFieldNames(JsonNode node) {
    List<String> names = new ArrayList<>();
    node.fieldNames().forEachRemaining(names::add);
    return names.stream().sorted().toList();
  }
}
