package eu.wohlben.qits.projects.bus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.eventstream.control.EventEnvelope;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The <b>wire contract</b> of {@link EntityTransitioned}, pinned at the source — {@link
 * ProjectLifecycleContractTest}'s shape, for the same reason: no jar carries the record, so a
 * consumer decodes it with {@code CanonicalJson.payloadTo} into a local record transcribed by hand
 * from the field names asserted below. <b>Renaming a field here is a change over there.</b>
 *
 * <p>Serialised through {@link EventEnvelope#of}, the path {@code QitsEventBus.publish} takes, so
 * the payload asserted is the one that would actually be PUT — the {@code QitsEvent} mix-in applied.
 *
 * <p>A plain JUnit test: {@code CanonicalJson} builds its own {@code ObjectMapper} outside CDI.
 */
public class EntityTransitionedContractTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final Instant AT = Instant.parse("2026-09-27T10:15:30Z");

  private static EntityTransitioned event() {
    return new EntityTransitioned(
        UUID.fromString("11111111-2222-3333-4444-555555555555"),
        List.of(
            new EntityTransitioned.Entity(
                "e-1",
                "p-1",
                "TICKET",
                "e-0",
                2,
                "announce-me",
                "Announce me",
                "VERIFIED",
                "IMPLEMENTED",
                "verifier",
                410L)),
        AT);
  }

  /** The wire NAME is the simple class name, and a consumer subscribes by it. */
  @Test
  public void theSignatureIsEntityTransitioned() {
    assertEquals("EntityTransitioned", event().signature());
    assertEquals("EntityTransitioned", EventEnvelope.of(event()).name());
    assertEquals(AT, EventEnvelope.of(event()).occurredAt(), "occurredAt is transitionedAt");
  }

  /** The payload: two keys, and none of the envelope's words among them. */
  @Test
  public void thePayloadIsEntitiesAndTransitionedAt() throws Exception {
    JsonNode payload = payload();

    assertEquals(List.of("entities", "transitionedAt"), sortedFieldNames(payload));
    assertEquals("2026-09-27T10:15:30Z", payload.get("transitionedAt").asText());
    assertFalse(payload.has("eventId"), "identity travels in the envelope, never in the payload");
    assertEquals(1, payload.get("entities").size());
  }

  /**
   * One entity: eleven keys, exactly these spellings. Every one is filled, because {@code
   * CanonicalJson} is {@code NON_NULL} and an absent value would be an absent KEY — which is also
   * why the record's components are pinned separately below.
   */
  @Test
  public void anEntityCarriesItsPostStateTheStatusItLeftTheActorAndItsNumber() throws Exception {
    JsonNode entity = payload().get("entities").get(0);

    assertEquals(
        List.of(
            "archetype",
            "changedBy",
            "entityId",
            "number",
            "parentId",
            "position",
            "projectId",
            "slug",
            "status",
            "statusBefore",
            "title"),
        sortedFieldNames(entity));
    assertEquals("e-1", entity.get("entityId").asText());
    assertEquals("p-1", entity.get("projectId").asText());
    assertEquals("TICKET", entity.get("archetype").asText());
    assertEquals("e-0", entity.get("parentId").asText());
    assertEquals(2, entity.get("position").asInt());
    assertEquals("announce-me", entity.get("slug").asText());
    assertEquals("Announce me", entity.get("title").asText());
    assertEquals("VERIFIED", entity.get("status").asText());
    assertEquals("IMPLEMENTED", entity.get("statusBefore").asText());
    assertEquals("verifier", entity.get("changedBy").asText());
    assertEquals(410L, entity.get("number").asLong());
  }

  /**
   * The components in their declared order, so an insertion is a change somebody made on purpose —
   * and none of them spells {@code name}, {@code signature} or {@code occurredAt}, which the
   * canonical mix-in would drop from the payload without saying so.
   */
  @Test
  public void theRecordComponentsAreThePublishedOnes() {
    assertEquals(
        List.of("eventId", "entities", "transitionedAt"), componentNames(EntityTransitioned.class));
    assertEquals(
        List.of(
            "entityId",
            "projectId",
            "archetype",
            "parentId",
            "position",
            "slug",
            "title",
            "status",
            "statusBefore",
            "changedBy",
            "number"),
        componentNames(EntityTransitioned.Entity.class));
    for (Class<?> record : List.of(EntityTransitioned.class, EntityTransitioned.Entity.class)) {
      for (String envelopeWord : List.of("signature", "name", "occurredAt")) {
        assertFalse(
            componentNames(record).contains(envelopeWord),
            record.getSimpleName() + " has a component the canonical mix-in drops: " + envelopeWord);
      }
    }
  }

  private static JsonNode payload() throws Exception {
    return MAPPER.readTree(EventEnvelope.of(event()).payload());
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
