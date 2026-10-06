package eu.wohlben.qits.entities.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.entities.control.EntityProperty;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import jakarta.ws.rs.core.Response;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The REST round-trip for {@code GET /projects/api/entities/archetypes} — the registry, served.
 *
 * <p>What this class is for, as distinct from {@code ArchetypeRegistryDocumentTest}: that the route
 * exists at that path, that the document reaches a caller as JSON with the member names a client is
 * being written against, and that the shape is one object rather than an {@code entries} envelope.
 * Every rule about what is <em>in</em> the document — the two orderings, the derivation, the
 * transition's own status rule — is asserted over there, where it costs no HTTP round trip.
 */
@QuarkusTest
class EntityArchetypesApiTest {

  private static final String PATH = "/projects/api/entities/archetypes";

  private static ValidatableResponse document() {
    return given().when().get(PATH).then().statusCode(Response.Status.OK.getStatusCode());
  }

  /**
   * The archetype's entry, located by name rather than by index: the enum order is the document's
   * contract and is asserted once, below, rather than restated by every other assertion here.
   */
  private static String at(String archetype) {
    return "archetypes.find { it.archetype == '" + archetype + "' }.";
  }

  @Test
  void theRegistryIsServedAsOneObjectAndNotAnEntriesEnvelope() {
    document()
        // Three named members and no collection envelope: this answers the model itself, which is
        // one thing with named parts, rather than rows that happen to be there.
        .body("entries", nullValue())
        .body("archetypes.size()", equalTo(5))
        .body("serverOwned.size()", equalTo(2))
        // The vocabulary travels whole, in declaration order, because a client derives a
        // violation's spelling from it rather than keeping its own table of field names.
        .body("properties[0]", equalTo("TITLE"))
        .body("properties.size()", equalTo(EntityProperty.values().length))
        .body("properties", hasItems("SLUG", "STATUS", "TICKET_TYPE", "IMPETUS", "DEPENDS_ON"));
  }

  @Test
  void everyArchetypeAppearsWithItsDepthAndItsRootness() {
    document()
        .body("archetypes.archetype", contains("EPIC", "TICKET", "FEATURE", "TASK", "CAMPAIGN"))
        // The two roots share a depth — which is what makes ticket-under-epic refusable by the
        // ordinary parent.depth < child.depth rule a client applies for itself.
        .body(at("EPIC") + "depth", equalTo(0))
        .body(at("EPIC") + "mayBeRoot", equalTo(true))
        .body(at("TICKET") + "depth", equalTo(0))
        .body(at("TICKET") + "mayBeRoot", equalTo(true))
        .body(at("FEATURE") + "depth", equalTo(1))
        .body(at("FEATURE") + "mayBeRoot", equalTo(false))
        .body(at("TASK") + "depth", equalTo(2))
        .body(at("TASK") + "mayBeRoot", equalTo(false))
        // Above the epic and a root too (qits-411): -1 moves no other depth, and rootness is the
        // declared flag, so the epic and the ticket stay roots beside it.
        .body(at("CAMPAIGN") + "depth", equalTo(-1))
        .body(at("CAMPAIGN") + "mayBeRoot", equalTo(true));
  }

  @Test
  void onlyTheCampaignGathers() {
    // Its children are campaign memberships, never structural — a client must not draw them as
    // the campaign's subtree.
    document()
        .body(at("CAMPAIGN") + "gathers", equalTo(true))
        .body(at("EPIC") + "gathers", equalTo(false))
        .body(at("TICKET") + "gathers", equalTo(false))
        .body(at("FEATURE") + "gathers", equalTo(false))
        .body(at("TASK") + "gathers", equalTo(false))
        .body(at("CAMPAIGN") + "legalStatuses.size()", equalTo(7))
        .body(at("CAMPAIGN") + "transitions.REFINED.to", contains("READY_FOR_DEV", "REPORTED", "DROPPED"))
        .body(
            at("CAMPAIGN") + "transitions.READY_FOR_DEV.to",
            contains("IMPLEMENTED", "REFINED", "DROPPED"))
        .body(
            at("CAMPAIGN") + "transitions.IMPLEMENTED.to",
            contains("VERIFIED", "READY_FOR_DEV", "DROPPED"))
        .body(at("CAMPAIGN") + "transitions.VERIFIED.to", contains("DONE", "IMPLEMENTED", "DROPPED"))
        .body(at("CAMPAIGN") + "permitted", contains("TITLE", "SLUG", "DESCRIPTION", "STATUS"));
  }

  @Test
  void aTicketAsksForItsImpetusAtINTAKEAndForItsTypeAndStatusForEver() {
    // The three axes, over the wire, on the one archetype whose create list is wider than its
    // invariant. requiredAtCreate is the four a ticket create refuses a row without — what
    // an intake form must gather — and required is the three an edit or a transition is judged
    // against, because entity.impetus is nullable and clearing one is behaviour a person has. In
    // vocabulary order, so the fields read in the order the violations would.
    document()
        .body(
            at("TICKET") + "requiredAtCreate",
            contains("TITLE", "STATUS", "TICKET_TYPE", "IMPETUS"))
        .body(at("TICKET") + "required", contains("TITLE", "STATUS", "TICKET_TYPE"))
        .body(
            at("TICKET") + "requiredOnTransition", contains("TITLE", "STATUS", "TICKET_TYPE"));
  }

  @Test
  void anEpicsTransitionWantsAStatusTheRegistryMerelyPermits() {
    // The transition's own rule, served: the registry permits an epic a status because the writer
    // mints the first one, and a transition mints nothing, so an omission would clear one.
    document()
        .body(at("EPIC") + "required", contains("TITLE"))
        .body(at("EPIC") + "requiredAtCreate", contains("TITLE"))
        .body(at("EPIC") + "requiredOnTransition", contains("TITLE", "STATUS"))
        .body(
            at("EPIC") + "permitted",
            contains(
                "TITLE", "SLUG", "DESCRIPTION", "STATUS", "SUPERSEDED_BY", "ACCEPTANCE_CRITERIA"))
        .body(
            at("EPIC") + "legalStatuses",
            contains(
                "DONE",
                "DROPPED",
                "IMPLEMENTED",
                "IMPLEMENTING",
                "READY_FOR_DEV",
                "REFINED",
                "REPORTED",
                "VERIFIED",
                "VERIFYING"))
        .body(at("TICKET") + "legalStatuses", contains(
                "DONE",
                "DROPPED",
                "IMPLEMENTED",
                "IMPLEMENTING",
                "READY_FOR_DEV",
                "REFINED",
                "REPORTED",
                "VERIFIED",
                "VERIFYING"));
  }

  /**
   * qits-763: a feature holds the one lifecycle, minted by the writer — so, as for an epic, the
   * registry permits a status and the transition (which mints nothing) requires one.
   */
  @Test
  void aFeatureHoldsTheOneLifecycleAndATransitionMustStateIt() {
    document()
        .body(
            at("FEATURE") + "legalStatuses",
            contains(
                "DONE",
                "DROPPED",
                "IMPLEMENTED",
                "IMPLEMENTING",
                "READY_FOR_DEV",
                "REFINED",
                "REPORTED",
                "VERIFIED",
                "VERIFYING"))
        .body(at("FEATURE") + "required", contains("TITLE"))
        .body(at("FEATURE") + "requiredAtCreate", contains("TITLE"))
        .body(at("FEATURE") + "requiredOnTransition", contains("TITLE", "STATUS"));
  }

  /** A legal move as the wire spells it. */
  private static Map<String, String> move(String to, String kind) {
    return Map.of("to", to, "kind", kind);
  }

  /**
   * The ticket's whole served map of legal moves, asserted exactly — keys in lifecycle order, each
   * value FORWARD, then SKIP, then BACK, then DROP/REOPEN — and DONE answering no move at all, because DONE is
   * final. Parsed with Jackson rather than read through a GPath so the key order is what arrived.
   */
  @Test
  void aTicketServesItsLegalMovesAndItsOrderedLifecycle() throws Exception {
    JsonNode document = new ObjectMapper().readTree(document().extract().asString());
    JsonNode ticket = null;
    for (JsonNode entry : document.path("archetypes")) {
      if ("TICKET".equals(entry.path("archetype").asText())) {
        ticket = entry;
      }
    }
    Map<String, List<Map<String, String>>> transitions =
        new ObjectMapper().convertValue(ticket.path("transitions"), new TypeReference<>() {});
    Map<String, List<Map<String, String>>> expected = new LinkedHashMap<>();
    expected.put(
        "REPORTED", List.of(move("REFINED", "FORWARD"), move("DROPPED", "DROP")));
    expected.put(
        "REFINED",
        List.of(move("READY_FOR_DEV", "FORWARD"), move("REPORTED", "BACK"), move("DROPPED", "DROP")));
    expected.put(
        "READY_FOR_DEV",
        List.of(
            move("IMPLEMENTING", "FORWARD"),
            move("IMPLEMENTED", "SKIP"),
            move("REFINED", "BACK"),
            move("DROPPED", "DROP")));
    expected.put(
        "IMPLEMENTING", List.of(move("IMPLEMENTED", "FORWARD"), move("DROPPED", "DROP")));
    expected.put(
        "IMPLEMENTED",
        List.of(
            move("VERIFYING", "FORWARD"),
            move("VERIFIED", "SKIP"),
            move("IMPLEMENTING", "BACK"),
            move("DROPPED", "DROP")));
    expected.put(
        "VERIFYING",
        List.of(move("VERIFIED", "FORWARD"), move("IMPLEMENTED", "BACK"), move("DROPPED", "DROP")));
    expected.put(
        "VERIFIED",
        List.of(move("DONE", "FORWARD"), move("VERIFYING", "BACK"), move("DROPPED", "DROP")));
    expected.put("DONE", List.of());
    expected.put("DROPPED", List.of(move("REPORTED", "REOPEN")));
    assertEquals(expected, transitions);
    assertEquals(
        List.copyOf(expected.keySet()),
        List.copyOf(transitions.keySet()),
        "keyed in lifecycle order");

    document()
        .body(
            at("TICKET") + "lifecycle",
            contains(
                "REPORTED",
                "REFINED",
                "READY_FOR_DEV",
                "IMPLEMENTING",
                "IMPLEMENTED",
                "VERIFYING",
                "VERIFIED",
                "DONE",
                "DROPPED"))
        .body(
            at("EPIC") + "lifecycle",
            contains(
                "REPORTED",
                "REFINED",
                "READY_FOR_DEV",
                "IMPLEMENTING",
                "IMPLEMENTED",
                "VERIFYING",
                "VERIFIED",
                "DONE",
                "DROPPED"))
        .body(at("EPIC") + "transitions.DONE", empty())
        // Additive: the alphabetical legalStatuses is still there, unchanged, beside it.
        .body(
            at("TICKET") + "legalStatuses",
            contains(
                "DONE",
                "DROPPED",
                "IMPLEMENTED",
                "IMPLEMENTING",
                "READY_FOR_DEV",
                "REFINED",
                "REPORTED",
                "VERIFIED",
                "VERIFYING"));
  }

  /** qits-763: a feature and a task serve the epic's moves and walk, both skips included. */
  @Test
  void aFeatureAndATaskServeTheEpicsMovesAndLifecycle() {
    Map<String, Object> epicMoves = document().extract().path(at("EPIC") + "transitions");
    List<String> epicWalk = document().extract().path(at("EPIC") + "lifecycle");
    for (String archetype : List.of("FEATURE", "TASK")) {
      document()
          .body(at(archetype) + "transitions", equalTo(epicMoves))
          .body(at(archetype) + "lifecycle", equalTo(epicWalk));
    }
  }

  @Test
  void theTwoServerOwnedPropertiesAreNamedSoNoFormRendersThem() {
    document().body("serverOwned", contains("SLUG", "CREATED_BY"));
  }

  @Test
  void twoReadsAnswerTheSameBytes() {
    // The document is derived from static declarations and nothing about a caller, so determinism
    // is a contract a client may cache against — and a hash order would break it silently.
    List<List<String>> first = document().extract().path("archetypes.permitted");
    List<List<String>> second = document().extract().path("archetypes.permitted");
    assertEquals(first, second);
  }
}
