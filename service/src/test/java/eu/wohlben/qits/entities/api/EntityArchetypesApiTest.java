package eu.wohlben.qits.entities.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.entities.control.EntityProperty;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import jakarta.ws.rs.core.Response;
import java.util.List;
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
        .body("archetypes.size()", equalTo(4))
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
        .body("archetypes.archetype", contains("EPIC", "TICKET", "FEATURE", "TASK"))
        // The two roots share a depth — which is what makes ticket-under-epic refusable by the
        // ordinary parent.depth < child.depth rule a client applies for itself.
        .body(at("EPIC") + "depth", equalTo(0))
        .body(at("EPIC") + "mayBeRoot", equalTo(true))
        .body(at("TICKET") + "depth", equalTo(0))
        .body(at("TICKET") + "mayBeRoot", equalTo(true))
        .body(at("FEATURE") + "depth", equalTo(1))
        .body(at("FEATURE") + "mayBeRoot", equalTo(false))
        .body(at("TASK") + "depth", equalTo(2))
        .body(at("TASK") + "mayBeRoot", equalTo(false));
  }

  @Test
  void aTicketAsksForItsTypeAndItsImpetusAndItsStatus() {
    // The four a TicketService.create refuses a row without, which is exactly the set a form must
    // gather. In vocabulary order, so the fields read in the order the violations would.
    document()
        .body(
            at("TICKET") + "required",
            contains("TITLE", "STATUS", "TICKET_TYPE", "IMPETUS"))
        .body(
            at("TICKET") + "requiredOnTransition",
            contains("TITLE", "STATUS", "TICKET_TYPE", "IMPETUS"));
  }

  @Test
  void anEpicsTransitionWantsAStatusTheRegistryMerelyPermits() {
    // The transition's own rule, served: the registry permits an epic a status because the writer
    // mints the first one, and a transition mints nothing, so an omission would clear one.
    document()
        .body(at("EPIC") + "required", contains("TITLE"))
        .body(at("EPIC") + "requiredOnTransition", contains("TITLE", "STATUS"))
        .body(
            at("EPIC") + "permitted",
            contains("TITLE", "SLUG", "DESCRIPTION", "STATUS", "SUPERSEDED_BY"))
        .body(
            at("EPIC") + "legalStatuses",
            contains("ABANDONED", "IMPLEMENTATION", "IMPLEMENTED", "REFINING", "SUPERSEDED"));
  }

  @Test
  void aFeatureHasNoLifecycleSoTheTwoRequiredListsAgree() {
    document()
        .body(at("FEATURE") + "legalStatuses", empty())
        .body(at("FEATURE") + "required", contains("TITLE"))
        .body(at("FEATURE") + "requiredOnTransition", contains("TITLE"));
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
