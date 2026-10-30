package eu.wohlben.qits.projects.bus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.entities.control.IdeaChange;
import eu.wohlben.qits.eventstream.QitsEvent;
import eu.wohlben.qits.eventstream.control.EventEnvelope;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The four idea events on the wire (qits-1387): one wire name per change kind, the same fields on
 * each, {@code changedAt} as the envelope's {@code occurredAt}.
 */
public class IdeaEventsContractTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final Instant AT = Instant.parse("2026-10-30T12:00:00Z");

  private static IdeaChange change(IdeaChange.Kind kind) {
    return new IdeaChange(
        kind, "idea-1", "p-1", "Faster builds", "QUEUED", "PLANNED", false, null, "alice", AT);
  }

  @Test
  public void eachKindIsItsOwnEvent() {
    assertInstanceOf(IdeaCreated.class, IdeaEventAnnouncer.event(change(IdeaChange.Kind.CREATED)));
    assertInstanceOf(IdeaUpdated.class, IdeaEventAnnouncer.event(change(IdeaChange.Kind.UPDATED)));
    assertInstanceOf(
        IdeaArchived.class, IdeaEventAnnouncer.event(change(IdeaChange.Kind.ARCHIVED)));
    assertInstanceOf(
        IdeaUnarchived.class, IdeaEventAnnouncer.event(change(IdeaChange.Kind.UNARCHIVED)));
  }

  @Test
  public void theWireNameIsTheSimpleClassNameAndOccurredAtIsChangedAt() {
    QitsEvent event = IdeaEventAnnouncer.event(change(IdeaChange.Kind.UPDATED));
    assertEquals("IdeaUpdated", EventEnvelope.of(event).name());
    assertEquals(AT, EventEnvelope.of(event).occurredAt());
  }

  @Test
  public void thePayloadCarriesTheIdeaAndItsProject() throws Exception {
    JsonNode payload =
        MAPPER.readTree(
            EventEnvelope.of(IdeaEventAnnouncer.event(change(IdeaChange.Kind.UPDATED))).payload());
    for (String field :
        List.of("ideaId", "projectId", "title", "status", "statusBefore", "archived", "changedBy",
            "changedAt")) {
      assertFalse(payload.path(field).isMissingNode(), field + " is on the wire");
    }
    assertEquals("idea-1", payload.get("ideaId").asText());
    assertEquals("p-1", payload.get("projectId").asText());
    assertEquals("PLANNED", payload.get("statusBefore").asText());
  }
}
