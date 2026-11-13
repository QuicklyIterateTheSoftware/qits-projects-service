package eu.wohlben.qits.projects.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.bus.PitchChanged;
import eu.wohlben.qits.projects.bus.RecordingPitchEventAnnouncer;
import eu.wohlben.qits.projects.contracts.ContractBearers;
import eu.wohlben.qits.projects.control.AgentSurfaceDefaults;
import eu.wohlben.qits.projects.control.NamedAgents;
import eu.wohlben.qits.projects.control.ProjectService;
import eu.wohlben.qits.projects.entity.NamedAgent;
import eu.wohlben.qits.projects.entity.Pitch;
import eu.wohlben.qits.projects.entity.PitchKind;
import eu.wohlben.qits.projects.entity.PitchVersion;
import eu.wohlben.qits.projects.entity.PitchViewport;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>The pitch doors</b> (qits-2155): {@code /projects/api/pitches}. Every caller is named on the
 * request: a named agent and a workspace agent are {@code qits:agent} bearers with a {@code project}
 * claim told apart only by their token subject ({@link ContractBearers#grant}); a person and a
 * platform service are {@link ContractBearers#caller}s, which carry no token at all.
 *
 * <p>Pinned here: only a named agent creates (admins included in the 403), only the pitch's agent
 * adds a version, the archive's three callers, every reader bound to the pitch's project, the 409 on
 * an archived pitch, the 413 over the size limit, the 400s, the filters and the archived filter, and
 * the {@code PitchChanged} each change announces.
 */
@QuarkusTest
public class PitchControllerTest {

  private static final String DOOR = "/projects/api/pitches";

  /** One byte past the shipped {@code qits.projects.pitch-max-bytes} (4 MiB). */
  private static final int TOO_LARGE = 4 * 1024 * 1024 + 1;

  @Inject ContractBearers bearers;

  @Inject ProjectService projectService;

  @Inject RecordingPitchEventAnnouncer announced;

  /** The project, its two repositories and another project: every table is emptied per test. */
  private String projectId;

  private String projectSlug;
  private String appRepoId;
  private String otherRepoId;
  private String otherProjectId;

  /** Per test: the bearers, and a route token so each test's pitches are its own. */
  private String uiBuilder;

  private String otherProjectUiBuilder;
  private String workspaceAgent;
  private String desk;
  private String deskAdmin;
  private String token;

  @BeforeEach
  void seed() {
    token = UUID.randomUUID().toString().substring(0, 8);
    Project project = projectService.create("Pitches", "pitches-" + token, null);
    projectId = project.id;
    projectSlug = project.slug;
    appRepoId =
        projectService
            .createRepository(projectId, null, "pitch-app", RepositoryArchetype.FRONTEND)
            .repository()
            .id;
    otherRepoId =
        projectService
            .createRepository(projectId, null, "pitch-other", RepositoryArchetype.FRONTEND)
            .repository()
            .id;
    otherProjectId = projectService.create("Other pitches", "pitches-other-" + token, null).id;
    String uiSubject = "tok-ui-" + token;
    String otherUiSubject = "tok-ui-other-" + token;
    namedAgent(projectId, uiSubject);
    namedAgent(otherProjectId, otherUiSubject);
    uiBuilder = agentBearer("ui-" + token, uiSubject, projectId);
    otherProjectUiBuilder = agentBearer("ui-other-" + token, otherUiSubject, otherProjectId);
    workspaceAgent = agentBearer("ws-" + token, "tok-ws-" + token, projectId);
    desk = "Bearer desk-" + token;
    bearers.grant(
        desk,
        "tok-desk-" + token,
        Map.of("project", projectId, "groups", List.of("qits:agent", "qits:desk")),
        "qits:agent",
        "qits:desk");
    deskAdmin = "Bearer desk-admin-" + token;
    bearers.grant(
        deskAdmin,
        "tok-desk-admin-" + token,
        Map.of(
            "project",
            projectId,
            "groups",
            List.of("qits:agent", "qits:desk", "qits:desk-admin")),
        "qits:agent",
        "qits:desk",
        "qits:desk-admin");
  }

  private static void namedAgent(String project, String subject) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              NamedAgent row = new NamedAgent();
              row.projectId = project;
              row.name = NamedAgents.UI_BUILDER;
              row.surface = AgentSurfaceDefaults.PROJECT_UI_BUILDER;
              row.tokenSubject = subject;
              row.createdAt = Instant.now();
              row.persist();
            });
  }

  private String agentBearer(String name, String subject, String project) {
    String bearer = "Bearer " + name;
    bearers.grant(
        bearer,
        subject,
        Map.of("project", project, "groups", List.of("qits:agent")),
        "qits:agent");
    return bearer;
  }

  private static RequestSpecification as(String authorization) {
    return given().contentType(ContentType.JSON).header("Authorization", authorization);
  }

  private static String admin() {
    return ContractBearers.caller("dev", "qits:admin");
  }

  private String route(String name) {
    return "/" + name + "-" + token;
  }

  private static String body(
      String project, String repository, String route, String screenshot, String html) {
    return "{\"project\":\""
        + project
        + "\",\"repository\":\""
        + repository
        + "\",\"route\":\""
        + route
        + "\",\"screenshot\":\""
        + screenshot
        + "\",\"kind\":\"DATA\",\"viewport\":\"DESKTOP\",\"colorScheme\":\"DARK\","
        + "\"manifestVersion\":\"2026.1113.1\",\"title\":\"A calmer header\","
        + "\"note\":\"Less chrome\",\"html\":\""
        + html
        + "\",\"versionNote\":\"first cut\"}";
  }

  private String create(String route, String screenshot) {
    return create(appRepoId, route, screenshot);
  }

  private String create(String repository, String route, String screenshot) {
    return as(uiBuilder)
        .body(body(projectId, repository, route, screenshot, "<p>v1</p>"))
        .post(DOOR)
        .then()
        .statusCode(201)
        .extract()
        .path("id");
  }

  /** A pitch the desk owns, written straight to the tables. */
  private String deskPitch() {
    UUID id = UUID.randomUUID();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Instant now = Instant.now();
              Pitch row = new Pitch();
              row.id = id;
              row.projectId = projectId;
              row.agentName = NamedAgents.DESK;
              row.repositoryId = appRepoId;
              row.route = route("desk");
              row.screenshot = "desk/home.html";
              row.kind = PitchKind.DATA;
              row.viewport = PitchViewport.PHONE;
              row.manifestVersion = "1";
              row.title = "The desk's";
              row.createdBy = "tok-desk";
              row.createdAt = now;
              row.updatedAt = now;
              row.persistAndFlush();
              PitchVersion first = new PitchVersion();
              first.pitchId = id;
              first.number = 1;
              first.html = "<p>desk</p>";
              first.htmlBytes = first.html.length();
              first.createdAt = now;
              first.persist();
            });
    return id.toString();
  }

  // --- create ------------------------------------------------------------------------------------

  @Test
  public void aNamedAgentPitchesAndThePitchIsItsOwn() {
    announced.reset();
    JsonPath created =
        as(uiBuilder)
            .body(body(projectSlug, "pitch-app", route("home"), "home/landing-desktop-dark.html", "<p>v1</p>"))
            .post(DOOR)
            .then()
            .statusCode(201)
            .contentType(ContentType.JSON)
            .extract()
            .jsonPath();
    String id = created.getString("id");
    assertEquals("ui-builder", created.getString("agent"));
    assertEquals(appRepoId, created.getString("repositoryId"));
    assertEquals("pitch-app", created.getString("repositoryName"));
    assertEquals(route("home"), created.getString("route"));
    assertEquals("home/landing-desktop-dark.html", created.getString("screenshot"));
    assertEquals("DATA", created.getString("kind"));
    assertEquals("DESKTOP", created.getString("viewport"));
    assertEquals("DARK", created.getString("colorScheme"));
    assertEquals("2026.1113.1", created.getString("manifestVersion"));
    assertEquals(1, created.getInt("latestVersion"));
    assertNull(created.get("archivedAt"));
    assertEquals(List.of(1), created.getList("versions.number"));
    assertEquals("first cut", created.getString("versions[0].note"));
    assertEquals(9, created.getInt("versions[0].htmlBytes"));
    assertNull(created.get("versions[0].html"), "no detail carries the document");

    String createdBy =
        QuarkusTransaction.requiringNew()
            .call(() -> Pitch.<Pitch>findById(UUID.fromString(id)).createdBy);
    assertEquals("tok-ui-" + token, createdBy, "the token subject that pitched it");

    List<PitchChanged> events = announced.of(id);
    assertEquals(1, events.size());
    PitchChanged event = events.get(0);
    assertEquals("CREATED", event.change());
    assertEquals(1, event.version());
    assertEquals(projectId, event.projectId());
    assertEquals("ui-builder", event.agentName());
    assertEquals(appRepoId, event.repositoryId());
    assertEquals(route("home"), event.route());
    assertEquals("home/landing-desktop-dark.html", event.screenshot());
    assertNotNull(event.changedAt());
  }

  @Test
  public void onlyANamedAgentPitchesAdminsIncluded() {
    String request = body(projectId, appRepoId, route("refused"), "a.html", "<p>x</p>");
    for (String caller : List.of(admin(), workspaceAgent, desk, deskAdmin)) {
      String message =
          as(caller).body(request).post(DOOR).then().statusCode(403).extract().path("message");
      assertTrue(message.startsWith(PitchController.ONLY_A_NAMED_AGENT), message);
    }
    // Another project's named agent naming this project: its token covers its own project only.
    as(otherProjectUiBuilder).body(request).post(DOOR).then().statusCode(403);
    given().contentType(ContentType.JSON).body(request).post(DOOR).then().statusCode(401);
    assertEquals(
        0,
        as(admin())
            .queryParam("project", projectId)
            .queryParam("route", route("refused"))
            .get(DOOR)
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getList("pitches")
            .size());
  }

  @Test
  public void aCreateIsValidated() {
    String route = route("invalid");
    as(uiBuilder)
        .body(body(projectId, appRepoId, route, "a.html", ""))
        .post(DOOR)
        .then()
        .statusCode(400);
    as(uiBuilder)
        .body(body(projectId, appRepoId, route, "a.html", "<p>x</p>").replace("\"DATA\"", "\"FULL\""))
        .post(DOOR)
        .then()
        .statusCode(400);
    as(uiBuilder)
        .body(body(projectId, appRepoId, route, "a.html", "<p>x</p>").replace("\"DESKTOP\"", "\"WATCH\""))
        .post(DOOR)
        .then()
        .statusCode(400);
    as(uiBuilder)
        .body(body(projectId, appRepoId, route, "a.html", "<p>x</p>").replace("\"DARK\"", "\"SEPIA\""))
        .post(DOOR)
        .then()
        .statusCode(400);
    as(uiBuilder)
        .body(body(projectId, appRepoId, "", "a.html", "<p>x</p>"))
        .post(DOOR)
        .then()
        .statusCode(400);
    as(uiBuilder)
        .body(body(projectId, "no-such-repository", route, "a.html", "<p>x</p>"))
        .post(DOOR)
        .then()
        .statusCode(404);
    as(uiBuilder)
        .body(body("no-such-project-" + token, appRepoId, route, "a.html", "<p>x</p>"))
        .post(DOOR)
        .then()
        .statusCode(404);
    // A pitch of an app with no colour schemes names none.
    String id =
        as(uiBuilder)
            .body(body(projectId, appRepoId, route, "a.html", "<p>x</p>").replace("\"DARK\"", "null"))
            .post(DOOR)
            .then()
            .statusCode(201)
            .extract()
            .path("id");
    assertNull(as(admin()).get(DOOR + "/" + id).then().statusCode(200).extract().path("colorScheme"));
  }

  @Test
  public void htmlOverTheLimitIsRefused() {
    String huge = "a".repeat(TOO_LARGE);
    as(uiBuilder)
        .body(body(projectId, appRepoId, route("huge"), "a.html", huge))
        .post(DOOR)
        .then()
        .statusCode(413);
    String id = create(route("huge"), "a.html");
    as(uiBuilder)
        .body("{\"html\":\"" + huge + "\"}")
        .post(DOOR + "/" + id + "/versions")
        .then()
        .statusCode(413);
    as(uiBuilder)
        .body("{\"html\":\"\"}")
        .post(DOOR + "/" + id + "/versions")
        .then()
        .statusCode(400);
    assertEquals(
        1, as(admin()).get(DOOR + "/" + id).then().statusCode(200).extract().<Integer>path("latestVersion"));
  }

  // --- versions ----------------------------------------------------------------------------------

  @Test
  public void thePitchesAgentAddsVersionsAndEveryVersionIsKept() {
    String id = create(route("versions"), "a.html");
    announced.reset();
    JsonPath added =
        as(uiBuilder)
            .body("{\"html\":\"<p>version two</p>\",\"note\":\"bolder\"}")
            .post(DOOR + "/" + id + "/versions")
            .then()
            .statusCode(201)
            .extract()
            .jsonPath();
    assertEquals(2, added.getInt("number"));
    assertEquals("bolder", added.getString("note"));
    assertEquals(18, added.getInt("htmlBytes"));
    assertNull(added.get("html"), "the answer carries no document");

    JsonPath detail = as(workspaceAgent).get(DOOR + "/" + id).then().statusCode(200).extract().jsonPath();
    assertEquals(List.of(1, 2), detail.getList("versions.number"));
    assertEquals(2, detail.getInt("latestVersion"));

    JsonPath first =
        as(workspaceAgent)
            .get(DOOR + "/" + id + "/versions/1")
            .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .extract()
            .jsonPath();
    assertEquals("<p>v1</p>", first.getString("html"));
    JsonPath latest =
        as(workspaceAgent)
            .get(DOOR + "/" + id + "/versions/latest")
            .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .extract()
            .jsonPath();
    assertEquals(2, latest.getInt("number"));
    assertEquals("<p>version two</p>", latest.getString("html"));
    as(workspaceAgent).get(DOOR + "/" + id + "/versions/3").then().statusCode(404);
    as(workspaceAgent).get(DOOR + "/" + id + "/versions/two").then().statusCode(400);
    as(workspaceAgent).get(DOOR + "/" + id + "/versions/0").then().statusCode(400);

    List<PitchChanged> events = announced.of(id);
    assertEquals(1, events.size());
    assertEquals("VERSION_ADDED", events.get(0).change());
    assertEquals(2, events.get(0).version());
  }

  @Test
  public void onlyThePitchesAgentChangesIt() {
    String id = create(route("owner"), "a.html");
    String version = "{\"html\":\"<p>mine now</p>\"}";
    for (String caller : List.of(admin(), workspaceAgent, desk, deskAdmin)) {
      String message =
          as(caller)
              .body(version)
              .post(DOOR + "/" + id + "/versions")
              .then()
              .statusCode(403)
              .extract()
              .path("message");
      assertTrue(message.startsWith(PitchController.ONLY_THE_PITCH_AGENT), message);
    }
    as(otherProjectUiBuilder).body(version).post(DOOR + "/" + id + "/versions").then().statusCode(403);
    // The UI builder may not change the desk's pitch either.
    String desks = deskPitch();
    as(uiBuilder).body(version).post(DOOR + "/" + desks + "/versions").then().statusCode(403);
    as(uiBuilder)
        .body(version)
        .post(DOOR + "/" + UUID.randomUUID() + "/versions")
        .then()
        .statusCode(404);
    assertEquals(
        1, as(admin()).get(DOOR + "/" + id).then().statusCode(200).extract().<Integer>path("latestVersion"));
  }

  // --- archive -----------------------------------------------------------------------------------

  @Test
  public void anArchivedPitchTakesNoVersionAndTheArchiveIsIdempotent() {
    String id = create(route("archive"), "a.html");
    announced.reset();
    JsonPath archived =
        as(uiBuilder).post(DOOR + "/" + id + "/archive").then().statusCode(200).extract().jsonPath();
    String at = archived.getString("archivedAt");
    assertNotNull(at);
    assertEquals(
        at,
        as(uiBuilder)
            .post(DOOR + "/" + id + "/archive")
            .then()
            .statusCode(200)
            .extract()
            .path("archivedAt"),
        "a second archive changes nothing");
    List<PitchChanged> events = announced.of(id);
    assertEquals(1, events.size(), "announced once");
    assertEquals("ARCHIVED", events.get(0).change());
    assertEquals(1, events.get(0).version());

    as(uiBuilder)
        .body("{\"html\":\"<p>late</p>\"}")
        .post(DOOR + "/" + id + "/versions")
        .then()
        .statusCode(409);
    // Nothing is deleted: the archived pitch still reads.
    as(workspaceAgent).get(DOOR + "/" + id + "/versions/latest").then().statusCode(200);
  }

  @Test
  public void theAgentAnAdminOrTheDeskAdminArchives() {
    String byAdmin = create(route("archivers"), "a.html");
    String byDeskAdmin = create(route("archivers"), "b.html");
    String refused = create(route("archivers"), "c.html");
    as(admin()).post(DOOR + "/" + byAdmin + "/archive").then().statusCode(200);
    as(deskAdmin).post(DOOR + "/" + byDeskAdmin + "/archive").then().statusCode(200);
    for (String caller : List.of(workspaceAgent, desk, otherProjectUiBuilder)) {
      as(caller).post(DOOR + "/" + refused + "/archive").then().statusCode(403);
    }
    assertNull(
        as(admin()).get(DOOR + "/" + refused).then().statusCode(200).extract().path("archivedAt"));
  }

  // --- reads -------------------------------------------------------------------------------------

  @Test
  public void everyProjectReaderReadsAndNoOtherProjectDoes() {
    String id = create(route("readers"), "a.html");
    String service = ContractBearers.caller("dev-qits-maintenance", "qits:system");
    for (String caller : List.of(admin(), service, workspaceAgent, desk, deskAdmin, uiBuilder)) {
      as(caller).get(DOOR + "/" + id).then().statusCode(200);
      as(caller).get(DOOR + "/" + id + "/versions/latest").then().statusCode(200);
      as(caller).queryParam("project", projectId).get(DOOR).then().statusCode(200);
    }
    as(otherProjectUiBuilder).get(DOOR + "/" + id).then().statusCode(403);
    as(otherProjectUiBuilder).get(DOOR + "/" + id + "/versions/1").then().statusCode(403);
    as(otherProjectUiBuilder).queryParam("project", projectId).get(DOOR).then().statusCode(403);
    as(admin()).get(DOOR + "/" + UUID.randomUUID()).then().statusCode(404);
    as(admin()).get(DOOR + "/not-a-pitch").then().statusCode(404);
    as(admin()).get(DOOR).then().statusCode(400);
  }

  @Test
  public void theListingFiltersAndPutsTheNewestChangeFirst() {
    String first = create(appRepoId, route("filters"), "a.html");
    String second = create(appRepoId, route("filters"), "b.html");
    String elsewhere = create("pitch-other", route("filters"), "a.html");
    String otherRoute = create(appRepoId, route("filters-two"), "a.html");
    as(uiBuilder)
        .body("{\"html\":\"<p>bumped</p>\"}")
        .post(DOOR + "/" + first + "/versions")
        .then()
        .statusCode(201);

    JsonPath byRoute = list(Map.of("route", route("filters")));
    assertEquals(List.of(first, elsewhere, second), byRoute.getList("pitches.id"));
    assertEquals(2, byRoute.getInt("pitches[0].latestVersion"));
    assertNull(byRoute.get("pitches[0].html"), "a listing carries no document");
    assertEquals("pitch-other", byRoute.getString("pitches[1].repositoryName"));

    assertEquals(
        List.of(first, second),
        list(Map.of("route", route("filters"), "repository", "pitch-app")).getList("pitches.id"));
    assertEquals(
        List.of(elsewhere),
        list(Map.of("route", route("filters"), "repository", otherRepoId)).getList("pitches.id"));
    assertEquals(
        List.of(first, elsewhere),
        list(Map.of("route", route("filters"), "screenshot", "a.html")).getList("pitches.id"));
    assertEquals(
        List.of(otherRoute), list(Map.of("route", route("filters-two"))).getList("pitches.id"));
    assertEquals(
        List.of(first, elsewhere, second),
        list(Map.of("route", route("filters"), "agent", "ui-builder")).getList("pitches.id"));
    assertTrue(
        list(Map.of("route", route("filters"), "agent", "desk")).getList("pitches.id").isEmpty());
    as(admin())
        .queryParam("project", projectId)
        .queryParam("repository", "no-such-repository")
        .get(DOOR)
        .then()
        .statusCode(404);
  }

  @Test
  public void anArchivedPitchListsOnlyWhenAskedFor() {
    String kept = create(route("archived-filter"), "a.html");
    String archived = create(route("archived-filter"), "b.html");
    as(uiBuilder).post(DOOR + "/" + archived + "/archive").then().statusCode(200);

    assertEquals(
        List.of(kept), list(Map.of("route", route("archived-filter"))).getList("pitches.id"));
    assertEquals(
        List.of(kept),
        list(Map.of("route", route("archived-filter"), "archived", "false"))
            .getList("pitches.id"));
    JsonPath all = list(Map.of("route", route("archived-filter"), "archived", "true"));
    assertEquals(List.of(archived, kept), all.getList("pitches.id"));
    assertNotNull(all.get("pitches[0].archivedAt"));
    assertFalse(all.getList("pitches.id").isEmpty());
  }

  private JsonPath list(Map<String, String> query) {
    return as(workspaceAgent)
        .queryParam("project", projectSlug)
        .queryParams(query)
        .get(DOOR)
        .then()
        .statusCode(200)
        .extract()
        .jsonPath();
  }
}
