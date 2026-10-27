package eu.wohlben.qits.projects.bus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.eventstream.control.EventEnvelope;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The wire contract of {@link EntityCommented} (qits-1660), and how {@link EntityCommentAnnouncer}
 * reads mentions out of a body. A consumer decodes the payload into a record of its own, so a
 * renamed field here is a change over there.
 */
public class EntityCommentedContractTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final Instant AT = Instant.parse("2026-10-27T07:00:00Z");

  private static EntityCommented event() {
    return new EntityCommented(
        UUID.fromString("11111111-2222-3333-4444-555555555555"),
        "c-1",
        "e-1",
        "p-1",
        "TICKET",
        770L,
        "Desk triggers",
        "agent-uuid",
        "qits-12",
        "tok-agent",
        List.of("desk"),
        AT);
  }

  @Test
  public void theSignatureIsEntityCommented() {
    assertEquals("EntityCommented", EventEnvelope.of(event()).name());
    assertEquals(AT, EventEnvelope.of(event()).occurredAt(), "occurredAt is commentedAt");
  }

  @Test
  public void thePayloadCarriesTheseFieldsAndNoBody() throws Exception {
    JsonNode payload = MAPPER.readTree(EventEnvelope.of(event()).payload());
    for (String field :
        List.of(
            "commentId",
            "entityId",
            "projectId",
            "archetype",
            "number",
            "title",
            "author",
            "authorAgentOf",
            "changedBy",
            "mentions",
            "commentedAt")) {
      assertFalse(payload.path(field).isMissingNode(), field + " is on the wire");
    }
    assertEquals("desk", payload.get("mentions").get(0).asText());
    assertEquals(770L, payload.get("number").asLong());
    assertFalse(payload.has("body"), "the text stays on the thread");
  }

  @Test
  public void mentionsAreEachNameOnceInLowerCase() {
    assertEquals(
        List.of("desk", "bob"),
        EntityCommentAnnouncer.mentions("@desk: done. @Bob, see @DESK's note."));
    assertEquals(List.of("desk"), EntityCommentAnnouncer.mentions("Over to you, @desk."));
    assertEquals(List.of("desk-admin"), EntityCommentAnnouncer.mentions("@desk-admin please"));
    assertEquals(List.of(), EntityCommentAnnouncer.mentions("mail me at me@desk.example"));
    assertEquals(List.of(), EntityCommentAnnouncer.mentions(null));
  }
}
