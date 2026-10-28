package eu.wohlben.qits.projects.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

import eu.wohlben.qits.projects.control.ProjectService;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import eu.wohlben.qits.projects.persistence.ProjectRepository;
import eu.wohlben.qits.projects.persistence.RepositoryRepository;
import eu.wohlben.qits.projects.security.NoDevUserProfile;
import eu.wohlben.qits.projects.testsupport.GitFixtures;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code GET /projects/api/repositories} — the flat catalogue qits-ci's trigger engine enumerates
 * the platform with.
 *
 * <p><b>Why it exists and what that makes load-bearing here.</b> The engine used to walk the git
 * host's internal {@code GET /git}, which answers storage ids and nothing else. This route is what
 * replaces it, so two things are the contract rather than details: every entry carries the public
 * identity ({@code projectId} + {@code name}), and a read that fails is a 5xx. An empty list means
 * "there is nothing to build" to the caller, and it acts on that.
 *
 * <p>The dev-user fallback is blanked so the role matrix is the deployed posture: no header is
 * anonymous, not the synthetic {@code dev} identity that holds every platform role.
 */
@QuarkusTest
@TestProfile(RepositoryCatalogueTest.DeployedPosture.class)
public class RepositoryCatalogueTest {

  /**
   * The deployed posture, plus the alias table that can be made to fail. Both belong to the two
   * classes that arm it — this one and {@link RepositoryNameCutoverTest} — and to no other: a
   * globally enabled alternative would sit in the path of every repository read in the suite, armed
   * or not. The two share this one profile rather than holding one each, because a profile is a
   * whole second app boot whose ~125 MB of metaspace stays retained for the rest of the run
   * (qits-965, the test-profile budget rule); each disarms every knob before each test, so neither
   * class can spend or inherit the other's armed failure.
   */
  public static class DeployedPosture implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("qits.auth.forward.dev-user", "");
    }

    @Override
    public Set<Class<?>> getEnabledAlternatives() {
      return Set.of(ConnectionLosingRepositoryNames.class);
    }
  }

  @Inject ConnectionLosingRepositoryNames names;

  @Inject ProjectRepository projects;

  @Inject RepositoryRepository repositories;

  @Inject ProjectService projectService;

  @BeforeEach
  public void healthy() {
    names.loseTheConnection(0);
    names.loseTheConnectionNaming(0);
    names.failNamingOutright(0);
  }

  /** A browser session as the edge forwards it. */
  private RequestSpecification session() {
    return given()
        .contentType(ContentType.JSON)
        .header("X-Qits-User", "alice")
        .header("X-Qits-Roles", "qits:admin");
  }

  /** qits-ci as it presents itself: a machine bearer's roles, and no browser session. */
  private RequestSpecification machine() {
    return given()
        .contentType(ContentType.JSON)
        .header("X-Qits-User", "dev-qits-ci")
        .header("X-Qits-Roles", "qits:system");
  }

  private String createProject(String name) {
    return session()
        .body(new ProjectController.CreateProjectRequest(name, null, null, null, ProjectRequests.DNS))
        .when()
        .post("/projects/api/projects")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .extract()
        .path("project.id");
  }

  private String createRepository(String projectId, String name) {
    return session()
        .body(
            new ProjectController.CreateProjectRepositoryRequest(
                null, name, RepositoryArchetype.SERVICE, null))
        .when()
        .post("/projects/api/projects/" + projectId + "/repositories")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .extract()
        .path("repository.id");
  }

  /**
   * A repository row that owns no alias. Every creation path registers one in the same transaction
   * as the row, so the only way to reach this state is to write it — which is exactly why the route
   * has to say what it does with one.
   */
  private String unnamedRepository(String projectId) {
    String id = UUID.randomUUID().toString();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Repository row = new Repository();
              row.id = id;
              row.mainBranch = "trunk";
              row.archetype = RepositoryArchetype.SERVICE;
              row.project = projects.findByIdOptional(projectId).orElseThrow();
              repositories.persist(row);
            });
    return id;
  }

  @Test
  public void theCatalogueCarriesEveryRepositorysPublicCoordinates() {
    String projectId = createProject("Catalogue Shape");
    String repoId = createRepository(projectId, "checkout");

    machine()
        .when()
        .get("/projects/api/repositories")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("repositories.find { it.id == '" + repoId + "' }.projectId", equalTo(projectId))
        .body("repositories.find { it.id == '" + repoId + "' }.name", equalTo("checkout"))
        .body("repositories.find { it.id == '" + repoId + "' }.mainBranch", equalTo("main"))
        // The project's wrapper is a repository like any other and is listed too, under its own
        // <slug>-<slug> name.
        .body("repositories.name", hasItem("catalogue-shape-catalogue-shape"));
  }

  /**
   * {@code declared} is the project listing's answer, row for row (qits-1695): qits-maintenance
   * reads it here to stop bumping a repository its wrapper dropped. The create route writes the
   * wrapper entry, so that component is declared; a row registered past it — the drift a hand-edited
   * wrapper leaves — is a component no entry names, so it is not; and the wrapper itself always is.
   */
  @Test
  public void theCatalogueSaysWhichRowsTheirWrapperDeclares() throws Exception {
    String projectId = createProject("Catalogue Declared");
    String member = createRepository(projectId, "member");
    String stray =
        projectService
            .createRepositoryUnderProject(
                projectId, GitFixtures.path("testing-repo.git"), RepositoryArchetype.LIBRARY)
            .id;

    machine()
        .when()
        .get("/projects/api/repositories")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("repositories.find { it.id == '" + member + "' }.declared", equalTo(true))
        .body("repositories.find { it.id == '" + stray + "' }.declared", equalTo(false))
        .body(
            "repositories.find { it.name == 'catalogue-declared-catalogue-declared' }.declared",
            equalTo(true));

    // The same reading as the project listing, not merely a similar one.
    session()
        .when()
        .get("/projects/api/projects/" + projectId + "/repositories")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("entries.find { it.repository.id == '" + member + "' }.declared", equalTo(true))
        .body("entries.find { it.repository.id == '" + stray + "' }.declared", equalTo(false));
  }

  /** A row with no name is a fact, not a gap: it is listed, and the caller is what skips it. */
  @Test
  public void aRepositoryWithNoRegisteredNameIsListedWithANullName() {
    String projectId = createProject("Catalogue Unnamed");
    String unnamed = unnamedRepository(projectId);

    var entries =
        machine()
            .when()
            .get("/projects/api/repositories")
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .body("repositories.id", hasItem(unnamed))
            .extract()
            .jsonPath()
            .getList("repositories", Map.class);

    Map<?, ?> row =
        entries.stream()
            .filter(e -> unnamed.equals(e.get("id")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("the unnamed row was dropped from the catalogue"));
    Assertions.assertNull(row.get("name"), "an unnamed row must be listed, with a null name");
    Assertions.assertEquals(projectId, row.get("projectId"));
    Assertions.assertEquals("trunk", row.get("mainBranch"));
  }

  /** Sorted by project then name, so two reads of an unchanged database agree. */
  @Test
  public void theListingIsStablySorted() {
    String projectId = createProject("Catalogue Order");
    createRepository(projectId, "zulu");
    createRepository(projectId, "alpha");

    List<Map> entries =
        machine()
            .when()
            .get("/projects/api/repositories")
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .extract()
            .jsonPath()
            .getList("repositories", Map.class);

    for (int i = 1; i < entries.size(); i++) {
      String previous = key(entries.get(i - 1));
      String current = key(entries.get(i));
      Assertions.assertTrue(
          previous.compareTo(current) <= 0,
          "the catalogue is out of order at " + i + ": " + previous + " before " + current);
    }
  }

  private static String key(Map<?, ?> entry) {
    // A null name sorts last within its project, which is what the tilde stands in for here.
    return entry.get("projectId") + " " + (entry.get("name") == null ? "~" : entry.get("name"));
  }

  /**
   * Both kinds of caller, and the reason this test exists: a method-level {@code @RolesAllowed}
   * REPLACES the controller's class-level {@code qits:admin} rather than adding to it, so naming
   * only {@code qits:system} here would be a 403 for every browser — the live defect two sibling
   * routes already had.
   */
  @Test
  public void aMachineAndABrowserSessionBothReadTheCatalogue() {
    createProject("Catalogue Roles");

    machine()
        .when()
        .get("/projects/api/repositories")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("repositories.size()", not(equalTo(0)));

    session()
        .when()
        .get("/projects/api/repositories")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("repositories.size()", not(equalTo(0)));
  }

  /** No header at all is anonymous: the challenge door, 401. */
  @Test
  public void anAnonymousReadIsRefused() {
    given()
        .when()
        .get("/projects/api/repositories")
        .then()
        .statusCode(Response.Status.UNAUTHORIZED.getStatusCode());
  }

  /** A named caller holding neither role authenticates and is then refused: the roles door, 403. */
  @Test
  public void aNamedCallerWithoutTheRoleIsRefused() {
    given()
        .header("X-Qits-User", "alice")
        .header("X-Qits-Roles", "qits:user")
        .when()
        .get("/projects/api/repositories")
        .then()
        .statusCode(Response.Status.FORBIDDEN.getStatusCode());
  }

  /** The catalogue holds through a postgres cutover rather than answering a shorter list. */
  @Test
  public void theCatalogueHoldsThroughACutover() {
    String projectId = createProject("Catalogue Cutover");
    String repoId = createRepository(projectId, "checkout");

    names.loseTheConnectionNaming(1);
    machine()
        .when()
        .get("/projects/api/repositories")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("repositories.find { it.id == '" + repoId + "' }.name", equalTo("checkout"));
    Assertions.assertEquals(
        0, names.unspentNaming(), "the armed failure was never reached — the read did not go through");
  }

  /**
   * A read that fails is a 5xx and never an empty list. This is the arm that matters most to a
   * caller: an empty catalogue is an answer it acts on, so a failure that degraded into one would
   * quietly stop every pipeline on the platform instead of reporting itself.
   */
  @Test
  public void aFailedReadIsAServerErrorAndNeverAnEmptyList() {
    String projectId = createProject("Catalogue Failure");
    createRepository(projectId, "checkout");

    names.failNamingOutright(1);
    machine()
        .when()
        .get("/projects/api/repositories")
        .then()
        .statusCode(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode());
    Assertions.assertEquals(0, names.unspentNaming(), "the armed failure was never reached");
  }
}
