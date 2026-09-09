package eu.wohlben.qits.projects.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertFalse;

import eu.wohlben.qits.projects.confighost.FakeMcpCredentials;
import eu.wohlben.qits.projects.control.AgentMcpCatalog;
import eu.wohlben.qits.projects.persistence.AgentMcpCatalogEntryRepository;
import eu.wohlben.qits.projects.persistence.AgentSurfaceExternalMcpAttachmentRepository;
import eu.wohlben.qits.projects.startup.AgentSurfaceSeed;
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
 * The external MCP catalog: what may be defined, what may be attached, and where the credential is —
 * and is not.
 *
 * <p><b>The load-bearing assertion in this file is a negative.</b> {@code
 * theEditorsAnswersNeverCarryAHeaderValue} is what stops a token reaching the editor, the OpenAPI
 * document or a revision snapshot; the positive that pairs with it is {@code
 * theDocumentCarriesTheFullyRenderedServer}, which is the one place a value is allowed to be. Delete
 * either and the other stops meaning anything.
 *
 * <p>Credentials come from {@code confighost/FakeMcpCredentials}, an in-memory qits-configuration
 * that models all three answers — value, "asked, not there", and "could not ask" — because the
 * catalog's write door treats the last two differently on purpose.
 */
@QuarkusTest
public class AgentMcpCatalogControllerTest {

  private static final String CATALOG = "/projects/api/agent-mcp-catalog";
  private static final String SURFACES = "/projects/api/agent-surfaces";
  private static final String DOCUMENT = "/projects/api/agent-configuration";

  private static final String KEY = "env.EXAMPLE_MCP_TOKEN";
  private static final String SECRET = "s3cr3t-do-not-log-me";

  @Inject AgentSurfaceSeed seed;

  @Inject FakeMcpCredentials credentials;

  @Inject AgentMcpCatalogEntryRepository entries;

  @Inject AgentSurfaceExternalMcpAttachmentRepository attachments;

  @BeforeEach
  void clean() {
    seed.seed();
    // requiringNew rather than @Transactional: a @Transactional method called from another method
    // of the same bean is a self-invocation the interceptor never sees.
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              attachments.deleteAll();
              entries.deleteAll();
            });
    credentials.reset();
    credentials.set(KEY, SECRET);
  }

  // -------------------------------------------------------------------------------------- writes

  @Test
  public void anEntryIsStoredByReferenceAndListedWithTheReservedKeysBesideIt() {
    define("weather", KEY);

    JsonPath answer = given().when().get(CATALOG).then().statusCode(200).extract().jsonPath();

    assertThat(answer.getList("entries.key", String.class), is(List.of("weather")));
    assertThat(answer.getString("entries[0].url"), is("https://mcp.example.com/sse"));
    assertThat(answer.getString("entries[0].headerName"), is("Authorization"));
    assertThat(answer.getString("entries[0].credentialKey"), is(KEY));
    assertThat(
        answer.getList("entries[0].allowedTools", String.class),
        is(List.of("mcp__weather__forecast")));
    assertThat(
        answer.getList("reservedKeys", String.class),
        is(List.of("repository", "observability", "actions")));
    // The namespace is answered, so the editor can tell an operator where to create the key.
    assertThat(
        answer.getString("credentialApplication"), is(AgentMcpCatalog.CREDENTIAL_APPLICATION));
  }

  /**
   * The reserved keys, and the refusal says why rather than reading as a bare validation error: an
   * entry under one of those names would displace a platform server in the rendered {@code
   * mcpServers} object and the session would look normal while talking to somebody else's server.
   */
  @Test
  public void aReservedKeyIsRefusedWithTheReasonSpelledOut() {
    for (String reserved : List.of("repository", "observability", "actions")) {
      String detail =
          given()
              .contentType(ContentType.JSON)
              .body(entryBody(KEY))
              .when()
              .put(CATALOG + "/" + reserved)
              .then()
              .statusCode(400)
              .extract()
              .asString();
      assertThat(detail, containsString("reserved"));
    }
  }

  /** URL transport only — Kimi's ACP shape has nowhere to put a stdio command. */
  @Test
  public void aNonHttpUrlIsRefused() {
    String detail =
        given()
            .contentType(ContentType.JSON)
            .body(
                Map.of(
                    "displayName", "Local",
                    "url", "stdio:///usr/bin/some-server",
                    "headerName", "",
                    "credentialKey", "",
                    "allowedTools", List.of()))
            .when()
            .put(CATALOG + "/local")
            .then()
            .statusCode(400)
            .extract()
            .asString();

    assertThat(detail, containsString("http"));
  }

  /** A confirmed-missing reference is a 400 at the form, naming the key and the application. */
  @Test
  public void aCredentialKeyQitsConfigurationDoesNotHoldIsRefusedNamingIt() {
    String detail =
        given()
            .contentType(ContentType.JSON)
            .body(entryBody("env.NOBODY_SET_THIS"))
            .when()
            .put(CATALOG + "/weather")
            .then()
            .statusCode(400)
            .extract()
            .asString();

    assertThat(detail, containsString("env.NOBODY_SET_THIS"));
    assertThat(detail, containsString(AgentMcpCatalog.CREDENTIAL_APPLICATION));
  }

  /**
   * "Could not ask" is not "not there". An outage of qits-configuration may not be dressed up as a
   * validation error — the strict gate is the document build, which is where a running agent is
   * actually protected.
   */
  @Test
  public void aReferenceThatCannotBeConfirmedIsStoredRatherThanRefused() {
    credentials.unreachable(true);

    given()
        .contentType(ContentType.JSON)
        .body(entryBody(KEY))
        .when()
        .put(CATALOG + "/weather")
        .then()
        .statusCode(200);
  }

  /** A key outside qits-configuration's own grammar could not be created there at all. */
  @Test
  public void aCredentialKeyOutsideTheEnvGrammarIsRefused() {
    String detail =
        given()
            .contentType(ContentType.JSON)
            .body(entryBody("secrets.WEATHER_TOKEN"))
            .when()
            .put(CATALOG + "/weather")
            .then()
            .statusCode(400)
            .extract()
            .asString();

    assertThat(detail, containsString("env.<VAR>"));
  }

  /** A header with no key renders an empty credential; a key with no header renders nowhere. */
  @Test
  public void aHeaderWithoutAKeyAndAKeyWithoutAHeaderAreBothRefused() {
    given()
        .contentType(ContentType.JSON)
        .body(
            Map.of(
                "displayName", "Weather",
                "url", "https://mcp.example.com/sse",
                "headerName", "Authorization",
                "credentialKey", "",
                "allowedTools", List.of()))
        .when()
        .put(CATALOG + "/weather")
        .then()
        .statusCode(400);

    given()
        .contentType(ContentType.JSON)
        .body(
            Map.of(
                "displayName", "Weather",
                "url", "https://mcp.example.com/sse",
                "headerName", "",
                "credentialKey", KEY,
                "allowedTools", List.of()))
        .when()
        .put(CATALOG + "/weather")
        .then()
        .statusCode(400);
  }

  /** A public server needs no credential, and that is a first-class entry rather than a half one. */
  @Test
  public void aServerWithNoCredentialIsAWholeEntry() {
    given()
        .contentType(ContentType.JSON)
        .body(
            Map.of(
                "displayName", "Public docs",
                "url", "https://docs.example.com/mcp",
                "headerName", "",
                "credentialKey", "",
                "allowedTools", List.of("mcp__docs__search")))
        .when()
        .put(CATALOG + "/docs")
        .then()
        .statusCode(200)
        .body("credentialKey", is(""));
  }

  // --------------------------------------------------------------------------------- attachments

  @Test
  public void aSurfaceAttachesACatalogEntryByKeyAndReadsItBackByReference() {
    define("weather", KEY);
    attach("project.epics", List.of("weather"));

    JsonPath answer =
        given().when().get(SURFACES + "/project.epics").then().statusCode(200).extract().jsonPath();

    assertThat(answer.getList("externalMcpServers.key", String.class), is(List.of("weather")));
    assertThat(answer.getString("externalMcpServers[0].url"), is("https://mcp.example.com/sse"));
    assertThat(answer.getString("externalMcpServers[0].credentialKey"), is(KEY));
    // The built-ins are untouched by an external attachment.
    assertThat(answer.getList("mcpServers.server", String.class), is(List.of("repository")));
  }

  @Test
  public void attachingAnEntryThatDoesNotExistIsRefused() {
    String detail =
        given()
            .contentType(ContentType.JSON)
            .body(surfaceBody(List.of("nonesuch")))
            .when()
            .put(SURFACES + "/project.epics")
            .then()
            .statusCode(400)
            .extract()
            .asString();

    assertThat(detail, containsString("nonesuch"));
  }

  @Test
  public void attachingTheSameEntryTwiceIsRefused() {
    define("weather", KEY);

    given()
        .contentType(ContentType.JSON)
        .body(surfaceBody(List.of("weather", "weather")))
        .when()
        .put(SURFACES + "/project.epics")
        .then()
        .statusCode(400);
  }

  /**
   * The refusal an FK would have made unreachable: a delete says which surfaces still attach the
   * entry, rather than arriving as a constraint violation.
   */
  @Test
  public void anEntryStillAttachedCannotBeDeletedAndTheRefusalNamesTheSurfaces() {
    define("weather", KEY);
    attach("project.epics", List.of("weather"));

    String detail =
        given().when().delete(CATALOG + "/weather").then().statusCode(400).extract().asString();
    assertThat(detail, containsString("project.epics"));

    // Detach, and it goes.
    attach("project.epics", List.of());
    given().when().delete(CATALOG + "/weather").then().statusCode(204);
    given().when().get(CATALOG + "/weather").then().statusCode(404);
  }

  // ---------------------------------------------------------------------------- the credential

  /**
   * The one place a header value is allowed to be: the document a container is created with, fully
   * rendered, so the container needs no second lookup.
   */
  @Test
  public void theDocumentCarriesTheFullyRenderedServer() {
    define("weather", KEY);
    attach("project.epics", List.of("weather"));

    JsonPath document = given().when().get(DOCUMENT).then().statusCode(200).extract().jsonPath();

    int epics =
        document.getList("surfaces.configuration.surface", String.class).indexOf("project.epics");
    String at = "surfaces[" + epics + "].externalMcpServers[0].";
    assertThat(document.getString(at + "key"), is("weather"));
    assertThat(document.getString(at + "url"), is("https://mcp.example.com/sse"));
    assertThat(document.getString(at + "headerName"), is("Authorization"));
    assertThat(document.getString(at + "headerValue"), is(SECRET));
    assertThat(
        document.getList(at + "allowedTools", String.class), is(List.of("mcp__weather__forecast")));
  }

  /**
   * <b>The negative this feature is built around.</b> A header value must not reach the editor's
   * answers — which are also what a revision snapshot is built from and what the OpenAPI document
   * describes. The catalog and the surface both answer the reference and never the value.
   */
  @Test
  public void theEditorsAnswersNeverCarryAHeaderValue() {
    define("weather", KEY);
    attach("project.epics", List.of("weather"));

    for (String path :
        List.of(CATALOG, CATALOG + "/weather", SURFACES, SURFACES + "/project.epics",
            SURFACES + "/project.epics/revisions")) {
      String body = given().when().get(path).then().statusCode(200).extract().asString();
      assertFalse(body.contains(SECRET), path + " answered a credential's value");
      assertThat(body, not(containsString(SECRET)));
    }
  }

  /**
   * A reference that cannot be resolved fails the whole document build, naming the key — rather than
   * rendering an unauthenticated server that 401s on the agent's first tool call, which surfaces as
   * a confused agent hours later with nothing pointing back here.
   */
  @Test
  public void anUnresolvableCredentialFailsTheDocumentBuildLoudlyAndNamesTheKey() {
    define("weather", KEY);
    attach("project.epics", List.of("weather"));
    credentials.unreachable(true);

    String detail =
        given().when().get(DOCUMENT).then().statusCode(500).extract().asString();

    assertThat(detail, containsString(KEY));
    assertThat(detail, containsString("weather"));
  }

  /** With nothing attached, the document is built without asking qits-configuration anything. */
  @Test
  public void aSurfaceAttachingNothingNeedsNoCredentialAtAll() {
    credentials.unreachable(true);

    JsonPath document = given().when().get(DOCUMENT).then().statusCode(200).extract().jsonPath();

    assertThat(document.getInt("version"), is(2));
    assertThat(document.getList("surfaces[0].externalMcpServers", Object.class), is(List.of()));
  }

  // -----------------------------------------------------------------------------------------

  private static Map<String, Object> entryBody(String credentialKey) {
    return Map.of(
        "displayName", "Weather",
        "url", "https://mcp.example.com/sse",
        "headerName", "Authorization",
        "credentialKey", credentialKey,
        "allowedTools", List.of("mcp__weather__forecast"));
  }

  private static void define(String key, String credentialKey) {
    given()
        .contentType(ContentType.JSON)
        .body(entryBody(credentialKey))
        .when()
        .put(CATALOG + "/" + key)
        .then()
        .statusCode(200);
  }

  private static Map<String, Object> surfaceBody(List<String> external) {
    return Map.ofEntries(
        Map.entry("harness", "CLAUDE"),
        Map.entry("model", ""),
        Map.entry("effort", ""),
        Map.entry("remoteControl", true),
        Map.entry("permissionMode", "SKIP_PERMISSIONS"),
        Map.entry("activityTracking", true),
        Map.entry("systemPrompt", ""),
        Map.entry("initialPrompt", ""),
        Map.entry(
            "mcpServers",
            List.of(
                Map.of(
                    "server", "repository",
                    "narrowProject", true,
                    "narrowRepository", false,
                    "narrowWorkspace", false,
                    "readOnly", false))),
        Map.entry("externalMcpServers", external));
  }

  private static void attach(String surface, List<String> external) {
    given()
        .contentType(ContentType.JSON)
        .body(surfaceBody(external))
        .when()
        .put(SURFACES + "/" + surface)
        .then()
        .statusCode(200);
  }
}
