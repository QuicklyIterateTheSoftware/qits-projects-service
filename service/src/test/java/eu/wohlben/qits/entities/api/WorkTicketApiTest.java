package eu.wohlben.qits.entities.api;

import static eu.wohlben.qits.entities.api.WorkRequests.map;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import eu.wohlben.qits.projects.security.FakeSessionIntrospection;
import eu.wohlben.qits.projects.security.PersonCheck;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A ticket over the {@code /work} family (qits-976 ported this from the per-archetype {@code
 * TicketApiTest}): the create, the read and the listing, the whole-row edit, the lifecycle, the
 * thread, the block and the delete. The twin of {@link WorkEpicApiTest}, and the caller is named
 * with {@code @TestSecurity} for the reason that class gives.
 *
 * <p>Which makes the {@code createdBy}/{@code author} assertions here worth stating precisely: they
 * pin that the columns are taken from <em>whatever</em> identity the request has and never from the
 * body, not that the header path produces one.
 */
@QuarkusTest
class WorkTicketApiTest {

  private static final String WORK = "/projects/api/work/";

  private static String project() {
    return EntityFixtures.project("Tickets Project").id();
  }

  private static ValidatableResponse create(Map<String, Object> body) {
    return given().contentType(ContentType.JSON).body(body).when().post("/projects/api/work").then();
  }

  /** A REPORTED ticket with criteria, so the ACCEPTANCE_CRITERIA gate is not what a test meets. */
  private static String ticket(String projectId, String title, String type) {
    return TestCriteria.give(
        WorkRequests.ticket(projectId, title, type, "something occurs in this project"));
  }

  private static String addComment(String ticket, String body) {
    return given()
        .contentType(ContentType.JSON)
        .body(map("body", body))
        .when()
        .post(WORK + ticket + "/comments")
        .then()
        .statusCode(200)
        .extract()
        .path("comment.id");
  }

  private static ValidatableResponse move(String ticket, String target) {
    return WorkRequests.status(
            () -> given().cookie(PersonCheck.SESSION_COOKIE, FakeSessionIntrospection.admin("dev")),
            ticket,
            target)
        .then();
  }

  /** Walk a ticket along the pipeline, one move per step, asserting nothing else. */
  private static void walkTo(String ticket, String... targets) {
    for (String target : targets) {
      move(ticket, target).statusCode(200);
    }
  }

  /**
   * The blocked door with its answer left unasserted, so the one success and every refusal below go
   * through a single spelling of the route and the body.
   */
  private static ValidatableResponse setBlocked(String ticket, boolean blocked, String reason) {
    return given()
        .contentType(ContentType.JSON)
        .body(map("blocked", blocked, "reason", reason))
        .when()
        .post(WORK + ticket + "/blocked")
        .then();
  }

  // --- The whole round trip --------------------------------------------------------------------

  @Test
  @TestSecurity(user = "dev", roles = "qits:admin")
  void aTicketAndItsThreadLiveAndDie() {
    String projectId = project();

    String ticket =
        TestCriteria.give(
            create(
                    map(
                        "archetype", "TICKET",
                        "project", projectId,
                        "title", "Login button does nothing",
                        "impetus", "clicking the login button does nothing on the sign-in page",
                        "description", "It just sits there",
                        "ticketType", "BUG",
                        "assignee", "alice"))
                .statusCode(201)
                .body("id", notNullValue())
                .body("projectId", equalTo(projectId))
                .body("title", equalTo("Login button does nothing"))
                .body("slug", equalTo("login-button-does-nothing"))
                .body("ticketType", equalTo("BUG"))
                // A filed ticket has been REPORTED and no more — the lifecycle's starting point.
                .body("status", equalTo("REPORTED"))
                .body(
                    "impetus", equalTo("clicking the login button does nothing on the sign-in page"))
                .body("assignee", equalTo("alice"))
                // Stamped from the identity, never from the body.
                .body("createdBy", equalTo("dev"))
                .body("createdAt", notNullValue())
                .extract()
                .path("id"));

    given()
        .when()
        .get(WORK + ticket)
        .then()
        .statusCode(200)
        .body("id", equalTo(ticket))
        .body("slug", equalTo("login-button-does-nothing"));
    given()
        .queryParam("archetype", "TICKET")
        .when()
        .get("/projects/api/projects/" + projectId + "/work")
        .then()
        .statusCode(200)
        .body("entities.id", hasItem(ticket));

    // A retitle plus a re-type, the rest restated as it was.
    restate(
            ticket,
            ticketRow(
                "Login button is inert",
                "clicking the login button does nothing on the sign-in page",
                "It just sits there",
                "IMPROVEMENT",
                "alice"))
        .statusCode(200);
    given()
        .when()
        .get(WORK + ticket)
        .then()
        .statusCode(200)
        .body("title", equalTo("Login button is inert"))
        .body("ticketType", equalTo("IMPROVEMENT"))
        .body("description", equalTo("It just sits there"))
        .body("impetus", equalTo("clicking the login button does nothing on the sign-in page"))
        .body("assignee", equalTo("alice"))
        // The slug is the row's stable address, so a rename never touches it.
        .body("slug", equalTo("login-button-does-nothing"));

    move(ticket, "REFINED").statusCode(200).body("status", equalTo("REFINED"));
    move(ticket, "READY_FOR_DEV").statusCode(200).body("status", equalTo("READY_FOR_DEV"));
    move(ticket, "IMPLEMENTED").statusCode(200).body("status", equalTo("IMPLEMENTED"));
    // A claim that turned out wrong is this ordinary backward move (to IMPLEMENTING since qits-749)
    // and not a verb of its own.
    move(ticket, "IMPLEMENTING").statusCode(200).body("status", equalTo("IMPLEMENTING"));

    String thread = WORK + ticket + "/comments";
    String comment =
        given()
            .contentType(ContentType.JSON)
            .body(map("body", "I can reproduce it"))
            .when()
            .post(thread)
            .then()
            .statusCode(200)
            .body("comment.entityId", equalTo(ticket))
            .body("comment.body", equalTo("I can reproduce it"))
            .body("comment.author", equalTo("dev"))
            .extract()
            .path("comment.id");

    given()
        .contentType(WorkEntityDoors.MERGE_PATCH_JSON)
        .body(map("body", "I can reproduce it, in dev"))
        .when()
        .patch(thread + "/" + comment)
        .then()
        .statusCode(200)
        .body("comment.body", equalTo("I can reproduce it, in dev"))
        // An edit records who changed it in the audit log; it does not rewrite who wrote it.
        .body("comment.author", equalTo("dev"));

    given()
        .when()
        .get(thread)
        .then()
        .statusCode(200)
        .body("entries", hasSize(1))
        .body("entries.comment.id", hasItem(comment));

    given().when().delete(thread + "/" + comment).then().statusCode(200).body("success", equalTo(true));
    given().when().get(thread).then().statusCode(200).body("entries", hasSize(0));

    given().when().delete(WORK + ticket).then().statusCode(200).body("success", equalTo(true));
    given().when().get(WORK + ticket).then().statusCode(404);
  }

  // --- Listing ---------------------------------------------------------------------------------

  @Test
  @TestSecurity(user = "dev", roles = "qits:admin")
  void theListingIsOldestFirstAndScopedToItsProject() {
    String projectA = project();
    String projectB = project();
    String first = ticket(projectA, "First", "BUG");
    String second = ticket(projectA, "Second", "IMPROVEMENT");
    String elsewhere = ticket(projectB, "Elsewhere", "BUG");

    given()
        .queryParam("archetype", "TICKET")
        .when()
        .get("/projects/api/projects/" + projectA + "/work")
        .then()
        .statusCode(200)
        .body("entities.id", contains(first, second))
        .body("entities.title", contains("First", "Second"));
    given()
        .queryParam("archetype", "TICKET")
        .when()
        .get("/projects/api/projects/" + projectB + "/work")
        .then()
        .statusCode(200)
        .body("entities.id", contains(elsewhere));
  }

  @Test
  @TestSecurity(user = "dev", roles = "qits:admin")
  void theListingFiltersByStatusAndRefusesATypo() {
    String projectId = project();
    String reported = ticket(projectId, "Still broken", "BUG");
    String refined = ticket(projectId, "Described", "BUG");
    move(refined, "REFINED").statusCode(200);
    String listing = "/projects/api/projects/" + projectId + "/work?archetype=TICKET&status=";

    given().when().get(listing + "REPORTED").then().statusCode(200).body("entities.id", contains(reported));
    given().when().get(listing + "REFINED").then().statusCode(200).body("entities.id", contains(refined));

    // A typo must not read as "no tickets", and the retired vocabulary is now one.
    given().when().get(listing + "REFIND").then().statusCode(400);
    given().when().get(listing + "OPEN").then().statusCode(400);
  }

  @Test
  @TestSecurity(user = "dev", roles = "qits:admin")
  void theThreadComesBackOldestFirst() {
    String ticket = ticket(project(), "Threaded", "BUG");
    String first = addComment(ticket, "one");
    String second = addComment(ticket, "two");
    String third = addComment(ticket, "three");

    // A conversation is read from the start — the opposite of the audit log's newest-first.
    given()
        .when()
        .get(WORK + ticket + "/comments")
        .then()
        .statusCode(200)
        .body("entries.comment.id", contains(first, second, third))
        .body("entries.comment.body", contains("one", "two", "three"));
  }

  // --- Emptying the nullable fields ------------------------------------------------------------

  /**
   * <b>The impetus can be emptied after intake</b>, and so can the description and the assignee.
   * {@code IMPETUS} is in the registry's {@code requiredAtCreate} for a ticket and not in its {@code
   * required}: intake demands one, and an existing row is not obliged to carry it for ever. On the
   * whole-row edit a field left out of the row is a field emptied.
   */
  @Test
  @TestSecurity(user = "dev", roles = "qits:admin")
  void theNullableFieldsEmptyThroughTheWholeRowEdit() {
    String ticket =
        TestCriteria.give(
            create(
                    map(
                        "archetype", "TICKET",
                        "project", project(),
                        "title", "Assigned",
                        "impetus", "the list is unsorted",
                        "description", "a body",
                        "ticketType", "BUG",
                        "assignee", "alice"))
                .statusCode(201)
                .extract()
                .path("id"));

    restate(ticket, ticketRow("Renamed", null, null, "BUG", null)).statusCode(200);

    given()
        .when()
        .get(WORK + ticket)
        .then()
        .statusCode(200)
        .body("title", equalTo("Renamed"))
        .body("impetus", nullValue())
        .body("description", nullValue())
        .body("assignee", nullValue());
  }

  // --- Refusals --------------------------------------------------------------------------------

  @Test
  @TestSecurity(user = "dev", roles = "qits:admin")
  void anIllegalMoveIsA409AndNoTargetA400() {
    String ticket = ticket(project(), "Reported already", "BUG");

    // Already REPORTED: the move is refused rather than being a no-op.
    move(ticket, "REPORTED").statusCode(409);
    // And so is a status that exists but is not a neighbour: moves are one step at a time.
    move(ticket, "IMPLEMENTED").statusCode(409);
    // A target naming no status at all is the same kind of answer.
    move(ticket, "CLOSED").statusCode(409);
    // An absent one is malformed, not refused.
    given()
        .cookie(PersonCheck.SESSION_COOKIE, FakeSessionIntrospection.admin("dev"))
        .contentType(ContentType.JSON)
        .body(map("target", null))
        .when()
        .post(WORK + ticket + "/status")
        .then()
        .statusCode(400);
  }

  /**
   * DONE is final: every target is refused, the one step back to VERIFIED included, and the refusal
   * says a follow-up is a new ticket. The ticket stays DONE afterwards.
   */
  @Test
  @TestSecurity(user = "dev", roles = "qits:admin")
  void aDoneTicketRefusesEveryTarget() {
    String ticket = ticket(project(), "Closed for good", "BUG");
    walkTo(ticket, "REFINED", "READY_FOR_DEV", "IMPLEMENTED", "VERIFIED", "DONE");

    for (String target :
        List.of("REPORTED", "REFINED", "READY_FOR_DEV", "IMPLEMENTED", "VERIFIED", "DONE", "DROPPED")) {
      move(ticket, target)
          .statusCode(409)
          .body("message", containsString("DONE is final"))
          .body("message", containsString("a follow-up is a new ticket or epic"));
    }
    given().when().get(WORK + ticket).then().statusCode(200).body("status", equalTo("DONE"));
  }

  @Test
  @TestSecurity(user = "dev", roles = "qits:admin")
  void deletingATicketTakesItsThreadWithIt() {
    String ticket = ticket(project(), "Doomed", "BUG");
    String comment = addComment(ticket, "goes with it");

    given().when().delete(WORK + ticket).then().statusCode(200);

    given().when().get(WORK + ticket + "/comments").then().statusCode(404);
    given()
        .contentType(WorkEntityDoors.MERGE_PATCH_JSON)
        .body(map("body", "still here?"))
        .when()
        .patch(WORK + ticket + "/comments/" + comment)
        .then()
        .statusCode(404);
  }

  @Test
  @TestSecurity(user = "dev", roles = "qits:admin")
  void slugsCollideIntoSuffixesWithinAProject() {
    String projectA = project();
    String projectB = project();
    Map<String, Object> ticket =
        map(
            "archetype", "TICKET",
            "project", projectA,
            "title", "Broken login",
            "impetus", "the login page is broken",
            "ticketType", "BUG");

    create(ticket).statusCode(201).body("slug", equalTo("broken-login"));
    ticket.put("title", "Broken   LOGIN!");
    create(ticket).statusCode(201).body("slug", equalTo("broken-login-2"));

    // Another project is another scope, so the clean slug is free again.
    ticket.put("title", "Broken login");
    ticket.put("project", projectB);
    create(ticket).statusCode(201).body("slug", equalTo("broken-login"));
  }

  @Test
  @TestSecurity(user = "dev", roles = "qits:admin")
  void aTicketUnderAnUnknownProjectIsA404() {
    create(
            map(
                "archetype", "TICKET",
                "project", "ghost",
                "title", "X",
                "impetus", "something occurs",
                "ticketType", "BUG"))
        .statusCode(404);
    given().when().get("/projects/api/projects/ghost/work?archetype=TICKET").then().statusCode(404);
  }

  @Test
  @TestSecurity(user = "dev", roles = "qits:admin")
  void unknownTicketAndCommentIdsAre404() {
    String ticket = ticket(project(), "Live", "BUG");
    given().when().get(WORK + "ghost").then().statusCode(404);
    given()
        .contentType(ContentType.JSON)
        .body(map("body", "hello"))
        .when()
        .post(WORK + "ghost/comments")
        .then()
        .statusCode(404);
    given().when().delete(WORK + ticket + "/comments/ghost").then().statusCode(404);
  }

  @Test
  @TestSecurity(user = "dev", roles = "qits:admin")
  void blankTitlesTypesImpetusesAndBodiesAreRefused() {
    String projectId = project();

    for (Map<String, Object> refused :
        List.of(
            ticketCreate(projectId, "  ", "something occurs", "BUG"),
            ticketCreate(projectId, "T", "something occurs", "  "),
            // The impetus is required at intake: a REPORTED ticket is an impetus and nothing else.
            ticketCreate(projectId, "T", null, "BUG"),
            ticketCreate(projectId, "T", "  ", "BUG"),
            // A type that is present and names nothing.
            ticketCreate(projectId, "T", "something occurs", "DEFECT"))) {
      create(refused).statusCode(400);
    }

    String ticket = ticket(projectId, "Live", "BUG");
    given()
        .contentType(ContentType.JSON)
        .body(map("body", " "))
        .when()
        .post(WORK + ticket + "/comments")
        .then()
        .statusCode(400);
    // A blank title on the whole-row edit is the registry's refusal: a ticket requires one.
    restate(ticket, ticketRow("  ", "x", null, "BUG", null)).statusCode(400);
  }

  private static Map<String, Object> ticketCreate(
      String projectId, String title, String impetus, String type) {
    return map(
        "archetype", "TICKET",
        "project", projectId,
        "title", title,
        "impetus", impetus,
        "ticketType", type);
  }

  // --- Blocking ----------------------------------------------------------------------------------

  @Test
  @TestSecurity(user = "dev", roles = "qits:admin")
  void blockingAScheduledTicketReadsBackBlockedAtTheSameStatus() {
    String ticket = ticket(project(), "Stuck on a sibling", "BUG");
    walkTo(ticket, "REFINED", "READY_FOR_DEV");

    // The door answers the flag it just wrote, and the flag is on the row, so the detail read —
    // how every screen and every agent reads a ticket — carries it too.
    setBlocked(ticket, true, "qits-eventstream has not released the watchdog this needs")
        .statusCode(200)
        .body("block.blocked", equalTo(true));
    given()
        .when()
        .get(WORK + ticket)
        .then()
        .statusCode(200)
        .body("blocked", equalTo(true))
        // A block is not a status: the status still names the phase to resume.
        .body("status", equalTo("READY_FOR_DEV"));

    setBlocked(ticket, false, "it released this morning")
        .statusCode(200)
        .body("block.blocked", equalTo(false));
    given().when().get(WORK + ticket).then().statusCode(200).body("blocked", equalTo(false));
  }

  @Test
  @TestSecurity(user = "dev", roles = "qits:admin")
  void blockingATicketWhoseStatusStartsNoPhaseIsRefused() {
    String projectId = project();

    // The rule is "no phase runs while this status holds" rather than a list of words: VERIFIED and
    // DONE are the far end of the pipeline, DROPPED the exit off it, and REFINED (qits-887) waits
    // for a person to schedule it.
    String verified = ticket(projectId, "Verified already", "BUG");
    walkTo(verified, "REFINED", "READY_FOR_DEV", "IMPLEMENTED", "VERIFIED");
    String done = ticket(projectId, "Closed", "BUG");
    walkTo(done, "REFINED", "READY_FOR_DEV", "IMPLEMENTED", "VERIFIED", "DONE");
    String dropped = ticket(projectId, "Decided against", "IMPROVEMENT");
    walkTo(dropped, "DROPPED");
    String refined = ticket(projectId, "Not scheduled yet", "IMPROVEMENT");
    walkTo(refined, "REFINED");

    for (String ticket : List.of(verified, done, dropped, refined)) {
      setBlocked(ticket, true, "waiting on somebody")
          .statusCode(409)
          .body("message", containsString("no phase is running and there is nothing to block"));
    }
  }

  @Test
  @TestSecurity(user = "dev", roles = "qits:admin")
  void blockingWithNoStatedBlockerIsRefusedAndLeavesNothingBehind() {
    String ticket = ticket(project(), "Blocked by nothing in particular", "BUG");

    // Absent and blank are one case: what is asked for is a sentence somebody could act on.
    for (String reason : Arrays.asList(null, "   ")) {
      setBlocked(ticket, true, reason)
          .statusCode(400)
          .body("message", containsString("stated blocker"));
    }

    // Both writes are on the far side of that refusal: a blocked ticket whose thread says nothing
    // is precisely the state this rule exists to prevent.
    given().when().get(WORK + ticket).then().statusCode(200).body("blocked", equalTo(false));
    given().when().get(WORK + ticket + "/comments").then().statusCode(200).body("entries", hasSize(0));
  }

  @Test
  @TestSecurity(user = "dev", roles = "qits:admin")
  void theBlockerAndTheUnblockAreBothSaidOnTheThread() {
    String ticket = ticket(project(), "Waiting on the registry", "BUG");
    walkTo(ticket, "REFINED", "READY_FOR_DEV");

    setBlocked(ticket, true, "the npm registry refuses the scope, only an operator can add it")
        .statusCode(200);
    // A blocker lands on the thread in the caller's own words, stamped like any other comment.
    given()
        .when()
        .get(WORK + ticket + "/comments")
        .then()
        .statusCode(200)
        .body("entries", hasSize(1))
        .body("entries[0].comment.body", containsString("the npm registry refuses the scope"))
        .body("entries[0].comment.author", equalTo("dev"));

    // An unblock with nothing to add still says so: a thread that goes quiet leaves the next reader
    // unable to tell a cleared blocker from one nobody mentioned again.
    setBlocked(ticket, false, null).statusCode(200);
    given()
        .when()
        .get(WORK + ticket + "/comments")
        .then()
        .statusCode(200)
        .body("entries", hasSize(2))
        .body("entries[1].comment.body", equalTo("Unblocked."));
  }

  @Test
  @TestSecurity(user = "dev", roles = "qits:admin")
  void aMoveClearsTheBlock() {
    String ticket = ticket(project(), "Blocked then moved on", "BUG");
    walkTo(ticket, "REFINED", "READY_FOR_DEV");
    setBlocked(ticket, true, "the change it depends on is not released").statusCode(200);

    // A block says the phase running NOW cannot finish, so the phase changing is what ends it — and
    // the move's own answer says so, since that is what a board draws from.
    move(ticket, "IMPLEMENTED")
        .statusCode(200)
        .body("status", equalTo("IMPLEMENTED"))
        .body("blocked", equalTo(false));
    given().when().get(WORK + ticket).then().statusCode(200).body("blocked", equalTo(false));
  }

  @Test
  @TestSecurity(user = "dev", roles = "qits:admin")
  void unblockingIsAllowedAtAStatusThatCouldNotHaveBeenBlocked() {
    String ticket = ticket(project(), "Blocked on the way to verified", "BUG");
    walkTo(ticket, "REFINED", "READY_FOR_DEV", "IMPLEMENTED");
    setBlocked(ticket, true, "the deployment has not gone out").statusCode(200);
    walkTo(ticket, "VERIFIED");

    // Only the blocking direction is refused: block-then-transition is the one path that leads
    // here, and refusing to unblock at VERIFIED would refuse to tidy up a state of the door's own
    // making. The other direction is pinned by blockingATicketWhoseStatusStartsNoPhaseIsRefused.
    setBlocked(ticket, false, "it deployed").statusCode(200).body("block.blocked", equalTo(false));
    given()
        .when()
        .get(WORK + ticket)
        .then()
        .statusCode(200)
        .body("blocked", equalTo(false))
        .body("status", equalTo("VERIFIED"));
  }

  /** One ticket's whole row as {@code PUT /work/{id}} takes it, at REPORTED. */
  private static Map<String, Object> ticketRow(
      String title, String impetus, String description, String type, String assignee) {
    return map(
        "archetype", "TICKET",
        "title", title,
        "impetus", impetus,
        "description", description,
        "ticketType", type,
        "assignee", assignee,
        "status", "REPORTED",
        // The SPA's form restates the criteria with the rest of the row (qits-887).
        "acceptanceCriteria", TestCriteria.CRITERIA,
        "membership", Collections.singletonMap("parent", null));
  }

  /** The whole-row edit: the ticket's intended post-state, at its own address. */
  private static ValidatableResponse restate(String ticket, Map<String, Object> row) {
    return given().contentType(ContentType.JSON).body(row).when().put(WORK + ticket).then();
  }
}
