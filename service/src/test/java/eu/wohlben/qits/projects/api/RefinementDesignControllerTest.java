package eu.wohlben.qits.projects.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.persistence.RefinementDesignRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The refinement designs REST surface: capture, list, read, the in-place rewrite and its version
 * check, the size cap, the per-refinement scoping, and the cascade a discard leaves behind.
 *
 * <p>There is no lifecycle left to test. A design is a document written and rewritten in place, so
 * what stands where the resolve cases were is the 409: a write carrying a version somebody has
 * already moved past is refused, and the refusal carries the current document.
 */
@QuarkusTest
public class RefinementDesignControllerTest {

  @Inject RefinementDesignRepository store;

  private static final String DOC =
      "<!doctype html><html><body style=\"margin:0\">Checkout</body></html>";

  private static final String REVISED =
      "<!doctype html><html><body style=\"margin:0\">Checkout, roomier</body></html>";

  // --- Fixtures -------------------------------------------------------------

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

  private String createEpic(String projectId, String title) {
    return given()
        .contentType(ContentType.JSON)
        .body(Map.of("title", title, "description", "A draft."))
        .when()
        .post("/projects/api/projects/" + projectId + "/epics")
        .then()
        .statusCode(200)
        .extract()
        .path("epic.id");
  }

  /** A refinement of a fresh epic in a fresh project — the row every design hangs off. */
  private long openRefinement(String name) {
    String projectId = createProject(name);
    String epicId = createEpic(projectId, name + " Epic");
    Number id =
        given()
            .contentType(ContentType.JSON)
            .body(Map.of("epicId", epicId))
            .when()
            .post("/projects/api/refinements")
            .then()
            .statusCode(200)
            .extract()
            .path("refinement.id");
    return id.longValue();
  }

  private static String base(long refinementId) {
    return "/projects/api/refinements/" + refinementId + "/designs";
  }

  private String capture(long refinementId, String title, String html) {
    return given()
        .contentType(ContentType.JSON)
        .body(Map.of("title", title, "html", html, "sourceRoute", "/checkout", "truncated", false))
        .when()
        .post(base(refinementId))
        .then()
        .statusCode(201)
        .extract()
        .path("id");
  }

  /** A write onto an existing design, at whatever version the caller believes is current. */
  private io.restassured.response.Response update(
      long refinementId, String designId, String title, String html, long version) {
    Map<String, Object> body = new java.util.HashMap<>();
    body.put("title", title);
    body.put("version", version);
    if (html != null) {
      body.put("html", html);
    }
    return given()
        .contentType(ContentType.JSON)
        .body(body)
        .when()
        .put(base(refinementId) + "/" + designId);
  }

  private static String htmlOf(long refinementId, String designId) {
    return given()
        .when()
        .get(base(refinementId) + "/" + designId)
        .then()
        .statusCode(200)
        .extract()
        .path("html");
  }

  // --- Capture and read -----------------------------------------------------

  @Test
  public void aCapturedDesignStartsAtVersionZeroAndCarriesItsDocumentOnlyOnTheSingleRead() {
    long id = openRefinement("Design Capture");

    String designId =
        given()
            .contentType(ContentType.JSON)
            .body(Map.of("title", "Checkout", "html", DOC, "truncated", false))
            .when()
            .post(base(id))
            .then()
            .statusCode(201)
            .body("version", equalTo(0))
            .body("htmlBytes", equalTo(DOC.length()))
            .body("createdBy", notNullValue())
            .body("html", nullValue())
            .extract()
            .path("id");

    given()
        .when()
        .get(base(id))
        .then()
        .statusCode(200)
        .body("designs[0].id", equalTo(designId))
        .body("designs[0].title", equalTo("Checkout"))
        .body("designs[0].html", nullValue());

    given()
        .when()
        .get(base(id) + "/" + designId)
        .then()
        .statusCode(200)
        .body("html", equalTo(DOC));
  }

  @Test
  public void aDesignIsRenamedAndDeleted() {
    long id = openRefinement("Design Rename");
    String designId = capture(id, "Draft", DOC);

    update(id, designId, "Checkout, second pass", null, 0)
        .then()
        .statusCode(200)
        .body("title", equalTo("Checkout, second pass"))
        .body("version", equalTo(1));

    // The document is untouched by a rename that carried no html.
    assertTrue(DOC.equals(htmlOf(id, designId)), "a rename must leave the document alone");

    given().when().delete(base(id) + "/" + designId).then().statusCode(204);
    given().when().get(base(id) + "/" + designId).then().statusCode(404);
  }

  // --- Writing in place ----------------------------------------------------

  @Test
  public void aDesignIsRewrittenInPlaceAndItsVersionMovesWithIt() {
    long id = openRefinement("Design Rewrite");
    String designId = capture(id, "Checkout", DOC);

    update(id, designId, "Checkout", REVISED, 0)
        .then()
        .statusCode(200)
        .body("id", equalTo(designId))
        .body("version", equalTo(1));

    assertTrue(REVISED.equals(htmlOf(id, designId)), "the design must carry the rewrite");
  }

  @Test
  public void aWriteCarryingAStaleVersionIsRefusedWithTheCurrentDesign() {
    long id = openRefinement("Design Contested");
    String designId = capture(id, "Checkout", DOC);

    update(id, designId, "Checkout", REVISED, 0).then().statusCode(200);

    // A second writer composed against version 0, which has been overtaken.
    update(id, designId, "Checkout", DOC, 0)
        .then()
        .statusCode(409)
        .body("current.version", equalTo(1))
        .body("current.html", equalTo(REVISED));

    // Nothing was merged: the first write still stands.
    assertTrue(REVISED.equals(htmlOf(id, designId)), "a refused write must change nothing");
  }

  @Test
  public void aWriteOnAnExistingDesignMustCarryAVersion() {
    long id = openRefinement("Design Versionless");
    String designId = capture(id, "Checkout", DOC);

    given()
        .contentType(ContentType.JSON)
        .body(Map.of("title", "Checkout"))
        .when()
        .put(base(id) + "/" + designId)
        .then()
        .statusCode(400);
  }

  // --- Limits and scoping ---------------------------------------------------

  @Test
  public void aDocumentOverTheCapIsRefused() {
    long id = openRefinement("Design Huge");
    String huge = "x".repeat(5 * 1024 * 1024);

    given()
        .contentType(ContentType.JSON)
        .body(Map.of("title", "Too much", "html", huge, "truncated", false))
        .when()
        .post(base(id))
        .then()
        .statusCode(413);
  }

  @Test
  public void aDesignOfAnotherRefinementIsNotFound() {
    long mine = openRefinement("Design Mine");
    long theirs = openRefinement("Design Theirs");
    String designId = capture(mine, "Checkout", DOC);

    given().when().get(base(theirs) + "/" + designId).then().statusCode(404);
    given().when().delete(base(theirs) + "/" + designId).then().statusCode(404);
  }

  @Test
  public void discardingTheRefinementTakesItsDesignsWithIt() {
    long id = openRefinement("Design Discard");
    String designId = capture(id, "Checkout", DOC);

    given()
        .when()
        .post("/projects/api/refinements/" + id + "/discard")
        .then()
        .statusCode(200)
        .body("success", equalTo(true));

    assertTrue(
        QuarkusTransaction.requiringNew().call(() -> store.findByIdOptional(designId)).isEmpty(),
        "the design must go with the refinement it hangs off");
  }
}
