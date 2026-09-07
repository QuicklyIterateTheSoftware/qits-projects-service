package eu.wohlben.qits.projects.bus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.eventstream.control.CanonicalJson;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The <b>wire contract</b> of {@link ProjectCreated} and {@link ProjectDeleted}, pinned at the
 * source.
 *
 * <p>Neither record is published as a jar — this service's standing answer, for the reason each
 * record's javadoc gives — so a consumer decodes them with {@code CanonicalJson.payloadTo} into a
 * local record of its own, transcribed by hand from these field names. That transcription and this
 * test are the two ends of the contract and nothing in either build can see the other: the platform
 * edge, which derives a project's TLS SANs from {@code slug}, is written against the literals
 * asserted below. <b>Renaming a field here is a change over there, in the same campaign.</b>
 *
 * <p>A plain JUnit test and not a {@code @QuarkusTest}: {@code CanonicalJson} builds its own {@code
 * ObjectMapper} by hand and deliberately does not go through CDI, so there is no application to boot
 * to ask it what a payload looks like.
 */
public class ProjectLifecycleContractTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** The wire NAME is the simple class name, and a consumer subscribes by it. */
  @Test
  public void theWireNamesAreProjectCreatedAndProjectDeleted() {
    assertEquals("ProjectCreated", new ProjectCreated("p", "s", "n", Instant.EPOCH).signature());
    assertEquals("ProjectDeleted", new ProjectDeleted("p", "s", Instant.EPOCH).signature());
  }

  /**
   * The creation payload: four keys, exactly these spellings, and {@code eventId} not among them.
   * Identity travels in the envelope — the canonical mix-in keeps every {@link
   * eu.wohlben.qits.eventstream.QitsEvent} method out of the payload — so an {@code eventId} here
   * would be a contract violation that breaks nothing visible, which is the worse failure mode.
   */
  @Test
  public void theCreatedPayloadIsProjectIdSlugNameAndCreatedAt() throws Exception {
    var payload =
        MAPPER.readTree(
            CanonicalJson.payload(
                new ProjectCreated(
                    UUID.fromString("11111111-2222-3333-4444-555555555555"),
                    "p-1",
                    "qits",
                    "QITS Platform",
                    Instant.parse("2026-09-07T10:15:30Z"))));

    assertEquals("p-1", payload.get("projectId").asText());
    assertEquals(
        "qits", payload.get("slug").asText(), "what the edge derives *.<slug>.<domain> from");
    assertEquals("QITS Platform", payload.get("projectName").asText());
    assertEquals("2026-09-07T10:15:30Z", payload.get("createdAt").asText());
    assertFalse(payload.has("eventId"), "identity travels in the envelope, never in the payload");
    assertFalse(payload.has("occurredAt"), "occurredAt is the envelope's; createdAt is the fact");
    assertEquals(
        List.of("createdAt", "projectId", "projectName", "slug"),
        sortedFieldNames(payload),
        "four keys, and a fifth is a contract change the edge has to be told about");
  }

  /**
   * <b>The display name may never be called {@code name}, and this is the guard.</b> {@code
   * QitsEvent.name()} is a default method that {@code CanonicalJson}'s mix-in {@code @JsonIgnore}s,
   * and Jackson matches a mix-in to a record's accessor by NAME — so a component called {@code name}
   * would have the getter {@code name()} and be dropped from every payload with nothing failing
   * anywhere. That is exactly what the first draft of {@link ProjectCreated} did. The rule
   * generalises: {@code signature}, {@code name}, {@code eventId} and {@code occurredAt} are the
   * envelope's words, and no payload field on this platform may spell one of them.
   */
  @Test
  public void noPayloadFieldSpellsOneOfTheEnvelopesWords() throws Exception {
    var created =
        MAPPER.readTree(
            CanonicalJson.payload(
                new ProjectCreated("p-1", "qits", "QITS Platform", Instant.EPOCH)));

    assertFalse(
        created.has("name"),
        "a field called 'name' would be ignored by the mix-in — the display name is projectName");
    // eventId is the one component that MAY spell an envelope word: it is the identity, and being
    // kept out of the payload is what it is there for.
    for (Class<?> event : List.of(ProjectCreated.class, ProjectDeleted.class)) {
      for (String envelopeWord : List.of("signature", "name", "occurredAt")) {
        assertFalse(
            componentNames(event).contains(envelopeWord),
            event.getSimpleName()
                + " has a component called '"
                + envelopeWord
                + "', which the canonical mix-in drops from the payload without saying so");
      }
    }
  }

  /**
   * The deletion payload: three keys, and {@code slug} is one of them on purpose — the row that held
   * it is gone by the time this is published, so an event carrying only the id would leave a
   * consumer holding names it cannot retire.
   */
  @Test
  public void theDeletedPayloadIsProjectIdSlugAndDeletedAt() throws Exception {
    var payload =
        MAPPER.readTree(
            CanonicalJson.payload(
                new ProjectDeleted(
                    UUID.fromString("66666666-7777-8888-9999-000000000000"),
                    "p-1",
                    "qits",
                    Instant.parse("2026-09-07T10:15:30Z"))));

    assertEquals("p-1", payload.get("projectId").asText());
    assertEquals("qits", payload.get("slug").asText());
    assertEquals("2026-09-07T10:15:30Z", payload.get("deletedAt").asText());
    assertFalse(payload.has("eventId"));
    assertEquals(
        List.of("deletedAt", "projectId", "slug"),
        sortedFieldNames(payload),
        "three keys — a deletion restates no display name, because nothing is derived from one");
  }

  /**
   * The components, in their declared order, so a reordering or an insertion is a change somebody
   * made on purpose. The pin is on the record and not only on one serialized instance: a nullable
   * field is an absent KEY under {@code NON_NULL} and would slip past a payload assertion.
   */
  @Test
  public void theRecordComponentsAreThePublishedOnes() {
    assertEquals(
        List.of("eventId", "projectId", "slug", "projectName", "createdAt"),
        componentNames(ProjectCreated.class));
    assertEquals(
        List.of("eventId", "projectId", "slug", "deletedAt"), componentNames(ProjectDeleted.class));
  }

  /** {@code occurredAt} is the fact's own timestamp, not the moment the publish was attempted. */
  @Test
  public void occurredAtIsTheMomentTheChangeCommitted() {
    Instant when = Instant.parse("2026-09-07T10:15:30Z");
    assertEquals(when, new ProjectCreated("p-1", "qits", "QITS", when).occurredAt());
    assertEquals(when, new ProjectDeleted("p-1", "qits", when).occurredAt());
  }

  /** An absent {@code eventId} is minted, which is what the idempotent PUT rests on. */
  @Test
  public void anAbsentEventIdIsMinted() {
    assertTrue(new ProjectCreated("p-1", "qits", "QITS", Instant.EPOCH).eventId() != null);
    assertTrue(new ProjectDeleted("p-1", "qits", Instant.EPOCH).eventId() != null);
  }

  private static List<String> sortedFieldNames(com.fasterxml.jackson.databind.JsonNode node) {
    List<String> names = new java.util.ArrayList<>();
    node.fieldNames().forEachRemaining(names::add);
    return names.stream().sorted().toList();
  }

  private static List<String> componentNames(Class<?> record) {
    return Arrays.stream(record.getRecordComponents()).map(RecordComponent::getName).toList();
  }
}
