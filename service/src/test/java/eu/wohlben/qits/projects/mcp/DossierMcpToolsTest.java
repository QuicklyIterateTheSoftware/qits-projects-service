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
 * The dossier MCP surface: what an agent on the other end of the socket can see and do.
 *
 * <p>The case worth having here is the <b>stale write</b>. Nobody accepts a write on this route, so
 * the version is the only thing standing between an agent and a person's paragraph; the refusal has
 * to be a readable message the model can act on rather than a protocol error that kills the turn.
 */
@QuarkusTest
@TestProfile(McpStatelessTestProfile.class)
public class DossierMcpToolsTest {

  private static RequestSpecification authenticated() {
    return given()
        .header("X-Qits-User", "mcp-test")
        .header("X-Qits-Roles", "qits:admin,qits:system,qits-platform:system");
  }

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

  private static String idIn(String json) {
    int at = json.indexOf("\"id\"");
    int open = json.indexOf('"', json.indexOf(':', at) + 1);
    return json.substring(open + 1, json.indexOf('"', open + 1));
  }

  @Test
  public void exposesTheDossierTools() {
    String projectId = createProject("Dossier Surface");
    client(projectId)
        .when()
        .toolsList(
            page -> {
              var names = page.tools().stream().map(t -> t.name()).toList();
              assertTrue(names.contains("list_dossier_pages"), names.toString());
              assertTrue(names.contains("get_dossier_page"), names.toString());
              assertTrue(names.contains("put_dossier_page"), names.toString());
              assertTrue(names.contains("move_dossier_page"), names.toString());
              assertTrue(names.contains("remove_dossier_page"), names.toString());
            })
        .thenAssertResults();
  }

  @Test
  public void anEpicInAnotherProjectIsNotFound() {
    String owner = createProject("Dossier Owner");
    String epicId = createEpic(owner, "Owned");
    String stranger = createProject("Dossier Stranger");

    call(
        stranger,
        "list_dossier_pages",
        Map.of("epicId", epicId),
        response -> {
          assertTrue(response.isError(), "cross-project access must be refused");
          assertTrue(text(response).contains("Epic not found"), text(response));
        });
  }

  @Test
  public void writesAPageListsItWithoutItsTextAndReadsItInFull() {
    String projectId = createProject("Dossier Write");
    String epicId = createEpic(projectId, "Checkout epic");

    String[] pageId = new String[1];
    call(
        projectId,
        "put_dossier_page",
        Map.of("epicId", epicId, "title", "The claim loop", "body", "# The claim loop\n\nturns."),
        response -> {
          assertFalse(response.isError(), text(response));
          pageId[0] = idIn(text(response));
        });

    call(
        projectId,
        "list_dossier_pages",
        Map.of("epicId", epicId),
        response -> {
          String body = text(response);
          assertTrue(body.contains("the-claim-loop"), body);
          // A listing must not pull the whole dossier back.
          assertFalse(body.contains("turns."), "a listing carries no text: " + body);
        });

    call(
        projectId,
        "get_dossier_page",
        Map.of("epicId", epicId, "pageId", pageId[0]),
        response -> assertTrue(text(response).contains("turns."), text(response)));
  }

  @Test
  public void aRewriteCarryingAStaleVersionIsRefusedReadably() {
    String projectId = createProject("Dossier Stale");
    String epicId = createEpic(projectId, "Contested epic");

    String[] pageId = new String[1];
    call(
        projectId,
        "put_dossier_page",
        Map.of("epicId", epicId, "title", "The claim loop", "body", "first"),
        response -> pageId[0] = idIn(text(response)));

    call(
        projectId,
        "put_dossier_page",
        Map.of(
            "epicId", epicId,
            "title", "The claim loop",
            "body", "second",
            "pageId", pageId[0],
            "version", 0),
        response -> assertFalse(response.isError(), text(response)));

    call(
        projectId,
        "put_dossier_page",
        Map.of(
            "epicId", epicId,
            "title", "The claim loop",
            "body", "third",
            "pageId", pageId[0],
            "version", 0),
        response -> {
          assertTrue(response.isError(), "a stale write must be refused, never merged");
          assertTrue(text(response).contains("written since you read it"), text(response));
        });
  }

  @Test
  public void pagesAreMovedAndRemoved() {
    String projectId = createProject("Dossier Order");
    String epicId = createEpic(projectId, "Ordered epic");

    String[] second = new String[1];
    call(
        projectId,
        "put_dossier_page",
        Map.of("epicId", epicId, "title", "One", "body", "one"),
        response -> assertFalse(response.isError(), text(response)));
    call(
        projectId,
        "put_dossier_page",
        Map.of("epicId", epicId, "title", "Two", "body", "two"),
        response -> second[0] = idIn(text(response)));

    call(
        projectId,
        "move_dossier_page",
        Map.of("epicId", epicId, "pageId", second[0], "position", 0),
        response -> assertTrue(text(response).contains("\"position\":0"), text(response)));

    call(
        projectId,
        "remove_dossier_page",
        Map.of("epicId", epicId, "pageId", second[0]),
        response -> assertFalse(response.isError(), text(response)));

    call(
        projectId,
        "list_dossier_pages",
        Map.of("epicId", epicId),
        response -> assertFalse(text(response).contains("\"two\""), text(response)));
  }
}
