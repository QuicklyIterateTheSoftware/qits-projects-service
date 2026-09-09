package eu.wohlben.qits.projects.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.control.AgentSurfaceDefaults;
import eu.wohlben.qits.projects.startup.AgentSurfaceSeed;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The two doors this feature opens: the editor's and the container's.
 *
 * <p>The suite drives {@link AgentSurfaceSeed#seed()} directly rather than waiting on the boot event
 * — the neighbouring startup beans are exercised the same way, and a virtual thread fired at
 * augmentation is not something a test may race. Seeding is idempotent, so calling it per test is
 * free.
 *
 * <p>The response field names are asserted as <b>literal strings</b>, which is the rule the daemon
 * repositories already keep for the same reason: these keys are a wire contract that a generated
 * client and a peer service consume, and a test that read them off the record would rename itself
 * along with the bug.
 */
@QuarkusTest
public class AgentSurfaceConfigurationControllerTest {

  @Inject AgentSurfaceSeed seed;

  @BeforeEach
  void seeded() {
    seed.seed();
  }

  @Test
  public void theListingAnswersEverySurfaceInTheVocabularysOwnOrder() {
    JsonPath answer =
        given().when().get("/projects/api/agent-surfaces").then().statusCode(200).extract().jsonPath();

    // The editor's list must not reshuffle as rows are written.
    assertThat(answer.getList("surfaces.surface", String.class), is(AgentSurfaceDefaults.SURFACES));
    assertThat(
        answer.getList("builtInServers", String.class),
        is(List.of("repository", "observability", "actions")));
  }

  /** The one seeded prompt, over the wire, byte for byte. */
  @Test
  public void theTicketsDeskAnswersItsSeededPromptAndItsOneProjectScopedServer() {
    JsonPath answer =
        given()
            .when()
            .get("/projects/api/agent-surfaces/project.tickets")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();

    assertThat(answer.getString("surface"), is("project.tickets"));
    assertThat(answer.getString("harness"), is("CLAUDE"));
    assertThat(answer.getString("permissionMode"), is("SKIP_PERMISSIONS"));
    assertThat(answer.getBoolean("activityTracking"), is(true));
    assertThat(answer.getString("model"), is(""));
    assertThat(answer.getString("effort"), is(""));
    assertThat(answer.getString("systemPrompt"), is(AgentSurfaceDefaults.TICKETS_DESK_PROMPT));
    assertThat(answer.getList("mcpServers.server", String.class), is(List.of("repository")));
    assertThat(answer.getBoolean("mcpServers[0].narrowProject"), is(true));
    assertThat(answer.getBoolean("mcpServers[0].narrowRepository"), is(false));
    assertThat(answer.getBoolean("mcpServers[0].readOnly"), is(false));
    assertThat(
        answer.getList("mcpServers[0].allowedTools", String.class),
        hasItem("mcp__repository__get_ticket"));
  }

  /**
   * The epics desk's empty system prompt is present as {@code ""} and not absent, because the two
   * mean different things everywhere downstream of here.
   */
  @Test
  public void theEpicsDeskAnswersAnEmptySystemPromptRatherThanNone() {
    JsonPath answer =
        given()
            .when()
            .get("/projects/api/agent-surfaces/project.epics")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
    assertThat(answer.getString("systemPrompt"), notNullValue());
    assertThat(answer.getString("systemPrompt"), is(""));
  }

  /** The rule the whole rollout rests on: a surface nobody has heard of still answers. */
  @Test
  public void anUnknownSurfaceAnswersItsShippedDefaultRatherThan404() {
    JsonPath answer =
        given()
            .when()
            .get("/projects/api/agent-surfaces/some.future.surface")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
    assertThat(answer.getString("surface"), is("some.future.surface"));
    assertThat(answer.getString("harness"), is("CLAUDE"));
    assertThat(answer.getBoolean("shipped"), is(true));
    assertThat(answer.getList("mcpServers"), is(List.of()));
  }

  @Test
  public void anEditIsStoredAndAppearsOnTheRevisionTrail() {
    Map<String, Object> body =
        Map.of(
            "harness", "KIMI",
            "model", "kimi-latest",
            "effort", "",
            "remoteControl", false,
            "permissionMode", "PROMPT",
            "activityTracking", false,
            "systemPrompt", "Steer like this.",
            "initialPrompt", "Say hello.",
            "mcpServers",
                List.of(
                    Map.of(
                        "server", "repository",
                        "narrowProject", true,
                        "narrowRepository", true,
                        "narrowWorkspace", false,
                        "readOnly", false)));

    JsonPath saved =
        given()
            .contentType(ContentType.JSON)
            .body(body)
            .when()
            .put("/projects/api/agent-surfaces/epic.chat")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();

    assertThat(saved.getString("harness"), is("KIMI"));
    assertThat(saved.getString("model"), is("kimi-latest"));
    assertThat(saved.getString("permissionMode"), is("PROMPT"));
    assertThat(saved.getString("systemPrompt"), is("Steer like this."));
    assertThat(saved.getBoolean("shipped"), is(false));
    assertThat(saved.getList("mcpServers.server", String.class), is(List.of("repository")));
    // The pre-approval list is not something the editor sent, and it survived the edit: the
    // workspace daemon's longer repository list is what this surface was seeded with.
    assertThat(
        saved.getList("mcpServers[0].allowedTools", String.class),
        hasItem("mcp__repository__mark_task_implemented"));

    JsonPath history =
        given()
            .when()
            .get("/projects/api/agent-surfaces/epic.chat/revisions")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
    // Two: the seed's own row, then this edit — newest first.
    assertTrue(history.getList("revisions").size() >= 2, "the seed and the edit both leave a row");
    assertThat(history.getString("revisions[0].surface"), is("epic.chat"));
    assertThat(history.getString("revisions[0].changedAt"), notNullValue());
    assertThat(history.getString("revisions[0].snapshot"), containsString("Steer like this."));
  }

  @Test
  public void anUnknownHarnessIsRefusedAndSaysWhatIsKnown() {
    given()
        .contentType(ContentType.JSON)
        .body(update("GEMINI", "SKIP_PERMISSIONS", List.of()))
        .when()
        .put("/projects/api/agent-surfaces/project.epics")
        .then()
        .statusCode(400)
        .body("message", containsString("CLAUDE, KIMI"));
  }

  @Test
  public void anUnknownPermissionModeIsRefusedAndSaysWhatIsKnown() {
    given()
        .contentType(ContentType.JSON)
        .body(update("CLAUDE", "ASK_NICELY", List.of()))
        .when()
        .put("/projects/api/agent-surfaces/project.epics")
        .then()
        .statusCode(400)
        .body("message", containsString("SKIP_PERMISSIONS, PROMPT"));
  }

  /**
   * A server that does not exist is a 400, not a row: the render path would have handed the session
   * a url nothing serves, which surfaces to a user as a tool that simply is not there.
   */
  @Test
  public void anMcpAttachmentNamingAServerThatDoesNotExistIsRefused() {
    given()
        .contentType(ContentType.JSON)
        .body(
            update(
                "CLAUDE",
                "SKIP_PERMISSIONS",
                List.of(
                    Map.of(
                        "server", "jira",
                        "narrowProject", false,
                        "narrowRepository", false,
                        "narrowWorkspace", false,
                        "readOnly", false))))
        .when()
        .put("/projects/api/agent-surfaces/project.epics")
        .then()
        .statusCode(400)
        .body("message", containsString("repository, observability, actions"));
  }

  /** Both harnesses render one {@code key → config} object, so a repeat silently displaces. */
  @Test
  public void theSameServerAttachedTwiceIsRefused() {
    Map<String, Object> attachment =
        Map.of(
            "server", "repository",
            "narrowProject", true,
            "narrowRepository", false,
            "narrowWorkspace", false,
            "readOnly", false);
    given()
        .contentType(ContentType.JSON)
        .body(update("CLAUDE", "SKIP_PERMISSIONS", List.of(attachment, attachment)))
        .when()
        .put("/projects/api/agent-surfaces/project.epics")
        .then()
        .statusCode(400)
        .body("message", containsString("attached twice"));
  }

  /**
   * The container's door: one snapshot, every surface, versioned.
   *
   * <p><b>A document surface is {@code {configuration, externalMcpServers}}</b> since the external
   * MCP catalog landed, which is what version 2 says. The wrapper is not decoration: the resolved
   * external servers carry credentials and had nowhere honest to sit inside a record the editor also
   * reads, so the configuration stayed one record and the credential-bearing half went beside it,
   * behind this door alone.
   */
  @Test
  public void theContainerDoorAnswersTheWholeDocument() {
    JsonPath document =
        given()
            .when()
            .get("/projects/api/agent-configuration")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();

    assertThat(document.getInt("version"), is(2));
    assertThat(document.getString("generatedAt"), notNullValue());
    assertThat(
        document.getList("surfaces.configuration.surface", String.class), hasItem("project.tickets"));
    assertThat(
        document.getList("surfaces.configuration.surface", String.class), hasItem("ticket.dispatch"));
    assertThat(
        document.getString(
            "surfaces.find { it.configuration.surface == 'project.tickets' }"
                + ".configuration.systemPrompt"),
        is(AgentSurfaceDefaults.TICKETS_DESK_PROMPT));
    // No catalog entry is attached anywhere by default, so no credential is read to build this.
    assertThat(
        document.getList(
            "surfaces.find { it.configuration.surface == 'project.tickets' }.externalMcpServers",
            Object.class),
        is(List.of()));
  }

  private static Map<String, Object> update(
      String harness, String permissionMode, List<Map<String, Object>> servers) {
    return Map.of(
        "harness", harness,
        "model", "",
        "effort", "",
        "remoteControl", false,
        "permissionMode", permissionMode,
        "activityTracking", true,
        "systemPrompt", "",
        "initialPrompt", "",
        "mcpServers", servers);
  }
}
