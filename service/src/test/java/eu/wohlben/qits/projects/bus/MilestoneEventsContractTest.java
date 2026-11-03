package eu.wohlben.qits.projects.bus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.eventstream.QitsEvent;
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
 * The <b>wire contract</b> of {@link MilestoneMet} and {@link MilestoneUnmet} (epic qits-615),
 * pinned at the source — {@link EntityTransitionedContractTest}'s shape, for the same reason: no jar
 * carries the records, so a consumer (the landing app, the campaign executor's own listener)
 * decodes them by these field names. <b>Renaming a field here is a change over there.</b>
 *
 * <p>Serialised through {@link EventEnvelope#of}, the path {@code QitsEventBus.publish} takes. A
 * plain JUnit test: {@code CanonicalJson} builds its own {@code ObjectMapper} outside CDI.
 */
public class MilestoneEventsContractTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final Instant AT = Instant.parse("2026-11-03T10:15:30Z");

  private static MilestoneMet met() {
    return new MilestoneMet(
        UUID.fromString("11111111-2222-3333-4444-555555555555"),
        "ms-1",
        "p-1",
        "bootstrap test node",
        "g-1",
        AT);
  }

  private static MilestoneUnmet unmet() {
    return new MilestoneUnmet(
        UUID.fromString("11111111-2222-3333-4444-666666666666"),
        "ms-1",
        "p-1",
        "bootstrap test node",
        AT);
  }

  @Test
  public void theSignaturesAreTheSimpleClassNamesAndOccurredAtIsTheFact() {
    assertEquals("MilestoneMet", met().signature());
    assertEquals("MilestoneMet", EventEnvelope.of(met()).name());
    assertEquals(AT, EventEnvelope.of(met()).occurredAt(), "occurredAt is satisfiedAt");
    assertEquals("MilestoneUnmet", unmet().signature());
    assertEquals("MilestoneUnmet", EventEnvelope.of(unmet()).name());
    assertEquals(AT, EventEnvelope.of(unmet()).occurredAt(), "occurredAt is at");
  }

  @Test
  public void aMilestoneMetCarriesTheMilestoneItsProjectNameHoldingGroupAndDate() throws Exception {
    JsonNode payload = payload(met());
    assertEquals(
        List.of("displayName", "groupId", "milestoneId", "projectId", "satisfiedAt"),
        sortedFieldNames(payload));
    assertEquals("ms-1", payload.get("milestoneId").asText());
    assertEquals("p-1", payload.get("projectId").asText());
    assertEquals("bootstrap test node", payload.get("displayName").asText());
    assertEquals("g-1", payload.get("groupId").asText());
    assertEquals("2026-11-03T10:15:30Z", payload.get("satisfiedAt").asText());
    assertFalse(payload.has("eventId"), "identity travels in the envelope, never in the payload");
  }

  @Test
  public void aMilestoneUnmetCarriesTheMilestoneItsProjectNameAndWhen() throws Exception {
    JsonNode payload = payload(unmet());
    assertEquals(
        List.of("at", "displayName", "milestoneId", "projectId"), sortedFieldNames(payload));
    assertEquals("ms-1", payload.get("milestoneId").asText());
    assertEquals("p-1", payload.get("projectId").asText());
    assertEquals("bootstrap test node", payload.get("displayName").asText());
    assertEquals("2026-11-03T10:15:30Z", payload.get("at").asText());
    assertFalse(payload.has("eventId"));
  }

  /** What the executor's own listener binds is what the announcer wrote. */
  @Test
  public void aMilestoneMetReadsBackAsItself() {
    MilestoneMet read = CanonicalJson.payloadTo(EventEnvelope.of(met()).payload(), MilestoneMet.class);
    assertEquals("ms-1", read.milestoneId());
    assertEquals("g-1", read.groupId());
    assertEquals(AT, read.satisfiedAt());
  }

  @Test
  public void theRecordComponentsAreThePublishedOnes() {
    assertEquals(
        List.of("eventId", "milestoneId", "projectId", "displayName", "groupId", "satisfiedAt"),
        componentNames(MilestoneMet.class));
    assertEquals(
        List.of("eventId", "milestoneId", "projectId", "displayName", "at"),
        componentNames(MilestoneUnmet.class));
    for (Class<?> record : List.of(MilestoneMet.class, MilestoneUnmet.class)) {
      for (String envelopeWord : List.of("signature", "name", "occurredAt")) {
        assertFalse(
            componentNames(record).contains(envelopeWord),
            record.getSimpleName() + " has a component the canonical mix-in drops: " + envelopeWord);
      }
    }
  }

  private static JsonNode payload(QitsEvent event) throws Exception {
    return MAPPER.readTree(EventEnvelope.of(event).payload());
  }

  private static List<String> sortedFieldNames(JsonNode node) {
    List<String> names = new ArrayList<>();
    node.fieldNames().forEachRemaining(names::add);
    return names.stream().sorted().toList();
  }

  private static List<String> componentNames(Class<?> record) {
    return Arrays.stream(record.getRecordComponents()).map(RecordComponent::getName).toList();
  }
}
