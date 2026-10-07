package eu.wohlben.qits.entities.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The inline door and the one hardened content route, {@code
 * /projects/api/work/{qualifiedId}/dossier-assets[/{assetId}/content]} — the rules {@code
 * /epics/{epicId}/dossier-assets} pinned before qits-976 deleted it.
 *
 * <p><b>The tests are the deliverable here, more than the code.</b> The header string is the whole
 * safety story of framing an agent-authored document, so it is asserted byte for byte on a DESIGN
 * and on an IMAGE alike — a route that hardened itself from a database value would be one bad row
 * away from serving a document unsandboxed. The mime type is asserted to come from the row, and a
 * cross-epic id to be a 404 rather than a body, because the epic in the path is an authorisation
 * boundary and not decoration.
 *
 * <p><b>The stored URL keeps its old shape</b>: the {@code url} and {@code markdown} an answer carries
 * are {@code /epics/{epicId}/dossier-assets/{assetId}/content}, because that string is data — it
 * sits in page bodies, and {@code DossierAssetService}'s reference count parses it — not an address
 * the {@code /work} family may restate. A page body naming that stored URL is a reference, and the
 * {@code /work} content route serves the bytes it names.
 */
@QuarkusTest
public class WorkDossierAssetApiTest {

  /** A 1x1 PNG, so the attachment door's byte sniffing lets it in. */
  private static final String PNG_BASE64 =
      "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==";

  private static final String DOC =
      "<!doctype html><html><body style=\"margin:0\">Checkout</body></html>";

  private static final String WORK = "/projects/api/work/";

  private String createProject(String name) {
    return EntityFixtures.project(name).id();
  }

  private String createEpic(String projectId, String title) {
    return WorkRequests.epic(projectId, title);
  }

  private long openRefinement(String entityId) {
    Number id =
        given()
            .contentType(ContentType.JSON)
            .when()
            .post(WORK + entityId + "/refinement")
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

  private static String assets(String entity) {
    return WORK + entity + "/dossier-assets";
  }

  private static String content(String entity, String assetId) {
    return assets(entity) + "/" + assetId + "/content";
  }

  private io.restassured.response.Response inline(String entity, String sourceId, String kind) {
    return given()
        .contentType(ContentType.JSON)
        .body(Map.of("sourceId", sourceId, "kind", kind))
        .when()
        .post(assets(entity));
  }

  /** The URL a page body holds for a figure: data, in the shape it was always stored in. */
  private static String storedUrl(String epicId, String assetId) {
    return "/epics/" + epicId + "/dossier-assets/" + assetId + "/content";
  }

  @Test
  public void inliningAnswersTheExactMarkdownLineToPasteInTheStoredShape() {
    String projectId = createProject("Figure Inline");
    String epicId = createEpic(projectId, "Checkout epic");
    String qualified = WorkRequests.qualifiedId(epicId);
    long refinementId = openRefinement(qualified);
    String sketchId = attachSketch(refinementId, "The claim loop");

    // Addressed by qualified id, and the stored line still names the epic by UUID under /epics.
    inline(qualified, sketchId, "IMAGE")
        .then()
        .statusCode(200)
        // The copy keeps the source's id, which is what keeps a written URL valid for ever.
        .body("id", equalTo(sketchId))
        .body("kind", equalTo("IMAGE"))
        .body("url", equalTo(storedUrl(epicId, sketchId)))
        .body("markdown", equalTo("![The claim loop](" + storedUrl(epicId, sketchId) + ")"));

    // Inlining the same figure again is the same line, not a second copy.
    inline(epicId, sketchId, "IMAGE")
        .then()
        .statusCode(200)
        .body("id", equalTo(sketchId))
        .body("markdown", equalTo("![The claim loop](" + storedUrl(epicId, sketchId) + ")"));
    given().when().get(assets(epicId)).then().statusCode(200).body("assets.id", contains(sketchId));
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
        .get(content(WorkRequests.qualifiedId(epicId), assetId))
        .then()
        .statusCode(200)
        // The mime type comes from the row, never from what the request asked for.
        .contentType("text/html")
        .header("Content-Security-Policy", DossierAssetContent.SANDBOX_CSP)
        .header(
            "Content-Security-Policy",
            "sandbox; default-src 'none'; img-src 'self' data:; style-src 'unsafe-inline'")
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
            .get(content(epicId, sketchId))
            .then()
            .statusCode(200)
            .contentType("image/png")
            // The headers go on images too: the kind is a column, and a route that read its own
            // hardening off one would be a bad row away from serving a document unsandboxed.
            .header("Content-Security-Policy", DossierAssetContent.SANDBOX_CSP)
            .header("X-Content-Type-Options", "nosniff")
            .header("Cache-Control", "private, max-age=3600")
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
    given().when().get(content(theirs, sketchId)).then().statusCode(404);
    given()
        .when()
        .get(content(WorkRequests.qualifiedId(theirs), sketchId))
        .then()
        .statusCode(404);
    given().when().get(content(mine, sketchId)).then().statusCode(200);
  }

  @Test
  public void aFigureFromAnotherRefinementIsNotCopied() {
    String stranger = createEpic(createProject("Figure Stranger"), "Stranger");
    String strangerSketch = attachSketch(openRefinement(stranger), "Not yours");

    String mine = createEpic(createProject("Figure Owner"), "Owner");
    openRefinement(mine);
    // The boundary that keeps copy-on-reference from becoming a cross-epic reference by accident.
    inline(mine, strangerSketch, "IMAGE").then().statusCode(404);
    given().when().get(assets(mine)).then().statusCode(200).body("assets", empty());
  }

  /**
   * The listing names each asset and the pages that inline it — counted off the stored {@code
   * /epics/…} URL in the page's body — and that URL's figure is what the {@code /work} content route
   * serves.
   */
  @Test
  public void aPageNamingTheStoredUrlIsAReferenceAndTheWorkRouteServesItsBytes() {
    String projectId = createProject("Figure Listing");
    String epicId = createEpic(projectId, "Checkout epic");
    String qualified = WorkRequests.qualifiedId(epicId);
    given().when().get(assets(qualified)).then().statusCode(200).body("assets", empty());

    String sketchId = attachSketch(openRefinement(epicId), "The claim loop");
    String line = inline(epicId, sketchId, "IMAGE").then().statusCode(200).extract().path("markdown");
    String pageId =
        given()
            .contentType(ContentType.JSON)
            .body(Map.of("title", "Flow", "body", "The loop:\n\n" + line))
            .when()
            .post(WORK + qualified + "/dossier")
            .then()
            .statusCode(201)
            .extract()
            .path("id");
    // A second page names the stored URL by hand, not through the pasted line: still a reference.
    String handWritten =
        given()
            .contentType(ContentType.JSON)
            .body(
                Map.of(
                    "title",
                    "Elsewhere",
                    "body",
                    "See ![again](" + storedUrl(epicId, sketchId) + ") below."))
            .when()
            .post(WORK + qualified + "/dossier")
            .then()
            .statusCode(201)
            .extract()
            .path("id");
    // A page with no figure is no reference.
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("title", "Prose", "body", "No pictures here."))
        .when()
        .post(WORK + qualified + "/dossier")
        .then()
        .statusCode(201);

    String url =
        given()
            .when()
            .get(assets(qualified))
            .then()
            .statusCode(200)
            .body("assets.id", contains(sketchId))
            .body("assets[0].kind", equalTo("IMAGE"))
            .body("assets[0].mimeType", equalTo("image/png"))
            .body("assets[0].label", equalTo("The claim loop"))
            .body("assets[0].markdown", equalTo(line))
            .body("assets[0].url", equalTo(storedUrl(epicId, sketchId)))
            .body("assets[0].pageIds", containsInAnyOrder(pageId, handWritten))
            .extract()
            .path("assets[0].url");

    // The stored URL names the epic and the asset; the /work content route serves those bytes.
    String[] segments = url.split("/");
    assertTrue(segments[2].equals(epicId) && segments[4].equals(sketchId), url);
    byte[] served =
        given()
            .when()
            .get(content(qualified, segments[4]))
            .then()
            .statusCode(200)
            .contentType("image/png")
            .header("Content-Security-Policy", DossierAssetContent.SANDBOX_CSP)
            .extract()
            .asByteArray();
    assertTrue(java.util.Arrays.equals(Base64.getDecoder().decode(PNG_BASE64), served));
  }

  @Test
  public void theListingOfAnUnknownEntityIsNotFound() {
    given()
        .when()
        .get(assets(java.util.UUID.randomUUID().toString()))
        .then()
        .statusCode(404);
    String slug = EntityFixtures.project("Figure Nobody").slug();
    given().when().get(assets(slug + "-99999")).then().statusCode(404);
  }

  @Test
  public void aTicketsDossierInlinesNoFigures() {
    String projectId = createProject("Figure Ticket");
    String ticketId = WorkRequests.ticket(projectId, "Figureless", "BUG", "it occurs");
    // dossier_asset is epic-only by decision (V8): a ticket's address answers 404 on every door.
    inline(ticketId, "anything", "IMAGE")
        .then()
        .statusCode(404)
        .body("message", containsString("inlines no figures"));
    given().when().get(assets(ticketId)).then().statusCode(404);
    given().when().get(content(ticketId, "anything")).then().statusCode(404);
  }
}
