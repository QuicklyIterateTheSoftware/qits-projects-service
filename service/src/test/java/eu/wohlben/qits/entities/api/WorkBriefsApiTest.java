package eu.wohlben.qits.entities.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

import eu.wohlben.qits.projects.contracts.ContractBearers;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code GET /projects/api/projects/{project}/work/briefs} (qits-1897): the named items of a
 * project in brief, the misses left out, duplicates collapsed, the request's order kept.
 */
@QuarkusTest
@TestSecurity(user = "dev", roles = "qits:admin")
class WorkBriefsApiTest {

  private EntityFixtures.Project project;
  private String epic;
  private String epicQualified;
  private String ticket;
  private String ticketQualified;
  private String taskQualified;

  @BeforeEach
  void seed() {
    project = EntityFixtures.project("Work Briefs");
    String repository = EntityFixtures.repository(project.id());
    epic = EntityFixtures.epic(project.id());
    String feature = EntityFixtures.feature(epic);
    String task = EntityFixtures.task(feature, repository);
    ticket = EntityFixtures.ticket(project.id());
    epicQualified = qualified(epic);
    ticketQualified = qualified(ticket);
    taskQualified = qualified(task);
  }

  private static String qualified(String id) {
    return given().get("/projects/api/work/" + id).then().statusCode(200).extract().path("qualifiedId");
  }

  private String path() {
    return "/projects/api/projects/" + project.slug() + "/work/briefs";
  }

  @Test
  void theNamedItemsAnswerInBriefInTheRequestsOrder() {
    given()
        .queryParam("id", ticketQualified, epicQualified, taskQualified)
        .get(path())
        .then()
        .statusCode(200)
        .body("briefs.qualifiedId", contains(ticketQualified, epicQualified, taskQualified))
        .body("briefs[0].id", equalTo(ticket))
        .body("briefs[0].archetype", equalTo("TICKET"))
        .body("briefs[0].status", notNullValue())
        .body("briefs[0].title", notNullValue())
        .body("briefs[0].updatedAt", notNullValue())
        .body("briefs[1].id", equalTo(epic))
        .body("briefs[1].archetype", equalTo("EPIC"))
        .body("briefs[2].archetype", equalTo("TASK"))
        .body(
            "briefs[0].keySet()",
            containsInAnyOrder("id", "qualifiedId", "archetype", "status", "title", "updatedAt"));
  }

  @Test
  void theProjectIsItsIdOrItsSlugAndCommaSeparatedValuesCount() {
    given()
        .queryParam("id", epicQualified + "," + ticketQualified)
        .get("/projects/api/projects/" + project.id() + "/work/briefs")
        .then()
        .statusCode(200)
        .body("briefs.qualifiedId", contains(epicQualified, ticketQualified));
  }

  @Test
  void anUnknownOrMalformedIdIsLeftOut() {
    given()
        .queryParam("id", project.slug() + "-9999", epicQualified, "not an id", "", "-3")
        .get(path())
        .then()
        .statusCode(200)
        .body("briefs.qualifiedId", contains(epicQualified));
  }

  @Test
  void anotherProjectsIdIsLeftOut() {
    EntityFixtures.Project other = EntityFixtures.project("Other Briefs");
    String foreign = qualified(EntityFixtures.ticket(other.id()));
    given()
        .queryParam("id", foreign, ticketQualified)
        .get(path())
        .then()
        .statusCode(200)
        .body("briefs.qualifiedId", contains(ticketQualified));
  }

  @Test
  void duplicatesCollapse() {
    given()
        .queryParam("id", epicQualified, ticketQualified, epicQualified, epicQualified.toUpperCase())
        .get(path())
        .then()
        .statusCode(200)
        .body("briefs.qualifiedId", contains(epicQualified, ticketQualified));
  }

  @Test
  void noIdsAnswerEmpty() {
    given().get(path()).then().statusCode(200).body("briefs", hasSize(0));
  }

  @Test
  void moreThanTwoHundredDistinctIdsIsA400() {
    List<String> ids = new ArrayList<>();
    IntStream.rangeClosed(1, 201).forEach(n -> ids.add(project.slug() + "-" + n));
    given().queryParam("id", ids).get(path()).then().statusCode(400);
    given()
        .queryParam("id", ids.subList(0, 200))
        .get(path())
        .then()
        .statusCode(200)
        .body("briefs", hasSize(4));
  }

  @Test
  void anUnknownProjectIsA404() {
    given()
        .queryParam("id", epicQualified)
        .get("/projects/api/projects/no-such-project/work/briefs")
        .then()
        .statusCode(404);
  }

  @Test
  void anAgentReads() {
    given()
        .header("Authorization", ContractBearers.caller("someone", "qits:agent"))
        .queryParam("id", epicQualified)
        .get(path())
        .then()
        .statusCode(200)
        .body("briefs.qualifiedId", contains(epicQualified));
  }
}
