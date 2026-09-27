package eu.wohlben.qits.projects.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.testsupport.RecordingWorkspaceAgentDispatch;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>The doors qits-399 removed stay removed</b>, pressed as an operator against rows that exist.
 *
 * <p>The SPA stopped calling five routes (qits-frontend 072512f: it edits through {@code POST
 * /entities/transition}, dispatches through {@code POST /entities/{id}/dispatch} and opens a room
 * through {@code POST /entities/{id}/refinement}), and each of them was deleted rather than left
 * answering nobody. What each answers now is what JAX-RS makes of a request no resource method
 * serves, and there are two answers, both asserted exactly:
 *
 * <ul>
 *   <li><b>405</b> where the PATH still has a resource method and only the method went: {@code PUT
 *       /epics/{id}} and {@code PUT /tickets/{id}} — the GET and DELETE on those paths still answer.
 *   <li><b>404</b> where no resource method matches the path at all: {@code POST
 *       /epics/{id}/dispatch-agent}, {@code POST /tickets/{id}/dispatch-agent} — and {@code POST
 *       /refinements}, although {@code RefinementController} is still rooted at {@code /refinements}:
 *       RESTEasy Reactive matches the class, finds no method on its bare root for any verb, and
 *       answers 404 rather than 405 (measured, not assumed).
 * </ul>
 *
 * <p>The ids name real rows on purpose, so a 404 here cannot be the entity's own "not found" — and
 * the rows are read back afterwards to show nothing was written: no retitle landed, no dispatch was
 * asked of the port and no refinement room was opened.
 *
 * <p>No profile of its own: the default application every door suite shares.
 */
@QuarkusTest
public class RetiredEntityDoorsTest {

  @Inject RecordingWorkspaceAgentDispatch dispatch;

  @BeforeEach
  void resetThePort() {
    dispatch.reset();
  }

  private static RequestSpecification asAdmin() {
    return given()
        .contentType(ContentType.JSON)
        .header("X-Qits-User", "mallory")
        .header("X-Qits-Roles", "qits:admin");
  }

  private static String create(String path, Map<String, ?> body, String idPath) {
    return asAdmin()
        .body(body)
        .when()
        .post(path)
        .then()
        .statusCode(200)
        .extract()
        .path(idPath);
  }

  @Test
  public void thePerEntityPutsAnswer405AndWriteNothing() {
    String projectId = project("Retired Puts");
    String epicId =
        create("/projects/api/projects/" + projectId + "/epics", Map.of("title", "A plan"), "epic.id");
    String ticketId = ticket(projectId, "A ticket");

    asAdmin()
        .body(Map.of("title", "Renamed through a door that is gone", "description", "x"))
        .when()
        .put("/projects/api/epics/" + epicId)
        .then()
        .statusCode(405);
    asAdmin()
        .body(Map.of("title", "Renamed through a door that is gone"))
        .when()
        .put("/projects/api/tickets/" + ticketId)
        .then()
        .statusCode(405);

    asAdmin()
        .when()
        .get("/projects/api/epics/" + epicId)
        .then()
        .statusCode(200)
        .body("epic.title", equalTo("A plan"));
    asAdmin()
        .when()
        .get("/projects/api/tickets/" + ticketId)
        .then()
        .statusCode(200)
        .body("ticket.title", equalTo("A ticket"));
  }

  @Test
  public void theTwoDispatchAgentDoorsAnswer404AndDispatchNothing() {
    String projectId = project("Retired Dispatch");
    String epicId =
        create("/projects/api/projects/" + projectId + "/epics", Map.of("title", "A plan"), "epic.id");
    String ticketId = ticket(projectId, "A ticket");

    Response epicDoor = asAdmin().when().post("/projects/api/epics/" + epicId + "/dispatch-agent");
    Response ticketDoor =
        asAdmin().when().post("/projects/api/tickets/" + ticketId + "/dispatch-agent");

    epicDoor.then().statusCode(404);
    ticketDoor.then().statusCode(404);
    assertTrue(dispatch.calls().isEmpty(), "no workspace was asked for: " + dispatch.calls());
    asAdmin()
        .when()
        .get("/projects/api/epics/" + epicId)
        .then()
        .statusCode(200)
        // The epic door froze a REPORTED epic before dispatching; nothing froze it now.
        .body("epic.status", equalTo("REPORTED"));
    asAdmin()
        .when()
        .get("/projects/api/tickets/" + ticketId + "/comments")
        .then()
        .statusCode(200)
        .body("entries.size()", equalTo(0));
  }

  @Test
  public void theEpicIdRefinementOpenAnswers404AndOpensNoRoom() {
    String projectId = project("Retired Refinement Open");
    String epicId =
        create("/projects/api/projects/" + projectId + "/epics", Map.of("title", "A plan"), "epic.id");

    asAdmin()
        .body(Map.of("epicId", epicId))
        .when()
        .post("/projects/api/refinements")
        .then()
        .statusCode(404);

    asAdmin()
        .when()
        .get("/projects/api/entities/" + epicId + "/refinement")
        .then()
        .statusCode(200)
        .body("refinement", equalTo(null));
  }

  private static String project(String name) {
    return asAdmin()
        .body(new ProjectController.CreateProjectRequest(name, null, null, null, ProjectRequests.DNS))
        .when()
        .post("/projects/api/projects")
        .then()
        .statusCode(200)
        .extract()
        .path("project.id");
  }

  private static String ticket(String projectId, String title) {
    return create(
        "/projects/api/projects/" + projectId + "/tickets",
        Map.of("title", title, "type", "BUG", "impetus", "something occurs"),
        "ticket.id");
  }
}
