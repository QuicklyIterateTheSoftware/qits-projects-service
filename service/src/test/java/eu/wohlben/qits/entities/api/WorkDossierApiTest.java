package eu.wohlben.qits.entities.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;

import eu.wohlben.qits.projects.security.FakeSessionIntrospection;
import eu.wohlben.qits.projects.security.PersonCheck;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The dossier's REST door, {@code /projects/api/work/{qualifiedId}/dossier} — the rules the epic
 * half ({@code /epics/{id}/dossier}) and the ticket half ({@code /tickets/{id}/dossier}) pinned
 * before qits-976 folded both into the one {@code /work} family. The addresses themselves (slug
 * first, the page id as a fallback, a page of another owner not found, an archetype with no dossier
 * a 404) are {@code WorkSubresourcesApiTest}'s; this class is about what the dossier does.
 *
 * <p>Three cases carry the feature. The <b>409 with the current page on it</b> is the ordinary case
 * on this route rather than an edge one — nobody accepts a write here, so a person and an agent write
 * the same page — and the tab needs the current body to say what a write would have overwritten. A
 * <b>frozen epic stays readable</b>: implementation reads this months after the scope was frozen, so
 * only the mutations are refused. And <b>a ticket's dossier never freezes</b>: the same page is
 * written while the ticket is REPORTED and again after it is DONE.
 */
@QuarkusTest
public class WorkDossierApiTest {

  private static final String WORK = "/projects/api/work/";

  private String createEpic(String name) {
    String projectId = EntityFixtures.project(name).id();
    return TestCriteria.give(WorkRequests.epic(projectId, name + " Epic"));
  }

  private String createTicket(String name) {
    String projectId = EntityFixtures.project(name).id();
    return TestCriteria.give(
        WorkRequests.ticket(
            projectId, name + " ticket", "BUG", "A 500 occurs when the claim loop runs twice."));
  }

  private static String base(String entityId) {
    return WORK + entityId + "/dossier";
  }

  /** Adds a page; answers its slug, which is how the routes below address it. */
  private String addPage(String entityId, String title, String body) {
    return given()
        .contentType(ContentType.JSON)
        .body(Map.of("title", title, "body", body))
        .when()
        .post(base(entityId))
        .then()
        .statusCode(201)
        .extract()
        .path("slug");
  }

  private io.restassured.response.Response write(
      String entityId, String page, String title, String body, Long version) {
    Map<String, Object> request = new HashMap<>();
    request.put("title", title);
    request.put("body", body);
    request.put("version", version);
    return given().contentType(ContentType.JSON).body(request).when().put(base(entityId) + "/" + page);
  }

  /** A person's session: the status door's moves are a person's. */
  private static RequestSpecification asPerson() {
    return given().cookie(PersonCheck.SESSION_COOKIE, FakeSessionIntrospection.admin("dev"));
  }

  private static void move(String entityId, String target) {
    WorkRequests.status(WorkDossierApiTest::asPerson, entityId, target).then().statusCode(200);
  }

  // --- the epic's dossier ----------------------------------------------------------------------

  @Test
  public void anEpicsPagesAreListedInOrderWithTheirBodiesAndNameTheirEpic() {
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
        .body("pages[0].epicId", equalTo(epicId))
        .body("pages[0].ticketId", nullValue())
        // The list carries bodies: the tab renders one immediately and a dossier is a few pages.
        .body("pages[0].body", equalTo("# The claim loop\n\nHow it turns."))
        .body("pages[1].position", equalTo(1));
  }

  @Test
  public void aPageIsRetitledWithoutMovingItsSlugMovedAndDeleted() {
    String epicId = createEpic("Dossier Write");
    String first = addPage(epicId, "One", "one");
    String second = addPage(epicId, "Two", "two");

    write(epicId, first, "One, renamed", "one again", 0L)
        .then()
        .statusCode(200)
        .body("title", equalTo("One, renamed"))
        // The slug is minted at create and never re-derived: it is in URLs people have sent.
        .body("slug", equalTo("one"))
        .body("version", equalTo(1));
    given().when().get(base(epicId) + "/one").then().statusCode(200).body("title", equalTo("One, renamed"));

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
    // The pages after it close the gap.
    given().when().get(base(epicId)).then().statusCode(200).body("pages[0].position", equalTo(0));
  }

  @Test
  public void aStaleWriteIsRefusedWithTheCurrentPage() {
    String epicId = createEpic("Dossier Contested");
    String page = addPage(epicId, "The claim loop", "first");

    write(epicId, page, null, "second", 0L).then().statusCode(200);

    write(epicId, page, null, "third", 0L)
        .then()
        .statusCode(409)
        .body("current.version", equalTo(1))
        // The body is on it, which is the whole point: the tab shows what would have been lost.
        .body("current.body", equalTo("second"));

    given().when().get(base(epicId) + "/" + page).then().body("body", equalTo("second"));
  }

  @Test
  public void aWriteWithNoVersionIsRefused() {
    String epicId = createEpic("Dossier Versionless");
    String page = addPage(epicId, "The claim loop", "first");
    write(epicId, page, null, "second", null).then().statusCode(400);

    String ticketId = createTicket("Ticket Dossier Versionless");
    String ticketPage = addPage(ticketId, "The root cause", "first");
    write(ticketId, ticketPage, null, "second", null).then().statusCode(400);
  }

  @Test
  public void aPageOfAnotherEntityIsNotFoundToReadOrToDelete() {
    String mine = createEpic("Dossier Mine");
    String theirs = createEpic("Dossier Theirs");
    String page = addPage(mine, "The claim loop", "first");
    String pageId = given().when().get(base(mine) + "/" + page).then().extract().path("id");

    given().when().get(base(theirs) + "/" + page).then().statusCode(404);
    given().when().get(base(theirs) + "/" + pageId).then().statusCode(404);
    given().when().delete(base(theirs) + "/" + page).then().statusCode(404);
    given().when().delete(base(theirs) + "/" + pageId).then().statusCode(404);
    given().when().get(base(mine) + "/" + page).then().statusCode(200);

    String myTicket = createTicket("Ticket Dossier Mine");
    String theirTicket = createTicket("Ticket Dossier Theirs");
    addPage(myTicket, "The root cause", "first");
    given().when().get(base(theirTicket) + "/the-root-cause").then().statusCode(404);
    given().when().delete(base(theirTicket) + "/the-root-cause").then().statusCode(404);
    given().when().get(base(myTicket) + "/the-root-cause").then().statusCode(200);
  }

  @Test
  public void aFrozenEpicReadsAndRefusesEveryWrite() {
    String epicId = createEpic("Dossier Frozen");
    String page = addPage(epicId, "The claim loop", "the body");

    move(epicId, "REFINED");

    given().when().get(base(epicId)).then().statusCode(200).body("pages[0].body", equalTo("the body"));
    write(epicId, page, "Renamed", null, 0L).then().statusCode(409);
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("title", "Another", "body", ""))
        .when()
        .post(base(epicId))
        .then()
        .statusCode(409);
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("position", 0))
        .when()
        .post(base(epicId) + "/" + page + "/move")
        .then()
        .statusCode(409);
    given().when().delete(base(epicId) + "/" + page).then().statusCode(409);
    given().when().get(base(epicId) + "/" + page).then().statusCode(200).body("title", equalTo("The claim loop"));
  }

  // --- the ticket's dossier --------------------------------------------------------------------

  @Test
  public void aTicketsPagesAreListedInOrderWithTheirBodiesAndNameTheirTicket() {
    String ticketId = createTicket("Ticket Dossier List");
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
  public void aTicketsDossierIsWritableAtEveryStatus() {
    String ticketId = createTicket("Ticket Dossier Unfrozen");
    String slug = addPage(ticketId, "The root cause", "as reported");

    for (String target : new String[] {"REFINED", "READY_FOR_DEV", "IMPLEMENTED", "VERIFIED", "DONE"}) {
      move(ticketId, target);
    }

    // Closed, and still writable: a ticket commits to no scope, so there is nothing to freeze.
    write(ticketId, slug, null, "as it turned out", 0L).then().statusCode(200);
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("title", "Another", "body", ""))
        .when()
        .post(base(ticketId))
        .then()
        .statusCode(201);
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("position", 0))
        .when()
        .post(base(ticketId) + "/another/move")
        .then()
        .statusCode(200);
    given().when().delete(base(ticketId) + "/another").then().statusCode(200);
  }

  @Test
  public void anEntityThatDoesNotExistIsNotFound() {
    String slug = EntityFixtures.project("Dossier Nobody").slug();
    given().when().get(base(slug + "-99999")).then().statusCode(404);
    given().when().get(base(java.util.UUID.randomUUID().toString())).then().statusCode(404);
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("title", "Orphan", "body", ""))
        .when()
        .post(base(slug + "-99999"))
        .then()
        .statusCode(404);
  }
}
