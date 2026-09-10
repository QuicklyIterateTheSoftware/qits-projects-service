package eu.wohlben.qits.epics.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

import eu.wohlben.qits.projects.api.ProjectController;
import eu.wohlben.qits.projects.api.ProjectRequests;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The dossier's REST door.
 *
 * <p>Two cases carry the feature. The <b>409 with the current page on it</b> is the ordinary case on
 * this route rather than an edge one — nobody accepts a write here, so a person and an agent write
 * the same page — and the tab needs the current body to say what a write would have overwritten. And
 * a <b>frozen epic stays readable</b>: implementation reads this months after the scope was frozen,
 * so only the mutations are refused.
 */
@QuarkusTest
public class DossierControllerTest {

  private String createProject(String name) {
    return given()
        .contentType(ContentType.JSON)
        .body(
            new ProjectController.CreateProjectRequest(name, null, null, null, ProjectRequests.DNS))
        .when()
        .post("/projects/api/projects")
        .then()
        .statusCode(200)
        .extract()
        .path("project.id");
  }

  private String createEpic(String name) {
    return given()
        .contentType(ContentType.JSON)
        .body(Map.of("title", name + " Epic", "description", "A draft."))
        .when()
        .post("/projects/api/projects/" + createProject(name) + "/epics")
        .then()
        .statusCode(200)
        .extract()
        .path("epic.id");
  }

  private static String base(String epicId) {
    return "/projects/api/epics/" + epicId + "/dossier";
  }

  private String addPage(String epicId, String title, String body) {
    return given()
        .contentType(ContentType.JSON)
        .body(Map.of("title", title, "body", body))
        .when()
        .post(base(epicId))
        .then()
        .statusCode(200)
        .extract()
        .path("id");
  }

  private io.restassured.response.Response write(
      String epicId, String pageId, String title, String body, Long version) {
    Map<String, Object> request = new HashMap<>();
    request.put("title", title);
    request.put("body", body);
    request.put("version", version);
    return given()
        .contentType(ContentType.JSON)
        .body(request)
        .when()
        .put(base(epicId) + "/" + pageId);
  }

  @Test
  public void pagesAreListedInOrderWithTheirBodies() {
    String epicId = createEpic("Dossier List");
    addPage(epicId, "The claim loop", "# The claim loop\n\nHow it turns.");
    addPage(epicId, "What the tab uses", "The rest of the route.");

    given()
        .when()
        .get(base(epicId))
        .then()
        .statusCode(200)
        .body("pages", hasSize(2))
        .body("pages[0].slug", equalTo("the-claim-loop"))
        .body("pages[0].position", equalTo(0))
        // The list carries bodies: the tab renders one immediately and a dossier is a few pages.
        .body("pages[0].body", equalTo("# The claim loop\n\nHow it turns."))
        .body("pages[1].position", equalTo(1));
  }

  @Test
  public void aPageIsWrittenMovedAndDeleted() {
    String epicId = createEpic("Dossier Write");
    String first = addPage(epicId, "One", "one");
    String second = addPage(epicId, "Two", "two");

    write(epicId, first, "One, renamed", "one again", 0L)
        .then()
        .statusCode(200)
        .body("title", equalTo("One, renamed"))
        .body("slug", equalTo("one"))
        .body("version", equalTo(1));

    given()
        .contentType(ContentType.JSON)
        .body(Map.of("position", 0))
        .when()
        .post(base(epicId) + "/" + second + "/move")
        .then()
        .statusCode(200)
        .body("position", equalTo(0));

    given().when().delete(base(epicId) + "/" + second).then().statusCode(200);
    given().when().get(base(epicId) + "/" + second).then().statusCode(404);
    given().when().get(base(epicId)).then().statusCode(200).body("pages[0].position", equalTo(0));
  }

  @Test
  public void aStaleWriteIsRefusedWithTheCurrentPage() {
    String epicId = createEpic("Dossier Contested");
    String pageId = addPage(epicId, "The claim loop", "first");

    write(epicId, pageId, null, "second", 0L).then().statusCode(200);

    write(epicId, pageId, null, "third", 0L)
        .then()
        .statusCode(409)
        .body("current.version", equalTo(1))
        // The body is on it, which is the whole point: the tab shows what would have been lost.
        .body("current.body", equalTo("second"));

    given().when().get(base(epicId) + "/" + pageId).then().body("body", equalTo("second"));
  }

  @Test
  public void aWriteWithNoVersionIsRefused() {
    String epicId = createEpic("Dossier Versionless");
    String pageId = addPage(epicId, "The claim loop", "first");
    write(epicId, pageId, null, "second", null).then().statusCode(400);
  }

  @Test
  public void aPageOfAnotherEpicIsNotFound() {
    String mine = createEpic("Dossier Mine");
    String theirs = createEpic("Dossier Theirs");
    String pageId = addPage(mine, "The claim loop", "first");

    given().when().get(base(theirs) + "/" + pageId).then().statusCode(404);
    given().when().delete(base(theirs) + "/" + pageId).then().statusCode(404);
  }

  @Test
  public void aFrozenEpicReadsAndRefusesEveryWrite() {
    String epicId = createEpic("Dossier Frozen");
    String pageId = addPage(epicId, "The claim loop", "the body");

    given()
        .contentType(ContentType.JSON)
        .body(Map.of("target", "IMPLEMENTATION"))
        .when()
        .post("/projects/api/epics/" + epicId + "/transition")
        .then()
        .statusCode(200);

    given().when().get(base(epicId)).then().statusCode(200).body("pages[0].body", equalTo("the body"));
    write(epicId, pageId, "Renamed", null, 0L).then().statusCode(409);
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("title", "Another", "body", ""))
        .when()
        .post(base(epicId))
        .then()
        .statusCode(409);
  }
}
