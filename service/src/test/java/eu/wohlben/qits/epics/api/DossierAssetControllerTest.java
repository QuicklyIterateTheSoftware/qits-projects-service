package eu.wohlben.qits.epics.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.api.ProjectController;
import eu.wohlben.qits.projects.api.ProjectRequests;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The inline door and the one hardened content route.
 *
 * <p><b>The tests are the deliverable here, more than the code.</b> The header string is the whole
 * safety story of framing an agent-authored document, so it is asserted byte for byte on a DESIGN
 * and on an IMAGE alike — a route that hardened itself from a database value would be one bad row
 * away from serving a document unsandboxed. The mime type is asserted to come from the row, and a
 * cross-epic id to be a 404 rather than a body, because the epic id in the path is an authorisation
 * boundary and not decoration.
 */
@QuarkusTest
public class DossierAssetControllerTest {

  /** A 1x1 PNG, so the attachment door's byte sniffing lets it in. */
  private static final String PNG_BASE64 =
      "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==";

  private static final String DOC =
      "<!doctype html><html><body style=\"margin:0\">Checkout</body></html>";

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

  private long openRefinement(String epicId) {
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

  private String captureDesign(long refinementId, String title) {
    return given()
        .contentType(ContentType.JSON)
        .body(Map.of("title", title, "html", DOC, "truncated", false))
        .when()
        .post("/projects/api/refinements/" + refinementId + "/designs")
        .then()
        .statusCode(201)
        .extract()
        .path("id");
  }

  private String attachSketch(long refinementId, String label) {
    return given()
        .contentType(ContentType.JSON)
        .body(Map.of("label", label, "source", "SKETCH", "dataBase64", PNG_BASE64))
        .when()
        .post("/projects/api/refinements/" + refinementId + "/prompt-attachments")
        .then()
        .statusCode(201)
        .extract()
        .path("id");
  }

  private io.restassured.response.Response inline(String epicId, String sourceId, String kind) {
    return given()
        .contentType(ContentType.JSON)
        .body(Map.of("sourceId", sourceId, "kind", kind))
        .when()
        .post("/projects/api/epics/" + epicId + "/dossier-assets");
  }

  @Test
  public void inliningAnswersTheExactMarkdownLineToPaste() {
    String projectId = createProject("Figure Inline");
    String epicId = createEpic(projectId, "Checkout epic");
    long refinementId = openRefinement(epicId);
    String sketchId = attachSketch(refinementId, "The claim loop");

    inline(epicId, sketchId, "IMAGE")
        .then()
        .statusCode(200)
        // The copy keeps the source's id, which is what keeps a written URL valid for ever.
        .body("id", equalTo(sketchId))
        .body("kind", equalTo("IMAGE"))
        .body(
            "markdown",
            equalTo(
                "![The claim loop](/epics/" + epicId + "/dossier-assets/" + sketchId + "/content)"));

    // Inlining the same figure again is the same line, not a second copy.
    inline(epicId, sketchId, "IMAGE").then().statusCode(200).body("id", equalTo(sketchId));
  }

  @Test
  public void aDesignIsServedWithTheSandboxHeadersByteForByte() {
    String projectId = createProject("Figure Design");
    String epicId = createEpic(projectId, "Checkout epic");
    long refinementId = openRefinement(epicId);
    String designId = captureDesign(refinementId, "Checkout");

    String assetId = inline(epicId, designId, "DESIGN").then().statusCode(200).extract().path("id");

    given()
        .when()
        .get("/projects/api/epics/" + epicId + "/dossier-assets/" + assetId + "/content")
        .then()
        .statusCode(200)
        // The mime type comes from the row, never from what the request asked for.
        .contentType("text/html")
        .header("Content-Security-Policy", DossierAssetController.SANDBOX_CSP)
        .header("X-Content-Type-Options", "nosniff")
        .header("Cache-Control", "private, max-age=3600")
        .body(containsString("Checkout"));
  }

  @Test
  public void anImageIsServedWithTheSameHeaders() {
    String projectId = createProject("Figure Image");
    String epicId = createEpic(projectId, "Checkout epic");
    long refinementId = openRefinement(epicId);
    String sketchId = attachSketch(refinementId, "The claim loop");
    inline(epicId, sketchId, "IMAGE").then().statusCode(200);

    byte[] served =
        given()
            .when()
            .get("/projects/api/epics/" + epicId + "/dossier-assets/" + sketchId + "/content")
            .then()
            .statusCode(200)
            .contentType("image/png")
            // The headers go on images too: the kind is a column, and a route that read its own
            // hardening off one would be a bad row away from serving a document unsandboxed.
            .header("Content-Security-Policy", DossierAssetController.SANDBOX_CSP)
            .header("X-Content-Type-Options", "nosniff")
            .extract()
            .asByteArray();
    assertTrue(java.util.Arrays.equals(Base64.getDecoder().decode(PNG_BASE64), served));
  }

  @Test
  public void anAssetOfAnotherEpicIsNotFound() {
    String projectId = createProject("Figure Mine");
    String mine = createEpic(projectId, "Mine");
    long refinementId = openRefinement(mine);
    String sketchId = attachSketch(refinementId, "The claim loop");
    inline(mine, sketchId, "IMAGE").then().statusCode(200);

    String theirs = createEpic(createProject("Figure Theirs"), "Theirs");
    given()
        .when()
        .get("/projects/api/epics/" + theirs + "/dossier-assets/" + sketchId + "/content")
        .then()
        .statusCode(404);
  }

  @Test
  public void aFigureFromAnotherRefinementIsNotCopied() {
    String stranger = createEpic(createProject("Figure Stranger"), "Stranger");
    String strangerSketch = attachSketch(openRefinement(stranger), "Not yours");

    String mine = createEpic(createProject("Figure Owner"), "Owner");
    openRefinement(mine);
    // The boundary that keeps copy-on-reference from becoming a cross-epic reference by accident.
    inline(mine, strangerSketch, "IMAGE").then().statusCode(404);
  }
}
