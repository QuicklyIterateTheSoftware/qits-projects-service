package eu.wohlben.qits.projects.contracts.consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.projects.contracts.consumer.ConsumerRow.Trigger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The machinery behind every consumer row, against a fixture provider ({@code qits-fixture}, under
 * {@code consumer-contracts-fixture/golden-masters/} on the test classpath): the answer cut down to
 * what is consumed, status-only rows, provider-state params in the path, the query and the body,
 * and the refusals. The real tables mostly wait on provider states, so this is what proves the
 * builder before they turn live.
 */
class ConsumerInteractionsTest {

  static {
    System.setProperty("pact_do_not_track", "true");
  }

  private static final ConsumerGoldenMasters FIXTURE =
      new ConsumerGoldenMasters("consumer-contracts-fixture/golden-masters/");

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final HttpClient HTTP = HttpClient.newHttpClient();

  private static ConsumerRow row(
      String operationId,
      String method,
      String path,
      Map<String, String> query,
      JsonNode body,
      List<String> consumes,
      int status,
      ConsumerRow.Call call) {
    return new ConsumerRow(
        "qits-fixture-service",
        "qits-fixture",
        operationId,
        "a thing with parts",
        method,
        path,
        query,
        body,
        consumes,
        status,
        Trigger.schedule("FixtureJob.run"),
        call,
        "a fixture");
  }

  private static HttpResponse<String> get(String url) throws Exception {
    return HTTP.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
  }

  private static final ConsumerRow GET_THING =
      row(
          "getThing",
          "GET",
          "/fixture/api/things/{thingId}",
          Map.of("view", "{thingId}"),
          null,
          List.of("$.name", "$.parts[*].label", "$.parts[*].note", "$.owner", "$.tags"),
          200,
          (base, p) -> {
            HttpResponse<String> response =
                get(base + "/fixture/api/things/" + p.get("thingId") + "?view=" + p.get("thingId"));
            assertEquals(200, response.statusCode());
            JsonNode body = MAPPER.readTree(response.body());
            assertEquals("Widget", body.path("name").asText());
            assertEquals("Ada", body.path("owner").path("name").asText());
          });

  private static JsonNode pactOf(ConsumerRow row) throws Exception {
    return MAPPER.readTree(
        ConsumerPactFilesTest.normalise(ConsumerPactFilesTest.written(FIXTURE, List.of(row))));
  }

  @Test
  void theAnswerHoldsOnlyWhatIsConsumed() throws Exception {
    JsonNode interaction = pactOf(GET_THING).path("interactions").path(0);
    JsonNode body = interaction.path("response").path("body").path("content");
    assertEquals(Keys.of("name", "parts", "owner", "tags"), Keys.keys(body));
    assertEquals(Keys.of("label", "note"), Keys.keys(body.path("parts").path(0)));
    assertEquals(Keys.of("name", "email"), Keys.keys(body.path("owner")), "a whole object is bound");
    assertTrue(body.path("tags").isArray() && body.path("tags").isEmpty());

    JsonNode rules = interaction.path("response").path("matchingRules").path("body");
    assertEquals("type", rules.path("$.parts").path("matchers").path(0).path("match").asText());
    assertEquals(1, rules.path("$.parts").path("matchers").path(0).path("min").asInt());
    assertTrue(
        rules.path("$.parts[*].note").toString().contains("null"),
        "a field null in one element matches type or null: " + rules.path("$.parts[*].note"));
    assertFalse(rules.has("$.id"), "an id nobody reads is not bound");
  }

  @Test
  void theStateParamsAreGeneratedInThePathAndQuery() throws Exception {
    JsonNode interaction = pactOf(GET_THING).path("interactions").path(0);
    JsonNode request = interaction.path("request");
    assertEquals(
        "/fixture/api/things/00000000-0000-4000-8000-000000000001", request.path("path").asText());
    JsonNode generators = request.path("generators");
    assertEquals(
        "/fixture/api/things/${thingId}", generators.path("path").path("expression").asText());
    assertEquals(
        "${thingId}", generators.path("query").path("view").path("expression").asText());
    assertEquals("a thing with parts", interaction.path("providerStates").path(0).path("name").asText());
    assertEquals(
        "00000000-0000-4000-8000-000000000001",
        interaction.path("providerStates").path(0).path("params").path("thingId").asText());
  }

  @Test
  void bothReferencesAreCarried() throws Exception {
    JsonNode refs = pactOf(GET_THING).path("interactions").path(0).path("comments").path("references");
    assertEquals("qits-fixture-service", refs.path("qits-call").path("app").asText());
    assertEquals("getThing", refs.path("qits-call").path("operationId").asText());
    assertEquals("schedule", refs.path("qits-trigger").path("kind").asText());
    assertEquals("qits-projects-service", refs.path("qits-trigger").path("app").asText());
    assertEquals("FixtureJob.run", refs.path("qits-trigger").path("schedule").asText());
  }

  @Test
  void aRowRunsAgainstTheMockServer() {
    ConsumerPactsTest.run(FIXTURE, GET_THING);
  }

  @Test
  void aRootArrayIsBoundElementByTemplate() throws Exception {
    ConsumerRow list =
        row(
            "listThings",
            "GET",
            "/fixture/api/things",
            Map.of(),
            null,
            List.of("$[*].id"),
            200,
            (base, p) -> {
              JsonNode body = MAPPER.readTree(get(base + "/fixture/api/things").body());
              assertTrue(body.isArray() && body.size() >= 1);
            });
    ConsumerPactsTest.run(FIXTURE, list);
    JsonNode response = pactOf(list).path("interactions").path(0).path("response");
    assertEquals(Keys.of("id"), Keys.keys(response.path("body").path("content").path(0)));
    assertEquals(
        "regex",
        response.path("matchingRules").path("body").path("$[*].id").path("matchers").path(0).path("match").asText());
  }

  @Test
  void aStatusOnlyRowBindsNoBodyAndSendsItsOwnRequest() throws Exception {
    ConsumerRow rename =
        row(
            "renameThing",
            "POST",
            "/fixture/api/things/{thingId}/name",
            Map.of(),
            ConsumerRow.json("{\"name\":\"Gizmo\",\"thing\":\"{thingId}\"}"),
            List.of(),
            204,
            (base, p) -> {
              HttpResponse<String> response =
                  HTTP.send(
                      HttpRequest.newBuilder(
                              URI.create(base + "/fixture/api/things/" + p.get("thingId") + "/name"))
                          .header("Content-Type", "application/json")
                          .POST(
                              HttpRequest.BodyPublishers.ofString(
                                  "{\"name\":\"Gizmo\",\"thing\":\"" + p.get("thingId") + "\"}"))
                          .build(),
                      HttpResponse.BodyHandlers.ofString());
              assertEquals(204, response.statusCode());
            });
    ConsumerPactsTest.run(FIXTURE, rename);
    JsonNode interaction = pactOf(rename).path("interactions").path(0);
    assertTrue(interaction.path("response").path("body").isMissingNode());
    assertEquals(
        "${thingId}",
        interaction.path("request").path("generators").path("body").path("$.thing").path("expression").asText());
  }

  @Test
  void aRowWhoseStateIsNotRecordedIsPendingWithTheStateItNeeds() {
    ConsumerRow missing =
        new ConsumerRow(
            "qits-fixture-service", "qits-fixture", "getThing", "a state nobody recorded", "GET",
            "/fixture/api/things/{thingId}", Map.of(), null, List.of(), 200,
            Trigger.schedule("FixtureJob.run"), (base, p) -> {}, "a thing");
    assertEquals(
        "needs provider state 'a state nobody recorded' for getThing in qits-fixture-service (a"
            + " thing)",
        missing.pending(FIXTURE).orElseThrow());
    assertTrue(missing.pending(new ConsumerGoldenMasters("no-such-root/")).isPresent());
  }

  @Test
  void aRowThatAsksSomethingElseThanTheRecordingIsRefused() {
    ConsumerRow wrongPath =
        row("getThing", "GET", "/fixture/api/thing/{thingId}", Map.of(), null, List.of(), 200, (b, p) -> {});
    IllegalStateException refused =
        assertThrows(IllegalStateException.class, () -> ConsumerInteractions.pact(FIXTURE, List.of(wrongPath)));
    assertTrue(refused.getMessage().contains("records GET /fixture/api/things/{thingId}"), refused.getMessage());

    ConsumerRow absent =
        row("getThing", "GET", "/fixture/api/things/{thingId}", Map.of(), null, List.of("$.colour"), 200, (b, p) -> {});
    IllegalStateException unrecorded =
        assertThrows(IllegalStateException.class, () -> ConsumerInteractions.pact(FIXTURE, List.of(absent)));
    assertTrue(unrecorded.getMessage().contains("$.colour"), unrecorded.getMessage());
  }

  /** Small set helpers, so the assertions above read as sets of keys. */
  private static final class Keys {
    static java.util.Set<String> of(String... names) {
      return java.util.Set.of(names);
    }

    static java.util.Set<String> keys(JsonNode node) {
      java.util.Set<String> keys = new java.util.HashSet<>();
      node.fieldNames().forEachRemaining(keys::add);
      return keys;
    }
  }
}
