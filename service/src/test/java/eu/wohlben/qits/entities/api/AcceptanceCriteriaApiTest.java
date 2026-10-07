package eu.wohlben.qits.entities.api;

import static eu.wohlben.qits.entities.api.EntityFixtures.map;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

import eu.wohlben.qits.projects.security.FakeSessionIntrospection;
import eu.wohlben.qits.projects.security.PersonCheck;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Acceptance criteria on the wire (qits-887, qits-934): written by the create and the merge patch,
 * restated by the PUT-shaped transition, permitted on an epic and a ticket only, judged item by item,
 * editable at REFINED outside an epic's scope freeze, and frozen from READY_FOR_DEV on — where a
 * restated, unchanged list still passes, because the SPA's edit form restates the whole row.
 */
@QuarkusTest
class AcceptanceCriteriaApiTest {

  private static final String MERGE_PATCH = "application/merge-patch+json";

  /** A person's browser session, as the edge forwards it: the scheduling move needs one. */
  private static RequestSpecification person() {
    return given()
        .cookie(PersonCheck.SESSION_COOKIE, FakeSessionIntrospection.admin("ada"))
        .contentType(ContentType.JSON);
  }

  private static ValidatableResponse patch(String id, Map<String, Object> body) {
    return given()
        .contentType(MERGE_PATCH)
        .body(body)
        .when()
        .patch("/projects/api/work/" + id)
        .then();
  }

  private static ValidatableResponse read(String id) {
    return given().when().get("/projects/api/work/" + id).then().statusCode(200);
  }

  private static void move(String id, String target) {
    person()
        .body(Map.of("target", target))
        .when()
        .post("/projects/api/work/" + id + "/status")
        .then()
        .statusCode(200)
        .body("status", equalTo(target));
  }

  /** One entry of the PUT-shaped door: an epic restated in full, with {@code criteria}. */
  private static ValidatableResponse restateEpic(
      String id, String title, String status, List<String> criteria) {
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("archetype", "EPIC");
    entry.put("title", title);
    entry.put("status", status);
    if (criteria != null) {
      entry.put("acceptanceCriteria", criteria);
    }
    return given()
        .contentType(ContentType.JSON)
        .body(Map.of(id, entry))
        .when()
        .post("/projects/api/work/transition")
        .then();
  }

  @Test
  void aPatchSetsReplacesAndClearsATicketsCriteria() {
    String ticket = EntityFixtures.ticket(EntityFixtures.project("Criteria Patch").id());
    read(ticket).body("acceptanceCriteria", equalTo(TestCriteria.CRITERIA));

    patch(ticket, map("acceptanceCriteria", List.of("The crash no longer occurs.", "A test pins it")))
        .statusCode(200)
        .body(
            "acceptanceCriteria",
            equalTo(List.of("The crash no longer occurs.", "A test pins it")));
    read(ticket)
        .body(
            "acceptanceCriteria",
            equalTo(List.of("The crash no longer occurs.", "A test pins it")));

    patch(ticket, map("acceptanceCriteria", List.of("Only this one.")))
        .statusCode(200)
        .body("acceptanceCriteria", equalTo(List.of("Only this one.")));

    patch(ticket, map("acceptanceCriteria", null))
        .statusCode(200)
        .body("acceptanceCriteria", equalTo(List.of()));
    read(ticket).body("acceptanceCriteria", equalTo(List.of()));
  }

  @Test
  void theCreateDoorTakesThemOnAnEpic() {
    EntityFixtures.Project project = EntityFixtures.project("Criteria Create");
    given()
        .contentType(ContentType.JSON)
        .body(
            map(
                "archetype", "EPIC",
                "project", project.id(),
                "title", "Planned with criteria",
                "acceptanceCriteria", List.of("It is planned.")))
        .when()
        .post("/projects/api/work")
        .then()
        .statusCode(201)
        .body("acceptanceCriteria", equalTo(List.of("It is planned.")));
  }

  @Test
  void aFeatureHasNoSlotForThemAndABrokenItemIsNamedByIndex() {
    String epic = EntityFixtures.epic(EntityFixtures.project("Criteria Refusals").id());
    String feature = EntityFixtures.feature(epic);
    patch(feature, map("acceptanceCriteria", List.of("Not here.")))
        .statusCode(400)
        .body("message", containsString("a FEATURE has no acceptance criteria"));
    read(feature).body("acceptanceCriteria", nullValue());

    patch(epic, map("acceptanceCriteria", List.of("Fine.", "a.b.c", "line\nbreak")))
        .statusCode(400)
        .body(
            "message",
            allOf(
                containsString("acceptanceCriteria[1]"),
                containsString("acceptanceCriteria[2]")));
    patch(epic, map("acceptanceCriteria", "not a list"))
        .statusCode(400)
        .body("message", containsString("acceptanceCriteria must be an array of strings"));
    read(epic).body("acceptanceCriteria", equalTo(TestCriteria.CRITERIA));
  }

  @Test
  void thePutDoorKeepsThemWhenStatedAndClearsThemWhenAbsent() {
    String epic = EntityFixtures.epic(EntityFixtures.project("Criteria Put").id());
    patch(epic, map("acceptanceCriteria", List.of("Kept.")))
        .statusCode(200);

    restateEpic(epic, "Retitled", "REPORTED", List.of("Kept."))
        .statusCode(200)
        .body(epic + ".acceptanceCriteria", equalTo(List.of("Kept.")));
    read(epic).body("title", equalTo("Retitled")).body("acceptanceCriteria", equalTo(List.of("Kept.")));

    restateEpic(epic, "Retitled again", "REPORTED", null)
        .statusCode(200)
        .body(epic + ".acceptanceCriteria", equalTo(List.of()));
    read(epic).body("acceptanceCriteria", equalTo(List.of()));
  }

  @Test
  void anEpicsCriteriaStayEditableAtRefinedAndFreezeFromReadyForDev() {
    String epic = EntityFixtures.epic(EntityFixtures.project("Criteria Freeze").id());
    patch(epic, map("acceptanceCriteria", List.of("First draft.")))
        .statusCode(200);
    move(epic, "REFINED");

    // Outside the scope freeze: the criteria move at REFINED, the title does not.
    patch(epic, map("acceptanceCriteria", List.of("Refined criterion.")))
        .statusCode(200)
        .body("acceptanceCriteria", equalTo(List.of("Refined criterion.")));
    patch(epic, map("title", "A new title"))
        .statusCode(409)
        .body("message", containsString("frozen"));

    move(epic, "READY_FOR_DEV");

    patch(epic, map("acceptanceCriteria", List.of("Changed after scheduling.")))
        .statusCode(409)
        .body("message", containsString("are frozen: it is READY_FOR_DEV"));
    patch(epic, map("acceptanceCriteria", null)).statusCode(409);
    // The same list restated is no change, through either door.
    patch(epic, map("acceptanceCriteria", List.of("Refined criterion.")))
        .statusCode(200);
    restateEpic(epic, "Restated with an edited title", "READY_FOR_DEV", List.of("Refined criterion."))
        .statusCode(200)
        .body(epic + ".acceptanceCriteria", equalTo(List.of("Refined criterion.")));
    restateEpic(epic, "Restated with an edited title", "READY_FOR_DEV", List.of("Another one."))
        .statusCode(409)
        .body("message", containsString("are frozen"));
    restateEpic(epic, "Restated with an edited title", "READY_FOR_DEV", null).statusCode(409);
    read(epic).body("acceptanceCriteria", equalTo(List.of("Refined criterion.")));

    // Unscheduled, they move again.
    move(epic, "REFINED");
    patch(epic, map("acceptanceCriteria", List.of("Changed once unscheduled.")))
        .statusCode(200);
  }
}
