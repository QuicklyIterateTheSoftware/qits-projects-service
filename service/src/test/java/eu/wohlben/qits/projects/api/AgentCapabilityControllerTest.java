package eu.wohlben.qits.projects.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import eu.wohlben.qits.projects.control.AgentCapabilityDefaults;
import eu.wohlben.qits.projects.persistence.AgentHarnessCapabilityRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The capability catalogue: what a container reports, and what the editor reads.
 *
 * <p>The response field names are asserted as <b>literal strings</b>, the rule this repository and
 * both daemon repositories keep for the same reason: these keys are a wire contract that the daemons
 * are written against, and a test that read them off the record would rename itself along with the
 * bug.
 *
 * <p>The store is emptied per test rather than seeded, because the empty case is the one the editor
 * meets first on a real estate and is the case with the most to get wrong.
 */
@QuarkusTest
public class AgentCapabilityControllerTest {

  private static final String PATH = "/projects/api/agent-capabilities";

  @Inject AgentHarnessCapabilityRepository capabilities;

  @BeforeEach
  void empty() {
    QuarkusTransaction.requiringNew().run(capabilities::deleteAll);
  }

  /**
   * A fresh estate has started no container, and the editor still has to offer a model. That is the
   * whole reason a shipped fallback exists.
   */
  @Test
  public void anEmptyCacheAnswersTheShippedFallbackForEveryHarness() {
    JsonPath answer = given().when().get(PATH).then().statusCode(200).extract().jsonPath();

    assertThat(answer.getList("harnesses.harness", String.class), is(List.of("CLAUDE", "KIMI")));
    assertThat(answer.getBoolean("harnesses[0].shipped"), is(true));
    assertThat(answer.getBoolean("harnesses[1].shipped"), is(true));
    assertThat(
        answer.getList("harnesses[0].models", String.class), is(AgentCapabilityDefaults.CLAUDE_MODELS));
    // No image version to name: nothing reported. Empty rather than a placeholder, because
    // "unknown" and "a build called unknown" must not look alike.
    assertThat(answer.getString("harnesses[0].imageVersion"), is(""));
    assertThat(answer.get("harnesses[0].reportedAt"), is(nullValue()));
  }

  /**
   * The two asymmetries the whole abstraction exists for, on the fallback: Claude cannot enumerate
   * its models and has effort levels; Kimi has no effort concept at all.
   */
  @Test
  public void theShippedFallbackKeepsTheTwoHarnessesAsymmetriesRatherThanFlatteningThem() {
    JsonPath answer = given().when().get(PATH).then().statusCode(200).extract().jsonPath();

    assertThat(answer.getBoolean("harnesses[0].effortSupported"), is(true));
    assertThat(
        answer.getList("harnesses[0].effortLevels", String.class),
        is(AgentCapabilityDefaults.CLAUDE_EFFORT_LEVELS));
    // Kimi: no effort control at all, not a disabled one carrying Claude's values.
    assertThat(answer.getBoolean("harnesses[1].effortSupported"), is(false));
    assertThat(answer.getList("harnesses[1].effortLevels", String.class), is(List.of()));
    // Claude has no listing command, so its models are a shipped alias set and the editor must
    // lead with the free-text escape.
    assertThat(answer.getBoolean("harnesses[0].modelsEnumerated"), is(false));
  }

  /** A report replaces the fallback for the harness it names, and only for that one. */
  @Test
  public void aReportBecomesTheCatalogueForItsHarness() {
    report("2026.900.1", claudeReport(List.of("opus", "sonnet"), true));

    JsonPath answer = given().when().get(PATH).then().statusCode(200).extract().jsonPath();

    assertThat(answer.getBoolean("harnesses[0].shipped"), is(false));
    assertThat(answer.getList("harnesses[0].models", String.class), is(List.of("opus", "sonnet")));
    assertThat(answer.getString("harnesses[0].imageVersion"), is("2026.900.1"));
    assertThat(answer.getString("harnesses[0].harnessVersion"), is("2.0.31"));
    assertThat(answer.getBoolean("harnesses[0].authenticated"), is(true));
    assertThat(answer.getString("harnesses[0].reportedBy"), is("qits-workspace-daemon"));
    // Kimi reported nothing, so it is still the fallback: one container's report is one harness's
    // answer and not a statement about the other.
    assertThat(answer.getBoolean("harnesses[1].shipped"), is(true));
  }

  /**
   * Two image versions disagreeing is the case this store exists to handle honestly: the newest
   * report wins <em>whole</em>, and the loser is named rather than merged in.
   */
  @Test
  public void whereTwoImageVersionsDisagreeTheNewestWinsWholeAndTheOtherIsNamed() {
    report("2026.900.1", claudeReport(List.of("opus", "sonnet"), true));
    report("2026.901.2", claudeReport(List.of("opus", "fable"), true));

    JsonPath answer = given().when().get(PATH).then().statusCode(200).extract().jsonPath();

    // Not the union: `sonnet` is gone, because the binary that offered it is not the one running.
    assertThat(answer.getList("harnesses[0].models", String.class), is(List.of("opus", "fable")));
    assertThat(answer.getString("harnesses[0].imageVersion"), is("2026.901.2"));
    assertThat(
        answer.getList("harnesses[0].otherImageVersions.imageVersion", String.class),
        is(List.of("2026.900.1")));
  }

  /** The same build reporting again replaces its own row rather than adding one. */
  @Test
  public void aContainerRestartingOnTheSameBuildReplacesItsOwnRow() {
    report("2026.900.1", claudeReport(List.of("opus"), true));
    report("2026.900.1", claudeReport(List.of("opus", "haiku"), false));

    JsonPath answer = given().when().get(PATH).then().statusCode(200).extract().jsonPath();

    assertThat(answer.getList("harnesses[0].models", String.class), is(List.of("opus", "haiku")));
    assertThat(answer.getBoolean("harnesses[0].authenticated"), is(false));
    // One row, so nothing to name beside it.
    assertThat(answer.getList("harnesses[0].otherImageVersions", Object.class), is(List.of()));
  }

  /**
   * A harness with no effort concept carries no levels, whatever it sent. Storing levels beside
   * {@code effortSupported: false} would be a set the editor must never show and the render path
   * must never pass.
   */
  @Test
  public void aHarnessWithNoEffortConceptStoresNoLevelsEvenIfItSentSome() {
    report(
        "2026.900.1",
        Map.of(
            "harness", "KIMI",
            "harnessVersion", "0.9.0",
            "models", List.of("kimi-k2"),
            "modelsEnumerated", true,
            "effortSupported", false,
            "effortLevels", List.of("low", "high"),
            "authenticated", true,
            "authDetail", "",
            "probeFailed", false,
            "probeDetail", ""));

    JsonPath answer = given().when().get(PATH).then().statusCode(200).extract().jsonPath();

    assertThat(answer.getList("harnesses[1].models", String.class), is(List.of("kimi-k2")));
    assertThat(answer.getBoolean("harnesses[1].modelsEnumerated"), is(true));
    assertThat(answer.getList("harnesses[1].effortLevels", String.class), is(List.of()));
  }

  /**
   * A probe that fell back still writes a row and says so — the editor gets a usable dropdown and
   * can explain why it may be stale, rather than sitting on a shipped fallback with no reason given.
   */
  @Test
  public void aFailedProbeIsRecordedRatherThanDropped() {
    report(
        "2026.900.1",
        Map.of(
            "harness", "CLAUDE",
            "harnessVersion", "",
            "models", AgentCapabilityDefaults.CLAUDE_MODELS,
            "modelsEnumerated", false,
            "effortSupported", true,
            "effortLevels", AgentCapabilityDefaults.CLAUDE_EFFORT_LEVELS,
            "authenticated", false,
            "authDetail", "",
            "probeFailed", true,
            "probeDetail", "`claude --help` timed out after 10s"));

    JsonPath answer = given().when().get(PATH).then().statusCode(200).extract().jsonPath();

    assertThat(answer.getBoolean("harnesses[0].shipped"), is(false));
    assertThat(answer.getBoolean("harnesses[0].probeFailed"), is(true));
    assertThat(
        answer.getString("harnesses[0].probeDetail"), is("`claude --help` timed out after 10s"));
  }

  /** A report this service could not key is a report it would silently drop; it is a 400 instead. */
  @Test
  public void anUnknownHarnessIsRefusedNamingTheKnownOnes() {
    String detail =
        given()
            .contentType(ContentType.JSON)
            .body(
                Map.of(
                    "reportedBy",
                    "qits-workspace-daemon",
                    "imageVersion",
                    "2026.900.1",
                    "capabilities",
                    List.of(Map.of("harness", "CURSOR", "models", List.of()))))
            .when()
            .put(PATH)
            .then()
            .statusCode(400)
            .extract()
            .asString();

    assertThat(detail, containsString("CURSOR"));
    assertThat(detail, containsString("CLAUDE"));
  }

  /** A report naming no harness at all is a 400 rather than a silently accepted no-op. */
  @Test
  public void anEmptyReportIsRefused() {
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("reportedBy", "d", "imageVersion", "v", "capabilities", List.of()))
        .when()
        .put(PATH)
        .then()
        .statusCode(400);
  }

  /**
   * The ingest body is the daemon's {@code GET /agents/available} body passed through, so the two
   * members this door does not read must not make it choke — a relay that had to strip them would be
   * a third place the contract can drift.
   */
  @Test
  public void theAgentsAvailableMembersTheDoorDoesNotReadArePassedThroughHarmlessly() {
    given()
        .contentType(ContentType.JSON)
        .body(
            Map.of(
                "agents", List.of("CLAUDE", "KIMI"),
                "defaultAgent", "CLAUDE",
                "reportedBy", "qits-projects-daemon",
                "imageVersion", "2026.900.1",
                "capabilities", List.of(claudeReport(List.of("opus"), true))))
        .when()
        .put(PATH)
        .then()
        .statusCode(200)
        .body("recorded", is(1));
  }

  // -----------------------------------------------------------------------------------------

  private static Map<String, Object> claudeReport(List<String> models, boolean authenticated) {
    return Map.of(
        "harness", "CLAUDE",
        "harnessVersion", "2.0.31",
        "models", models,
        "modelsEnumerated", false,
        "effortSupported", true,
        "effortLevels", AgentCapabilityDefaults.CLAUDE_EFFORT_LEVELS,
        "authenticated", authenticated,
        "authDetail", "",
        "probeFailed", false,
        "probeDetail", "");
  }

  private static void report(String imageVersion, Map<String, Object> harness) {
    given()
        .contentType(ContentType.JSON)
        .body(
            Map.of(
                "reportedBy", "qits-workspace-daemon",
                "imageVersion", imageVersion,
                "capabilities", List.of(harness)))
        .when()
        .put(PATH)
        .then()
        .statusCode(200);
  }
}
