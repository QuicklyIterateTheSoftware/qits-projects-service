package eu.wohlben.qits.entities.api;

import static eu.wohlben.qits.entities.api.EntityFixtures.map;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.entities.control.EntityWrite;
import eu.wohlben.qits.entities.control.WorkEntityService;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.projects.bus.RecordingWorkNeedAnnouncer;
import eu.wohlben.qits.projects.bus.WorkNeedMet;
import eu.wohlben.qits.projects.security.FakeSessionIntrospection;
import eu.wohlben.qits.projects.security.PersonCheck;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>What a work item needs</b> (qits-1903, epic qits-1899): {@code needs} — {@code [{"item",
 * "status"}]}, "this waits until that item reaches that status" — written whole by the create, the
 * child create and the patch on an epic, a ticket, a feature and a task, at every status; read on
 * {@code GET /work/{qualifiedId}}, its children and the project listing as {@code needs[]} with
 * {@code unmetNeeds}; every violation in one 400 with the page's sentences; {@code dependsOn}
 * refused on every door; and {@code WorkNeedMet} published when a move meets a need.
 */
@QuarkusTest
@TestSecurity(user = "dev", roles = "qits:admin")
class WorkNeedApiTest {

  private static final String WORK = "/projects/api/work/";

  @Inject WorkEntityService entities;

  @Inject RecordingWorkNeedAnnouncer announced;

  @BeforeEach
  void reset() {
    announced.reset();
  }

  private static ValidatableResponse patch(String id, Object body) {
    return given()
        .contentType(WorkEntityDoors.MERGE_PATCH_JSON)
        .body(body)
        .when()
        .patch(WORK + id)
        .then();
  }

  private static ValidatableResponse read(String id) {
    return given().when().get(WORK + id).then().statusCode(200);
  }

  private static Map<String, Object> need(String item, String status) {
    return map("item", item, "status", status);
  }

  private static Map<String, Object> needs(Map<?, ?>... entries) {
    return map("needs", List.of(entries));
  }

  /** A move through the status door, as a person: scheduling needs one (qits-887). */
  private static void move(String id, String target) {
    given()
        .cookie(PersonCheck.SESSION_COOKIE, FakeSessionIntrospection.admin("dev"))
        .contentType(ContentType.JSON)
        .body(WorkRequests.statusBody(target))
        .when()
        .post(WORK + id + "/status")
        .then()
        .statusCode(200);
  }

  // --- write, read, clear ----------------------------------------------------------------------

  @Test
  void aNeedIsWrittenWholeReadOnEveryReadAndCleared() {
    EntityFixtures.Project project = EntityFixtures.project("Needs Read");
    String waiting = EntityFixtures.ticket(project.id());
    String needed = EntityFixtures.ticket(project.id());
    String neededQualified = EntityFixtures.qualifiedId(needed);

    patch(EntityFixtures.qualifiedId(waiting), needs(need(neededQualified, "VERIFIED")))
        .statusCode(200)
        .body("id", equalTo(waiting));

    read(waiting)
        .body("needs", hasSize(1))
        .body("needs[0].id", equalTo(needed))
        .body("needs[0].qualifiedId", equalTo(neededQualified))
        .body("needs[0].title", equalTo("The ticket"))
        .body("needs[0].archetype", equalTo("TICKET"))
        .body("needs[0].status", equalTo("VERIFIED"))
        .body("needs[0].currentStatus", equalTo("REPORTED"))
        .body("needs[0].met", equalTo(false))
        .body("needs[0].number", org.hamcrest.Matchers.nullValue())
        .body("unmetNeeds", equalTo(1));
    // The needed item needs nothing: [] and 0, never absent, on every work read.
    read(needed).body("needs", hasSize(0)).body("unmetNeeds", equalTo(0));

    given()
        .when()
        .get("/projects/api/projects/" + project.slug() + "/work")
        .then()
        .statusCode(200)
        .body("entities.find { it.id == '" + waiting + "' }.needs[0].qualifiedId",
            equalTo(neededQualified))
        .body("entities.find { it.id == '" + waiting + "' }.unmetNeeds", equalTo(1))
        .body("entities.find { it.id == '" + needed + "' }.needs", hasSize(0))
        .body("entities.find { it.id == '" + needed + "' }.unmetNeeds", equalTo(0));

    // A need already met is met on the read — and writing it announces nothing.
    patch(waiting, needs(need(needed, "REPORTED"))).statusCode(200);
    read(waiting).body("needs[0].met", equalTo(true)).body("unmetNeeds", equalTo(0));
    assertEquals(List.of(), announced.met(), "a write is not something reaching its status");

    patch(waiting, map("needs", List.of())).statusCode(200);
    read(waiting).body("needs", hasSize(0)).body("unmetNeeds", equalTo(0));
    patch(waiting, needs(need(needed, "DONE"))).statusCode(200);
    patch(waiting, map("needs", null)).statusCode(200);
    read(waiting).body("needs", hasSize(0));
  }

  // --- the 400 ---------------------------------------------------------------------------------

  @Test
  void everyViolationIsOneSentenceInOne400() {
    EntityFixtures.Project project = EntityFixtures.project("Needs Refused");
    EntityFixtures.Project other = EntityFixtures.project("Needs Elsewhere");
    String ticket = EntityFixtures.ticket(project.id());
    String ticketQualified = EntityFixtures.qualifiedId(ticket);
    String needed = EntityFixtures.qualifiedId(EntityFixtures.ticket(project.id()));
    String elsewhere = EntityFixtures.qualifiedId(EntityFixtures.ticket(other.id()));
    String issue =
        EntityFixtures.qualifiedId(
            entities
                .create(
                    Archetype.ISSUE,
                    project.id(),
                    EntityWrite.epic("Needs a person", "A person lifts the lease."),
                    "dev")
                .entity()
                .id);
    String campaign = EntityFixtures.qualifiedId(EntityFixtures.campaign(project.id()));

    String body =
        patch(
                ticketQualified,
                needs(
                    need("nope-999", "DONE"),
                    need(elsewhere, "DONE"),
                    need(issue, "DONE"),
                    need(campaign, "DONE"),
                    need(ticketQualified, "DONE"),
                    need(needed, "SHIPPED"),
                    need(needed, "OPEN"),
                    need(needed, "VERIFIED")))
            .statusCode(400)
            .extract()
            .asString();

    for (String sentence :
        List.of(
            "needs[0].item nope-999 is not a work item of this project",
            "needs[1].item " + elsewhere + " is not a work item of this project",
            "needs[2].item "
                + issue
                + " is an ISSUE; only an epic, a ticket, a feature or a task can be needed",
            "needs[3].item "
                + campaign
                + " is a CAMPAIGN; only an epic, a ticket, a feature or a task can be needed",
            "needs[4].item " + ticketQualified + " is this item itself",
            "needs[5].status SHIPPED is not a status of the walk (REPORTED, REFINED,"
                + " READY_FOR_DEV, IMPLEMENTING, IMPLEMENTED, VERIFYING, VERIFIED, DONE) or DROPPED",
            "needs[6].status OPEN is not a status of the walk",
            "needs names " + needed + " twice")) {
      assertTrue(body.contains(sentence), sentence + " in " + body);
    }
    // Nothing was written.
    read(ticket).body("needs", hasSize(0));
  }

  @Test
  void aCycleIsNamedFromTheItemBackToItself() {
    EntityFixtures.Project project = EntityFixtures.project("Needs Cycle");
    String a = EntityFixtures.qualifiedId(EntityFixtures.ticket(project.id()));
    String b = EntityFixtures.qualifiedId(EntityFixtures.ticket(project.id()));
    String c = EntityFixtures.qualifiedId(EntityFixtures.ticket(project.id()));
    patch(b, needs(need(c, "DONE"))).statusCode(200);
    patch(c, needs(need(a, "REFINED"))).statusCode(200);

    patch(a, needs(need(b, "VERIFIED")))
        .statusCode(400)
        .body(containsString("needs would close a cycle: " + a + " → " + b + " → " + c + " → " + a));

    // Replacing c's need breaks the ring, and then a may need b.
    patch(c, map("needs", List.of())).statusCode(200);
    patch(a, needs(need(b, "VERIFIED"))).statusCode(200);
  }

  @Test
  void anIssueAndACampaignHaveNoNeeds() {
    EntityFixtures.Project project = EntityFixtures.project("Needs Refused Kinds");
    String needed = EntityFixtures.qualifiedId(EntityFixtures.ticket(project.id()));
    String campaign = EntityFixtures.campaign(project.id());

    patch(campaign, needs(need(needed, "DONE")))
        .statusCode(400)
        .body(containsString("a CAMPAIGN has no needs"));
    given()
        .contentType(ContentType.JSON)
        .body(
            map(
                "archetype",
                "CAMPAIGN",
                "project",
                project.id(),
                "title",
                "An order",
                "needs",
                List.of(need(needed, "DONE"))))
        .when()
        .post("/projects/api/work")
        .then()
        .statusCode(400)
        .body(containsString("a CAMPAIGN has no needs"));
  }

  // --- every status, every kind ----------------------------------------------------------------

  /**
   * A task needs a task of another feature, written at create, and its needs stay editable after
   * the epic's scope froze at REFINED — where a title edit is refused — because ordering must be
   * fixable after the plan is frozen.
   */
  @Test
  void aTaskNeedsATaskOfAnotherFeatureAndTheNeedsStayEditableAfterTheFreeze() {
    EntityFixtures.Project project = EntityFixtures.project("Needs Tasks");
    String repository = EntityFixtures.repository(project.id());
    String epic = EntityFixtures.epic(project.id());
    String firstFeature = EntityFixtures.feature(epic);
    String secondFeature = EntityFixtures.feature(epic);
    String needed = EntityFixtures.task(firstFeature, repository);
    String neededQualified = EntityFixtures.qualifiedId(needed);

    String waiting =
        given()
            .contentType(ContentType.JSON)
            .body(
                map(
                    "title",
                    "Second step",
                    "repositoryId",
                    repository,
                    "needs",
                    List.of(need(neededQualified, "IMPLEMENTED"))))
            .when()
            .post(WORK + EntityFixtures.qualifiedId(secondFeature) + "/children")
            .then()
            .statusCode(201)
            .extract()
            .path("id");
    read(waiting)
        .body("needs[0].id", equalTo(needed))
        .body("needs[0].archetype", equalTo("TASK"))
        .body("unmetNeeds", equalTo(1));
    given()
        .when()
        .get(WORK + EntityFixtures.qualifiedId(secondFeature) + "/children")
        .then()
        .statusCode(200)
        .body("children[0].needs[0].qualifiedId", equalTo(neededQualified))
        .body("children[0].unmetNeeds", equalTo(1));

    move(epic, "REFINED");
    patch(waiting, map("title", "Renamed")).statusCode(409);
    patch(waiting, needs(need(neededQualified, "DONE"))).statusCode(200);
    read(waiting).body("needs[0].status", equalTo("DONE"));
  }

  // --- dependsOn is retired --------------------------------------------------------------------

  @Test
  void aWriteOfDependsOnIsRefusedOnEveryDoor() {
    EntityFixtures.Project project = EntityFixtures.project("Needs Retired");
    String epic = EntityFixtures.epic(project.id());
    String first = EntityFixtures.feature(epic);
    String second = EntityFixtures.feature(epic);
    String ticket = EntityFixtures.ticket(project.id());

    patch(second, map("dependsOn", first))
        .statusCode(400)
        .body("message", containsString(EntityWireProperties.DEPENDS_ON_RETIRED));
    // A ticket's was "a TICKET has no depends on"; it is the same retirement now.
    patch(ticket, map("dependsOn", first))
        .statusCode(400)
        .body("message", containsString(EntityWireProperties.DEPENDS_ON_RETIRED))
        .body(not(containsString("has no depends on")));
    given()
        .contentType(ContentType.JSON)
        .body(map("title", "Third", "dependsOn", first))
        .when()
        .post(WORK + epic + "/children")
        .then()
        .statusCode(400)
        .body("message", containsString(EntityWireProperties.DEPENDS_ON_RETIRED));
    given()
        .contentType(ContentType.JSON)
        .body(
            Map.of(
                second,
                map(
                    "archetype",
                    "FEATURE",
                    "title",
                    "The part",
                    "status",
                    "REPORTED",
                    "membership",
                    map("parent", epic),
                    "dependsOn",
                    first)))
        .when()
        .post(WORK + "transition")
        .then()
        .statusCode(400)
        .body("message", containsString(EntityWireProperties.DEPENDS_ON_RETIRED));
    assertEquals(
        "dependsOn is retired: name what this waits for in needs, e.g."
            + " \"needs\":[{\"item\":\"qits-12\",\"status\":\"VERIFIED\"}]",
        EntityWireProperties.DEPENDS_ON_RETIRED);
  }

  // --- the event -------------------------------------------------------------------------------

  /**
   * A move of the needed item that meets a need publishes one {@code WorkNeedMet} for it, with
   * {@code allMet} saying whether the waiting item has an unmet need left; a move that meets
   * nothing, and a move back, publish nothing.
   */
  @Test
  void aMoveThatMeetsANeedPublishesWorkNeedMet() {
    EntityFixtures.Project project = EntityFixtures.project("Needs Met");
    String waiting = EntityFixtures.ticket(project.id());
    String first = EntityFixtures.ticket(project.id());
    String second = EntityFixtures.ticket(project.id());
    patch(waiting, needs(need(first, "REFINED"), need(second, "READY_FOR_DEV"))).statusCode(200);

    move(first, "REFINED");
    List<WorkNeedMet> met = announced.metFor(waiting);
    assertEquals(1, met.size(), "one event per need that flipped: " + met);
    WorkNeedMet firstMet = met.get(0);
    assertEquals(project.id(), firstMet.projectId());
    assertEquals(EntityFixtures.qualifiedId(waiting), firstMet.qualifiedId());
    assertEquals(first, firstMet.neededEntityId());
    assertEquals(EntityFixtures.qualifiedId(first), firstMet.neededQualifiedId());
    assertEquals("REFINED", firstMet.status());
    assertEquals("REFINED", firstMet.neededStatus());
    assertEquals(false, firstMet.allMet(), "the second need is still unmet");

    move(second, "REFINED");
    assertEquals(1, announced.metFor(waiting).size(), "REFINED is short of READY_FOR_DEV");
    move(second, "READY_FOR_DEV");
    met = announced.metFor(waiting);
    assertEquals(2, met.size());
    assertEquals(true, met.get(1).allMet());
    read(waiting).body("unmetNeeds", equalTo(0));

    // A move back unmeets the need on the read and publishes nothing; forward again meets it again.
    move(second, "REFINED");
    assertEquals(2, announced.metFor(waiting).size());
    read(waiting).body("unmetNeeds", equalTo(1));
    move(second, "READY_FOR_DEV");
    assertEquals(3, announced.metFor(waiting).size());
  }
}
