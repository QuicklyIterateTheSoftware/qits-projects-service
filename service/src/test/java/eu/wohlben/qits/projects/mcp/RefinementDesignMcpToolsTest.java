package eu.wohlben.qits.projects.mcp;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.api.ProjectController;
import eu.wohlben.qits.projects.api.ProjectRequests;
import io.quarkiverse.mcp.server.ToolResponse;
import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkiverse.mcp.server.test.McpAssured.McpStreamableTestClient;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import io.vertx.core.MultiMap;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The design MCP surface: what an agent on the other end of the socket can see and do. It reads
 * the epic's designs and proposes revisions; making one active is a person's act over REST, and
 * nothing here can reach it.
 */
@QuarkusTest
@TestProfile(McpStatelessTestProfile.class)
public class RefinementDesignMcpToolsTest {

  private static final String DOC =
      "<!doctype html><html><body style=\"margin:0\">Checkout</body></html>";

  private static RequestSpecification authenticated() {
    return given()
        .header("X-Qits-User", "mcp-test")
        .header("X-Qits-Roles", "qits:admin,qits:system,qits-platform:system");
  }

  // --- Fixtures over REST ---------------------------------------------------

  private String createProject(String name) {
    return authenticated()
        .contentType(ContentType.JSON)
        .body(
            new ProjectController.CreateProjectRequest(name, null, null, null, ProjectRequests.DNS))
        .when()
        .post("/projects/api/projects")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .extract()
        .path("project.id");
  }

  private String createEpic(String projectId, String title) {
    return authenticated()
        .contentType(ContentType.JSON)
        .body(Map.of("title", title, "description", "A draft."))
        .when()
        .post("/projects/api/projects/" + projectId + "/epics")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .extract()
        .path("epic.id");
  }

  private long openRefinement(String epicId) {
    Number id =
        authenticated()
            .contentType(ContentType.JSON)
            .body(Map.of("epicId", epicId))
            .when()
            .post("/projects/api/refinements")
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .extract()
            .path("refinement.id");
    return id.longValue();
  }

  private String capture(long refinementId, String title) {
    return authenticated()
        .contentType(ContentType.JSON)
        .body(Map.of("title", title, "html", DOC, "truncated", false))
        .when()
        .post("/projects/api/refinements/" + refinementId + "/designs")
        .then()
        .statusCode(Response.Status.CREATED.getStatusCode())
        .extract()
        .path("id");
  }

  // --- MCP plumbing ---------------------------------------------------------

  private static String text(ToolResponse response) {
    return response.content().stream()
        .map(c -> c.asText().text())
        .collect(Collectors.joining("\n"));
  }

  private McpStreamableTestClient client(String projectId) {
    return McpAssured.newStreamableClient()
        .setStateless()
        .setMcpPath("/projects/mcp")
        .setAdditionalHeaders(
            msg -> {
              MultiMap headers = MultiMap.caseInsensitiveMultiMap();
              if (projectId != null) {
                headers.add(ProjectScope.PROJECT_HEADER, projectId);
              }
              return headers;
            })
        .build()
        .connect();
  }

  private void call(String projectId, String tool, Map<String, Object> args, Check check) {
    client(projectId).when().toolsCall(tool, args, check::accept).thenAssertResults();
  }

  private interface Check {
    void accept(ToolResponse response);
  }

  /** The {@code "id"} field of a tool's JSON result — the first one, which is the row's own. */
  private static String idIn(String json) {
    int at = json.indexOf("\"id\"");
    int open = json.indexOf('"', json.indexOf(':', at) + 1);
    return json.substring(open + 1, json.indexOf('"', open + 1));
  }

  // --- The surface ----------------------------------------------------------

  @Test
  public void exposesTheThreeDesignTools() {
    String projectId = createProject("Design Surface");
    client(projectId)
        .when()
        .toolsList(
            page -> {
              var names = page.tools().stream().map(t -> t.name()).toList();
              assertTrue(names.contains("list_designs"), names.toString());
              assertTrue(names.contains("get_design"), names.toString());
              assertTrue(names.contains("put_design"), names.toString());
            })
        .thenAssertResults();
  }

  // --- Scoping --------------------------------------------------------------

  @Test
  public void anEpicWithNoRefinementOpenReadsAsNoRefinement() {
    String projectId = createProject("Design Unopened");
    String epicId = createEpic(projectId, "Never refined");

    call(
        projectId,
        "list_designs",
        Map.of("epicId", epicId),
        response -> {
          assertTrue(response.isError(), "there is nothing to list");
          assertTrue(text(response).contains("No refinement is open for epic"), text(response));
        });
  }

  @Test
  public void anEpicInAnotherProjectReadsTheSameWay() {
    String owner = createProject("Design Owner");
    String epicId = createEpic(owner, "Owned");
    openRefinement(epicId);
    String stranger = createProject("Design Stranger");

    call(
        stranger,
        "list_designs",
        Map.of("epicId", epicId),
        response -> {
          assertTrue(response.isError(), "cross-project access must be refused");
          // The same message as an epic with no refinement: nothing says what elsewhere holds.
          assertTrue(text(response).contains("No refinement is open for epic"), text(response));
        });
  }

  // --- Reading and proposing ------------------------------------------------

  @Test
  public void readsADesignInFull() {
    String projectId = createProject("Design Read");
    String epicId = createEpic(projectId, "Checkout epic");
    long refinementId = openRefinement(epicId);
    String designId = capture(refinementId, "Checkout");

    call(
        projectId,
        "list_designs",
        Map.of("epicId", epicId),
        response -> {
          assertFalse(response.isError(), text(response));
          String body = text(response);
          assertTrue(body.contains(designId), body);
          assertTrue(body.contains("\"version\""), body);
          assertFalse(body.contains("<!doctype"), "a listing must not carry documents: " + body);
        });

    call(
        projectId,
        "get_design",
        Map.of("epicId", epicId, "designId", designId),
        response -> {
          assertFalse(response.isError(), text(response));
          assertTrue(text(response).contains("Checkout</body>"), text(response));
        });
  }

  @Test
  public void writesADesignAndThenRewritesItInPlace() {
    String projectId = createProject("Design Write");
    String epicId = createEpic(projectId, "Roomier epic");
    long refinementId = openRefinement(epicId);
    String revised = "<!doctype html><html><body>Checkout, roomier</body></html>";

    String[] designId = new String[1];
    call(
        projectId,
        "put_design",
        Map.of("epicId", epicId, "title", "Roomier checkout", "html", revised),
        response -> {
          assertFalse(response.isError(), text(response));
          String body = text(response);
          assertTrue(body.contains("\"version\" : 0") || body.contains("\"version\":0"), body);
          designId[0] = idIn(body);
        });

    // A rewrite in place: same id, next version. Nobody accepted anything in between.
    call(
        projectId,
        "put_design",
        Map.of(
            "epicId", epicId,
            "title", "Roomier checkout",
            "html", revised + "<!-- again -->",
            "designId", designId[0],
            "version", 0),
        response -> {
          assertFalse(response.isError(), text(response));
          String body = text(response);
          assertTrue(body.contains(designId[0]), body);
          assertTrue(body.contains("\"version\" : 1") || body.contains("\"version\":1"), body);
        });
  }

  @Test
  public void aRewriteCarryingAStaleVersionIsRefused() {
    String projectId = createProject("Design Stale");
    String epicId = createEpic(projectId, "Contested epic");
    long refinementId = openRefinement(epicId);
    String designId = capture(refinementId, "Checkout");

    // Somebody else writes first, so version 0 is no longer the current one.
    call(
        projectId,
        "put_design",
        Map.of(
            "epicId", epicId,
            "title", "Checkout",
            "html", "<!doctype html><html><body>First</body></html>",
            "designId", designId,
            "version", 0),
        response -> assertFalse(response.isError(), text(response)));

    call(
        projectId,
        "put_design",
        Map.of(
            "epicId", epicId,
            "title", "Checkout",
            "html", "<!doctype html><html><body>Second</body></html>",
            "designId", designId,
            "version", 0),
        response -> {
          assertTrue(response.isError(), "a stale write must be refused, never merged");
          assertTrue(text(response).contains("written since you read it"), text(response));
        });
  }
}
