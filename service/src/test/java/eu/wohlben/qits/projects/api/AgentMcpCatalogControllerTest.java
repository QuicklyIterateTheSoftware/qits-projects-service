package eu.wohlben.qits.projects.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertFalse;

import eu.wohlben.qits.projects.persistence.AgentMcpCatalogEntryRepository;
import eu.wohlben.qits.projects.persistence.AgentMcpCredentialRepository;
import eu.wohlben.qits.projects.persistence.AgentSurfaceExternalMcpAttachmentRepository;
import eu.wohlben.qits.projects.startup.AgentSurfaceSeed;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;
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
 * <p>Credentials live in this service's own {@code agent_mcp_credential} table (qits-1394), set and
 * removed through the write-only doors under {@code /agent-mcp-catalog/credentials}; the tests write
 * them through those doors, exactly as the {@code qits agent-mcp credentials} verbs do.
 */
@QuarkusTest
public class AgentMcpCatalogControllerTest {

  private static final String CATALOG = "/projects/api/agent-mcp-catalog";
  private static final String SURFACES = "/projects/api/agent-surfaces";
  private static final String DOCUMENT = "/projects/api/agent-configuration";
  private static final String CREDENTIALS = CATALOG + "/credentials";

  private static final String KEY = "env.EXAMPLE_MCP_TOKEN";
  private static final String SECRET = "s3cr3t-do-not-log-me";

  @Inject AgentSurfaceSeed seed;

  @Inject AgentMcpCredentialRepository credentials;

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
              credentials.deleteAll();
            });
    setCredential(KEY, SECRET);
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
        is(List.of("repository", "observability", "actions", "qits")));
    // The application segment of the retired qits-configuration store is gone from the wire.
    assertThat(answer.getMap(""), not(hasKey("credentialApplication")));
  }

  /**
   * The reserved keys, and the refusal says why rather than reading as a bare validation error: an
   * entry under one of those names would displace a platform server in the rendered {@code
   * mcpServers} object and the session would look normal while talking to somebody else's server.
   */
  @Test
  public void aReservedKeyIsRefusedWithTheReasonSpelledOut() {
    for (String reserved : List.of("repository", "observability", "actions", "qits")) {
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

  /** A key with no credential set is a 400 at the form, naming the key and how to set it. */
  @Test
  public void aCredentialKeyWithNoCredentialSetIsRefusedNamingIt() {
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
    assertThat(detail, containsString("qits agent-mcp credentials set"));
  }

  /** `credentials` is the credential doors' segment, so no catalog entry may take it. */
  @Test
  public void theCredentialsSegmentIsNotACatalogKey() {
    given()
        .contentType(ContentType.JSON)
        .body(entryBody(KEY))
        .when()
        .put(CATALOG + "/credentials")
        .then()
        .statusCode(anyOf(is(400), is(405)));
  }

  /** A key outside the `env.<VAR>` grammar is refused. */
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
    attach("project.work", List.of("weather"));

    JsonPath answer =
        given().when().get(SURFACES + "/project.work").then().statusCode(200).extract().jsonPath();

    assertThat(answer.getList("externalMcpServers.key", String.class), is(List.of("weather")));
    assertThat(answer.getString("externalMcpServers[0].url"), is("https://mcp.example.com/sse"));
    assertThat(answer.getString("externalMcpServers[0].credentialKey"), is(KEY));
    // The built-ins are untouched by an external attachment.
    assertThat(answer.getList("mcpServers.server", String.class), is(List.of("qits")));
  }

  @Test
  public void attachingAnEntryThatDoesNotExistIsRefused() {
    String detail =
        given()
            .contentType(ContentType.JSON)
            .body(surfaceBody(List.of("nonesuch")))
            .when()
            .put(SURFACES + "/project.work")
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
        .put(SURFACES + "/project.work")
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
    attach("project.work", List.of("weather"));

    String detail =
        given().when().delete(CATALOG + "/weather").then().statusCode(400).extract().asString();
    assertThat(detail, containsString("project.work"));

    // Detach, and it goes.
    attach("project.work", List.of());
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
    attach("project.work", List.of("weather"));

    JsonPath document = given().when().get(DOCUMENT).then().statusCode(200).extract().jsonPath();

    int work =
        document.getList("surfaces.configuration.surface", String.class).indexOf("project.work");
    String at = "surfaces[" + work + "].externalMcpServers[0].";
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
    attach("project.work", List.of("weather"));

    for (String path :
        List.of(CATALOG, CATALOG + "/weather", SURFACES, SURFACES + "/project.work",
            SURFACES + "/project.work/revisions")) {
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
    attach("project.work", List.of("weather"));
    // Deleted after the entry was saved: the document build is the gate that catches it.
    given().when().delete(CREDENTIALS + "/" + KEY).then().statusCode(204);

    String detail =
        given().when().get(DOCUMENT).then().statusCode(500).extract().asString();

    assertThat(detail, containsString(KEY));
    assertThat(detail, containsString("weather"));
  }

  /** With nothing attached, the document is built without any credential set at all. */
  @Test
  public void aSurfaceAttachingNothingNeedsNoCredentialAtAll() {
    given().when().delete(CREDENTIALS + "/" + KEY).then().statusCode(204);

    JsonPath document = given().when().get(DOCUMENT).then().statusCode(200).extract().jsonPath();

    assertThat(document.getInt("version"), is(2));
    assertThat(document.getList("surfaces[0].externalMcpServers", Object.class), is(List.of()));
  }

  // ------------------------------------------------------------------------- the credential doors

  /**
   * Set, list, replace, delete — and no answer of any credential door ever carries a value. The PUT
   * answers the key and who set it; the listing answers keys only.
   */
  @Test
  public void aCredentialIsSetListedByKeyOnlyReplacedAndDeleted() {
    String set =
        given()
            .contentType(ContentType.JSON)
            .body(Map.of("value", "another-s3cr3t"))
            .when()
            .put(CREDENTIALS + "/env.OTHER_TOKEN")
            .then()
            .statusCode(200)
            .body("key", is("env.OTHER_TOKEN"))
            .body("updatedAt", org.hamcrest.Matchers.notNullValue())
            .body("$", not(hasKey("value")))
            .extract()
            .asString();
    assertFalse(set.contains("another-s3cr3t"), "the PUT answered the value");

    String listing = given().when().get(CREDENTIALS).then().statusCode(200).extract().asString();
    JsonPath list = JsonPath.from(listing);
    assertThat(
        list.getList("credentials.key", String.class), is(List.of(KEY, "env.OTHER_TOKEN")));
    assertFalse(listing.contains(SECRET), "the listing answered a value");
    assertFalse(listing.contains("another-s3cr3t"), "the listing answered a value");
    assertThat(list.getMap("credentials[0]"), not(hasKey("value")));

    // Replace in place: still one row under the key.
    setCredential("env.OTHER_TOKEN", "rotated");
    assertThat(
        given().when().get(CREDENTIALS).then().extract().jsonPath()
            .getList("credentials.key", String.class),
        is(List.of(KEY, "env.OTHER_TOKEN")));

    given().when().delete(CREDENTIALS + "/env.OTHER_TOKEN").then().statusCode(204);
    assertThat(
        given().when().get(CREDENTIALS).then().extract().jsonPath()
            .getList("credentials.key", String.class),
        is(List.of(KEY)));
  }

  /** A rotated value is what the next document carries. */
  @Test
  public void theDocumentReadsTheValueFromTheStore() {
    define("weather", KEY);
    attach("project.work", List.of("weather"));
    setCredential(KEY, "rotated-value");

    JsonPath document = given().when().get(DOCUMENT).then().statusCode(200).extract().jsonPath();
    int work =
        document.getList("surfaces.configuration.surface", String.class).indexOf("project.work");
    assertThat(
        document.getString("surfaces[" + work + "].externalMcpServers[0].headerValue"),
        is("rotated-value"));
  }

  @Test
  public void deletingACredentialThatIsNotSetIs404() {
    given().when().delete(CREDENTIALS + "/env.NEVER_SET").then().statusCode(404);
  }

  /** The key grammar is the catalog's, and a blank value is not a credential. */
  @Test
  public void aBadKeyOrABlankValueIsRefused() {
    String detail =
        given()
            .contentType(ContentType.JSON)
            .body(Map.of("value", "x"))
            .when()
            .put(CREDENTIALS + "/secrets.WEATHER")
            .then()
            .statusCode(400)
            .extract()
            .asString();
    assertThat(detail, containsString("env.<VAR>"));

    given()
        .contentType(ContentType.JSON)
        .body(Map.of("value", "  "))
        .when()
        .put(CREDENTIALS + "/env.BLANK")
        .then()
        .statusCode(400);
    given().when().delete(CREDENTIALS + "/not-a-key").then().statusCode(400);
  }

  /**
   * An agent reads the keys and writes nothing; the desk-admin is refused the writes too, since the
   * catalogue its surface is built from is its own rights. An admin-agent writes.
   */
  @Test
  public void anAgentListsButCannotWriteACredential() {
    as("an-agent", "qits:agent").when().get(CREDENTIALS).then().statusCode(200);
    as("an-agent", "qits:agent")
        .body(Map.of("value", "x"))
        .when()
        .put(CREDENTIALS + "/env.AGENT_TRY")
        .then()
        .statusCode(403);
    as("an-agent", "qits:agent").when().delete(CREDENTIALS + "/" + KEY).then().statusCode(403);
    as("a-desk", "qits:agent,qits:desk,qits:desk-admin")
        .body(Map.of("value", "x"))
        .when()
        .put(CREDENTIALS + "/env.DESK_TRY")
        .then()
        .statusCode(403);

    as("an-admin-agent", "qits:admin-agent")
        .body(Map.of("value", "x"))
        .when()
        .put(CREDENTIALS + "/env.ADMIN_AGENT")
        .then()
        .statusCode(200)
        .body("updatedBy", is("an-admin-agent"));
    as("an-admin-agent", "qits:admin-agent")
        .when()
        .delete(CREDENTIALS + "/env.ADMIN_AGENT")
        .then()
        .statusCode(204);
  }

  // -----------------------------------------------------------------------------------------

  private static RequestSpecification as(String user, String roles) {
    return given()
        .contentType(ContentType.JSON)
        .header("X-Qits-User", user)
        .header("X-Qits-Roles", roles);
  }

  private static void setCredential(String key, String value) {
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("value", value))
        .when()
        .put(CREDENTIALS + "/" + key)
        .then()
        .statusCode(200);
  }

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
                    "server", "qits",
                    "narrowProject", false,
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
