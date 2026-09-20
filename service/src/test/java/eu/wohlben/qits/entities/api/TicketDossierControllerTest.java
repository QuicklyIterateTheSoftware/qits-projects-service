package eu.wohlben.qits.entities.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;

import eu.wohlben.qits.projects.api.ProjectController;
import eu.wohlben.qits.projects.api.ProjectRequests;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The ticket half of the dossier's REST door — the routes the SPA's ticket Dossier tab is written
 * against: {@code GET/PUT/DELETE /tickets/{ticketId}/dossier[/{slug}]} and {@code
 * POST …/{slug}/move}.
 *
 * <p>Two cases carry the feature. <b>Nothing freezes</b>: the same page is written while the ticket
 * is REPORTED and again after it is DONE, which is the whole difference from the epic routes. And
 * the <b>409 with the current page</b> is the ordinary case here too — nobody accepts a write, so an
 * agent writing from a prompt and a person typing in the tab are the same page's two authors.
 */
@QuarkusTest
public class TicketDossierControllerTest {

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

  private String createTicket(String name) {
    return given()
        .contentType(ContentType.JSON)
        .body(
            Map.of(
                "title",
                name + " ticket",
                "impetus",
                "A 500 occurs when the claim loop runs twice.",
                "type",
                "BUG"))
        .when()
        .post("/projects/api/projects/" + createProject(name) + "/tickets")
        .then()
        .statusCode(200)
        .extract()
        .path("ticket.id");
  }

  private static String base(String ticketId) {
    return "/projects/api/tickets/" + ticketId + "/dossier";
  }

  private String addPage(String ticketId, String title, String body) {
    return given()
        .contentType(ContentType.JSON)
        .body(Map.of("title", title, "body", body))
        .when()
        .post(base(ticketId))
        .then()
        .statusCode(200)
        .extract()
        .path("slug");
  }

  private io.restassured.response.Response write(
      String ticketId, String slug, String title, String body, Long version) {
    Map<String, Object> request = new HashMap<>();
    request.put("title", title);
    request.put("body", body);
    request.put("version", version);
    return given()
        .contentType(ContentType.JSON)
        .body(request)
        .when()
        .put(base(ticketId) + "/" + slug);
  }

  private void transition(String ticketId, String target) {
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("target", target))
        .when()
        .post("/projects/api/tickets/" + ticketId + "/transition")
        .then()
        .statusCode(200);
  }

  @Test
  public void pagesAreListedInOrderWithTheirBodiesAndNameTheirTicket() {
    String ticketId = createTicket("Dossier list");
    addPage(ticketId, "The root cause", "# The root cause\n\nFour services deep.");
    addPage(ticketId, "What to change", "The rest of it.");

    given()
        .when()
        .get(base(ticketId))
        .then()
        .statusCode(200)
        .body("pages", hasSize(2))
        .body("pages[0].slug", equalTo("the-root-cause"))
        .body("pages[0].ticketId", equalTo(ticketId))
        // Exactly one owner is set, and a client reads which without being told what it asked for.
        .body("pages[0].epicId", nullValue())
        .body("pages[0].body", equalTo("# The root cause\n\nFour services deep."))
        .body("pages[1].position", equalTo(1));
  }

  @Test
  public void aPageIsWrittenMovedAndDeletedBySlug() {
    String ticketId = createTicket("Dossier write");
    String first = addPage(ticketId, "One", "one");
    String second = addPage(ticketId, "Two", "two");

    write(ticketId, first, "One, renamed", "one again", 0L)
        .then()
        .statusCode(200)
        .body("title", equalTo("One, renamed"))
        // The slug is minted at create and never re-derived: it is in URLs people have sent.
        .body("slug", equalTo("one"))
        .body("version", equalTo(1));

    given()
        .contentType(ContentType.JSON)
        .body(Map.of("position", 0))
        .when()
        .post(base(ticketId) + "/" + second + "/move")
        .then()
        .statusCode(200)
        .body("position", equalTo(0));

    given().when().delete(base(ticketId) + "/" + second).then().statusCode(200);
    given().when().get(base(ticketId) + "/" + second).then().statusCode(404);
    given().when().get(base(ticketId)).then().statusCode(200).body("pages[0].position", equalTo(0));
  }

  @Test
  public void aTicketsDossierIsWritableAtEveryStatus() {
    String ticketId = createTicket("Dossier unfrozen");
    String slug = addPage(ticketId, "The root cause", "as reported");

    for (String target : new String[] {"REFINED", "IMPLEMENTED", "VERIFIED", "DONE"}) {
      transition(ticketId, target);
    }

    // Closed, and still writable: a ticket commits to no scope, so there is nothing to freeze.
    write(ticketId, slug, null, "as it turned out", 0L).then().statusCode(200);
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("title", "Another", "body", ""))
        .when()
        .post(base(ticketId))
        .then()
        .statusCode(200);
  }

  @Test
  public void aStaleWriteIsRefusedWithTheCurrentPage() {
    String ticketId = createTicket("Dossier contested");
    String slug = addPage(ticketId, "The root cause", "first");

    write(ticketId, slug, null, "second", 0L).then().statusCode(200);

    write(ticketId, slug, null, "third", 0L)
        .then()
        .statusCode(409)
        .body("current.version", equalTo(1))
        // The body is on it: the tab shows what a write would have overwritten.
        .body("current.body", equalTo("second"));

    given().when().get(base(ticketId) + "/" + slug).then().body("body", equalTo("second"));
  }

  @Test
  public void aWriteWithNoVersionIsRefused() {
    String ticketId = createTicket("Dossier versionless");
    String slug = addPage(ticketId, "The root cause", "first");
    write(ticketId, slug, null, "second", null).then().statusCode(400);
  }

  @Test
  public void aPageOfAnotherTicketIsNotFound() {
    String mine = createTicket("Dossier mine");
    String theirs = createTicket("Dossier theirs");
    addPage(mine, "The root cause", "first");

    given().when().get(base(theirs) + "/the-root-cause").then().statusCode(404);
    given().when().delete(base(theirs) + "/the-root-cause").then().statusCode(404);
  }

  @Test
  public void aTicketThatDoesNotExistIsNotFound() {
    given().when().get(base("no-such-ticket")).then().statusCode(404);
  }

  @Test
  public void thereIsNoAssetRouteUnderATicket() {
    String ticketId = createTicket("Dossier figureless");
    // dossier_asset is epic-only by decision (V8); the absence of the route is the whole guard.
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("sourceId", "anything", "kind", "IMAGE"))
        .when()
        .post("/projects/api/tickets/" + ticketId + "/dossier-assets")
        .then()
        .statusCode(404);
  }
}
