package eu.wohlben.qits.entities.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

import eu.wohlben.qits.projects.api.ProjectController;
import eu.wohlben.qits.projects.api.ProjectRequests;
import eu.wohlben.qits.projects.contracts.ContractBearers;
import eu.wohlben.qits.projects.security.FakeSessionIntrospection;
import eu.wohlben.qits.projects.security.PersonCheck;
import eu.wohlben.qits.servicemock.idp.MockIdp;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * <b>The ISSUE archetype at the door</b> (qits-1819). The tenant is on and the dev user is blanked
 * ({@link PersonGateIdpTenant}), so a caller is what it sends, and each refusal below can fail.
 */
@QuarkusTest
@TestSecurity(user = "dev", roles = "qits:admin")
@WithTestResource(PersonGateIdpTenant.class)
class WorkIssueApiTest {

  private static RequestSpecification person() {
    return given()
        .header("Authorization", ContractBearers.caller("ada", "qits:admin"))
        .cookie(PersonCheck.SESSION_COOKIE, FakeSessionIntrospection.admin("ada"))
        .contentType(ContentType.JSON);
  }

  /** {@code qits:admin} asserted in headers, which is no proof of a person. */
  private static RequestSpecification adminHeaders() {
    return given()
        .header("Authorization", ContractBearers.caller("mallory", "qits:admin"))
        .contentType(ContentType.JSON);
  }

  private static RequestSpecification bearer(String token) {
    return given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON);
  }

  private static RequestSpecification desk(String projectId, String... roles) {
    String subject = "desk-of-" + projectId;
    List<String> groups = new java.util.ArrayList<>(List.of(roles));
    groups.add("clients/" + subject);
    return bearer(
        MockIdp.attach()
            .token()
            .subject(subject)
            .audience("qits-platform")
            .groups(groups.toArray(String[]::new))
            .claim("context_kind", "desk-delivery")
            .claim("project", projectId)
            .mint());
  }

  private static RequestSpecification desk(String projectId) {
    return desk(projectId, "qits:agent", "qits:desk", "qits:desk-admin");
  }

  private static RequestSpecification machine(String projectId, String... roles) {
    return bearer(
        MockIdp.attach()
            .token()
            .subject("agent-7")
            .audience("qits-platform")
            .groups(roles)
            .claim("credential_type", "cli")
            .claim("context_kind", "agent-container")
            .claim("project", projectId)
            .mint());
  }

  private static String project(String name) {
    return person()
        .body(new ProjectController.CreateProjectRequest(name, null, null, null, ProjectRequests.DNS))
        .when()
        .post("/projects/api/projects")
        .then()
        .statusCode(200)
        .extract()
        .path("project.id");
  }

  private static Map<String, Object> issueBody(String projectId, String description) {
    Map<String, Object> body = new HashMap<>();
    body.put("archetype", "ISSUE");
    body.put("project", projectId);
    body.put("title", "Needs a person");
    body.put("description", description);
    return body;
  }

  private static String issue(String projectId) {
    return desk(projectId)
        .body(issueBody(projectId, "qits-ci refuses the retry. A person must lift the lease."))
        .when()
        .post("/projects/api/work")
        .then()
        .statusCode(201)
        .extract()
        .path("id");
  }

  private static String ticket(String projectId) {
    return person()
        .body(
            Map.of(
                "archetype", "TICKET",
                "project", projectId,
                "title", "A ticket",
                "ticketType", "BUG",
                "impetus", "It broke"))
        .when()
        .post("/projects/api/work")
        .then()
        .statusCode(201)
        .extract()
        .path("id");
  }

  private static io.restassured.response.ValidatableResponse move(
      RequestSpecification who, String id, Map<String, Object> body) {
    return who.body(body).when().post("/projects/api/work/" + id + "/status").then();
  }

  // --- create ------------------------------------------------------------------------------------

  @Test
  void theDeskFilesAnIssueItStartsOpenWithNoBlockAndNoAssignee() {
    String projectId = project("issue-files");
    desk(projectId)
        .body(issueBody(projectId, "A short need."))
        .when()
        .post("/projects/api/work")
        .then()
        .statusCode(201)
        .body("archetype", equalTo("ISSUE"))
        .body("status", equalTo("OPEN"))
        .body("createdBy", equalTo("desk-of-" + projectId));
  }

  @Test
  void aDeskTokenWithoutDeskAdminFilesOneToo() {
    String projectId = project("issue-desk-only");
    desk(projectId, "qits:agent", "qits:desk")
        .body(issueBody(projectId, "A short need."))
        .when()
        .post("/projects/api/work")
        .then()
        .statusCode(201);
  }

  @Test
  void everyOtherRoleIsRefusedACreate() {
    String projectId = project("issue-refused");
    Map<String, Object> body = issueBody(projectId, "A short need.");
    List<RequestSpecification> refused =
        List.of(
            person(),
            adminHeaders(),
            machine(projectId, "qits:admin"),
            machine(projectId, "qits:agent", "qits:admin-agent"),
            machine(projectId, "qits:agent"),
            machine(projectId, "qits:system"));
    for (RequestSpecification caller : refused) {
      caller
          .body(body)
          .when()
          .post("/projects/api/work")
          .then()
          .statusCode(403)
          .body(containsString("Only the delivery desk creates an issue"));
    }
    // The same callers may file a ticket, so the 403 above is the issue rule and not the role list.
    ticket(projectId);
  }

  @Test
  void anotherProjectsDeskIsRefused() {
    String mine = project("issue-mine");
    String theirs = project("issue-theirs");
    desk(theirs).body(issueBody(mine, "A short need.")).when().post("/projects/api/work").then().statusCode(403);
  }

  @Test
  void aDescriptionOverTheLimitIsA400AndTheLimitItselfPasses() {
    String projectId = project("issue-long");
    desk(projectId)
        .body(issueBody(projectId, "x".repeat(281)))
        .when()
        .post("/projects/api/work")
        .then()
        .statusCode(400)
        .body(containsString("280"));
    String id =
        desk(projectId)
            .body(issueBody(projectId, "x".repeat(280)))
            .when()
            .post("/projects/api/work")
            .then()
            .statusCode(201)
            .extract()
            .path("id");
    desk(projectId)
        .contentType("application/merge-patch+json")
        .body(Map.of("description", "y".repeat(281)))
        .when()
        .patch("/projects/api/work/" + id)
        .then()
        .statusCode(400)
        .body(containsString("280"));
  }

  @Test
  void aDescriptionIsRequired() {
    String projectId = project("issue-no-text");
    Map<String, Object> body = issueBody(projectId, null);
    body.remove("description");
    desk(projectId).body(body).when().post("/projects/api/work").then().statusCode(400);
  }

  @Test
  void itMayRelateToOneItemOfItsProjectByQualifiedId() {
    String projectId = project("issue-related");
    String other = project("issue-related-other");
    String ticket = ticket(projectId);
    String qualified = person().when().get("/projects/api/work/" + ticket).then().extract().path("qualifiedId");
    Map<String, Object> body = issueBody(projectId, "A short need.");
    body.put("relatedTo", qualified);
    desk(projectId)
        .body(body)
        .when()
        .post("/projects/api/work")
        .then()
        .statusCode(201)
        .body("relatedTo", equalTo(ticket));
    body.put("relatedTo", ticket(other));
    desk(projectId).body(body).when().post("/projects/api/work").then().statusCode(400);
  }

  // --- nothing runs on it ------------------------------------------------------------------------

  @Test
  void anIssueIsNotDispatchedBlockedRefinedOrDeleted() {
    String projectId = project("issue-inert");
    String id = issue(projectId);
    person()
        .body(Map.of("mode", "FLOW"))
        .when()
        .post("/projects/api/work/" + id + "/dispatch")
        .then()
        .statusCode(409)
        .body(containsString("never dispatched"));
    person()
        .when()
        .get("/projects/api/work/" + id + "/dispatch")
        .then()
        .statusCode(200)
        .body("state.dispatchable", equalTo(false));
    person()
        .body(Map.of("blocked", true, "reason", "no"))
        .when()
        .post("/projects/api/work/" + id + "/blocked")
        .then()
        .statusCode(409);
    person().when().post("/projects/api/work/" + id + "/refinement").then().statusCode(409);
    person().when().delete("/projects/api/work/" + id).then().statusCode(409);
    person().when().get("/projects/api/work/" + id + "/workspaces").then().statusCode(200);
  }

  @Test
  void aCampaignDoesNotGatherAnIssueAndNothingHangsUnderOne() {
    String projectId = project("issue-alone");
    String id = issue(projectId);
    String campaign =
        person()
            .body(Map.of("archetype", "CAMPAIGN", "project", projectId, "title", "C"))
            .when()
            .post("/projects/api/work")
            .then()
            .statusCode(201)
            .extract()
            .path("id");
    person()
        .body(Map.of("entityId", id))
        .when()
        .post("/projects/api/work/" + campaign + "/members")
        .then()
        .statusCode(org.hamcrest.Matchers.greaterThanOrEqualTo(400));
    person()
        .body(Map.of("title", "child"))
        .when()
        .post("/projects/api/work/" + id + "/children")
        .then()
        .statusCode(org.hamcrest.Matchers.greaterThanOrEqualTo(400));
  }

  @Test
  void aReshapeNeitherMakesNorChangesAnIssue() {
    String projectId = project("issue-reshape");
    String id = issue(projectId);
    String ticket = ticket(projectId);
    person()
        .body(Map.of(ticket, Map.of("archetype", "ISSUE", "title", "Now an issue", "description", "d", "status", "OPEN")))
        .when()
        .post("/projects/api/work/transition")
        .then()
        .statusCode(409)
        .body(containsString("not reshaped"));
    machine(projectId, "qits:agent")
        .body(Map.of(id, Map.of("archetype", "ISSUE", "title", "t", "description", "d", "status", "RESOLVED")))
        .when()
        .post("/projects/api/work/transition")
        .then()
        .statusCode(409)
        .body(containsString("not reshaped"));
    person().when().get("/projects/api/work/" + id).then().body("status", equalTo("OPEN"));
  }

  // --- moves -------------------------------------------------------------------------------------

  @Test
  void onlyAPersonResolvesAndTheThreadCarriesTheAnswer() {
    String projectId = project("issue-resolve");
    String id = issue(projectId);
    for (RequestSpecification refused :
        List.of(
            desk(projectId),
            adminHeaders(),
            machine(projectId, "qits:agent"),
            machine(projectId, "qits:agent", "qits:admin-agent"),
            machine(projectId, "qits:system"))) {
      move(refused, id, Map.of("target", "RESOLVED")).statusCode(403);
    }
    person().when().get("/projects/api/work/" + id).then().body("status", equalTo("OPEN"));

    move(person(), id, Map.of("target", "RESOLVED", "reason", "Lease lifted.\nGo on."))
        .statusCode(200)
        .body("status", equalTo("RESOLVED"));
    person()
        .when()
        .get("/projects/api/work/" + id + "/comments")
        .then()
        .body("entries.comment.body", hasItem("Lease lifted.\nGo on."));
    move(person(), id, Map.of("target", "OBSOLETE", "reason", "late")).statusCode(409);
  }

  @Test
  void theDeskMakesItsIssueObsoleteWithOneLine() {
    String projectId = project("issue-obsolete");
    String id = issue(projectId);
    move(desk(projectId), id, Map.of("target", "OBSOLETE")).statusCode(400);
    move(desk(projectId), id, Map.of("target", "OBSOLETE", "reason", "one\ntwo")).statusCode(400);
    move(machine(projectId, "qits:agent"), id, Map.of("target", "OBSOLETE", "reason", "gone"))
        .statusCode(403);
    String other = project("issue-obsolete-other");
    move(desk(other), id, Map.of("target", "OBSOLETE", "reason", "gone")).statusCode(403);

    move(desk(projectId), id, Map.of("target", "OBSOLETE", "reason", "The lease ended."))
        .statusCode(200)
        .body("status", equalTo("OBSOLETE"));
    person()
        .when()
        .get("/projects/api/work/" + id + "/comments")
        .then()
        .body("entries.comment.body", hasItem("The lease ended."));
  }

  @Test
  void anIssueTakesNoWordOfTheLifecycleAndATicketNoneOfAnIssues() {
    String projectId = project("issue-words");
    String id = issue(projectId);
    String ticket = ticket(projectId);
    move(person(), id, Map.of("target", "DONE")).statusCode(409);
    move(person(), ticket, Map.of("target", "RESOLVED")).statusCode(409);
    move(person(), ticket, Map.of("target", "REFINED", "reason", "no")).statusCode(400);
  }

  @Test
  void theListingShowsTheOpenOnes() {
    String projectId = project("issue-list");
    String open = issue(projectId);
    String gone = issue(projectId);
    move(desk(projectId), gone, Map.of("target", "OBSOLETE", "reason", "gone")).statusCode(200);
    ticket(projectId);
    person()
        .queryParam("archetype", "ISSUE")
        .queryParam("status", "OPEN")
        .when()
        .get("/projects/api/projects/" + projectId + "/work")
        .then()
        .statusCode(200)
        .body("entities.size()", equalTo(1))
        .body("entities[0].id", equalTo(open));
  }
}
