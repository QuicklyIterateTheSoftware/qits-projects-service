package eu.wohlben.qits.projects.refinementhost;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.entities.api.TestCriteria;
import eu.wohlben.qits.entities.api.WorkRequests;
import eu.wohlben.qits.projects.api.ProjectController;
import eu.wohlben.qits.projects.api.ProjectRequests;
import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

/**
 * The refinement containers stay retired (qits-1316): every room is a session of its project's
 * front desk, the container classes do not come back, the container-only doors answer 404, the
 * container rooms' columns and DTO fields are gone, and so are the container config keys.
 *
 * <p>{@code ensure-container} is NOT in the removed list: qits-platform-access-cli's pact still calls
 * it ({@code qits work refinement open}) and pins its {@code technicalProcessId}, so it stays as the
 * alias of {@code ensure} until that consumer releases a pact without it.
 */
@QuarkusTest
class RefinementContainersRetiredTest {

  @Inject
  @DataSource("projects")
  AgroalDataSource projects;

  @Test
  void theRetiredClassesAreGone() {
    for (String name :
        List.of(
            "eu.wohlben.qits.projects.refinementhost.RefinementContainerFactory",
            "eu.wohlben.qits.projects.refinementhost.RefinementRuntime",
            "eu.wohlben.qits.projects.containershost.ContainersRefinementRuntime",
            "eu.wohlben.qits.projects.refinementhost.RefinementCommissions",
            "eu.wohlben.qits.projects.refinementhost.RefinementCredentials",
            "eu.wohlben.qits.projects.idphost.IdpRefinementCredentials",
            "eu.wohlben.qits.projects.refinementhost.RefinementCommissionReconcile",
            "eu.wohlben.qits.projects.refinementhost.RefinementDaemonRegistry",
            "eu.wohlben.qits.projects.refinementhost.RefinementControlSocket",
            "eu.wohlben.qits.projects.refinementhost.RefinementControlSocketAccess",
            "eu.wohlben.qits.projects.refinementhost.RefinementTunnels",
            "eu.wohlben.qits.projects.refinementhost.RefinementStreamRoute",
            "eu.wohlben.qits.projects.refinementhost.RefinementProxyRoute",
            "eu.wohlben.qits.projects.refinementhost.RefinementPaths",
            "eu.wohlben.qits.projects.refinementhost.RefinementStaleImageSweep",
            "eu.wohlben.qits.projects.refinementhost.RefinementDrift",
            "eu.wohlben.qits.projects.refinementhost.RefinementAgentEntities",
            "eu.wohlben.qits.projects.refinementhost.RefinementMessageCodec",
            "eu.wohlben.qits.projects.refinementhost.RetiredRefinementImageVersionKey")) {
      assertThrows(ClassNotFoundException.class, () -> Class.forName(name), name);
    }
  }

  @Test
  void theContainerDoorsAnswer404AndTheRoomCarriesNoContainerField() {
    long room = openRoom("Retired Doors");

    given().when().post("/projects/api/refinements/" + room + "/stop-container").then().statusCode(404);
    given()
        .when()
        .post("/projects/api/refinements/" + room + "/recreate-container")
        .then()
        .statusCode(404);
    given().when().get("/projects/api/refinements/" + room + "/active-process").then().statusCode(404);

    var body =
        given().when().get("/projects/api/refinements/" + room).then().statusCode(200);
    body.body("refinement.deskBase", notNullValue());
    for (String field :
        List.of(
            "branch",
            "parent",
            "clean",
            "ahead",
            "behind",
            "conflictsWithParent",
            "daemonConnectedAt",
            "daemonVersion",
            "daemonOutdated",
            "pinnedDaemonVersion",
            "daemonVersionStale")) {
      body.body("refinement", not(hasKey(field)));
    }
  }

  @Test
  void theContainerColumnsAreGone() throws Exception {
    try (Connection connection = projects.getConnection();
        Statement sql = connection.createStatement();
        ResultSet rows =
            sql.executeQuery(
                "select count(*) from information_schema.columns where table_name = 'refinement'"
                    + " and column_name in ('branch', 'parent', 'commissioned_client_id',"
                    + " 'commissioned_client_secret')")) {
      assertTrue(rows.next());
      assertEquals(0, rows.getInt(1));
    }
  }

  @Test
  void theContainerConfigKeysAreGone() {
    var config = ConfigProvider.getConfig();
    for (String key :
        List.of(
            "qits.projects.refinement-volume-prefix",
            "qits.projects.refinement.drift-staleness-ms",
            "qits.projects.refinement.stale-activity-ttl-ms",
            "qits.projects.refinement-stale-quiet-window",
            "qits.projects.refinement-stale-sweep-interval",
            "qits.projects.refinement-image-version-override",
            "qits.projects.containers.ensure-patience")) {
      assertTrue(config.getOptionalValue(key, String.class).isEmpty(), key);
    }
    // The one that stays: the cap on a project's running rooms.
    assertTrue(
        config.getOptionalValue("qits.projects.refinement-sessions-max", Integer.class).isPresent());
  }

  private long openRoom(String name) {
    String projectId =
        given()
            .contentType(ContentType.JSON)
            .body(
                new ProjectController.CreateProjectRequest(
                    name, null, null, null, ProjectRequests.DNS))
            .when()
            .post("/projects/api/projects")
            .then()
            .statusCode(200)
            .extract()
            .path("project.id");
    String epicId =
        TestCriteria.give(
            WorkRequests.create(
                    WorkRequests.map(
                        "archetype", "EPIC",
                        "project", projectId,
                        "title", name,
                        "description", "A draft."))
                .path("id"));
    Number id =
        given()
            .contentType(ContentType.JSON)
            .body(Map.of())
            .when()
            .post("/projects/api/work/" + epicId + "/refinement")
            .then()
            .statusCode(200)
            .extract()
            .path("refinement.id");
    return id.longValue();
  }
}
