package eu.wohlben.qits.entities.api;

import static eu.wohlben.qits.entities.api.EntityFixtures.map;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import eu.wohlben.qits.projects.refinementhost.FakeRefinementCredentials;
import eu.wohlben.qits.projects.refinementhost.FakeRefinementRuntime;
import eu.wohlben.qits.projects.security.FakeSessionIntrospection;
import eu.wohlben.qits.projects.security.PersonCheck;
import eu.wohlben.qits.projects.testsupport.RecordingWorkspaceAgentDispatch;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The work family's sub-resources ({@code /projects/api/work/{qualifiedId}/…}, qits-970) over HTTP:
 * the dossier (pages by slug, the id as a fallback), the epic's figures, the children, the history,
 * a campaign's progress and membership, the dispatch, the refinement room and the delete — each
 * reached by a qualified id in its path, and every entity id a body carries accepted as a qualified
 * id (a member's {@code entityId}, a condition's {@code predicate.entityId}, a child's {@code
 * dependsOn}). The rules behind each are the per-archetype routes' and asserted in their suites;
 * this class is about the addresses and the ids.
 *
 * <p>Fixtures are written through the older doors ({@link EntityFixtures}), never through the route
 * under test.
 */
@QuarkusTest
class WorkSubresourcesApiTest {

  private static final String WORK = "/projects/api/work/";

  @Inject RecordingWorkspaceAgentDispatch workspaces;

  @Inject FakeRefinementRuntime runtime;

  @Inject FakeRefinementCredentials credentials;

  @BeforeEach
  void resetThePorts() {
    workspaces.reset();
    runtime.reset();
    credentials.reset();
  }

  @AfterEach
  void forgetScriptedReferences() {
    workspaces.reset();
  }

  private static ValidatableResponse send(String method, String path, Object body) {
    var request = given().contentType(ContentType.JSON);
    if (body != null) {
      request = request.body(body);
    }
    return request.when().request(method, path).then();
  }

  // --- the dossier -----------------------------------------------------------------------------

  @Test
  void aTicketsDossierIsWrittenAndReadByQualifiedIdWithPagesBySlug() {
    EntityFixtures.Project project = EntityFixtures.project("Work Dossier");
    String ticket = EntityFixtures.ticket(project.id());
    String dossier = WORK + EntityFixtures.qualifiedId(ticket) + "/dossier";

    String pageId =
        send("POST", dossier, map("title", "Reproduction", "body", "Steps."))
            .statusCode(201)
            .body("slug", equalTo("reproduction"))
            .body("ticketId", equalTo(ticket))
            .extract()
            .path("id");
    send("POST", dossier, map("title", "Affected invoices", "body", "A table."))
        .statusCode(201);

    given().when().get(dossier).then().statusCode(200).body("pages", hasSize(2));
    // The slug is the key; the id is the fallback.
    given().when().get(dossier + "/reproduction").then().statusCode(200).body("id", equalTo(pageId));
    given().when().get(dossier + "/" + pageId).then().statusCode(200).body("slug", equalTo("reproduction"));

    send("PUT", dossier + "/reproduction", map("body", "Better steps.", "version", 0))
        .statusCode(200)
        .body("body", equalTo("Better steps."))
        .body("version", equalTo(1));
    // A stale version is the 409 carrying the page as it stands.
    send("PUT", dossier + "/reproduction", map("body", "Lost.", "version", 0))
        .statusCode(409)
        .body("current.body", equalTo("Better steps."));

    send("POST", dossier + "/affected-invoices/move", map("position", 0))
        .statusCode(200)
        .body("position", equalTo(0));
    send("DELETE", dossier + "/affected-invoices", null)
        .statusCode(200)
        .body("success", equalTo(true));
    given().when().get(dossier).then().statusCode(200).body("pages", hasSize(1));
    given().when().get(dossier + "/affected-invoices").then().statusCode(404);
  }

  @Test
  void anEpicsDossierIsTheSameFamilyAndAPageOfAnotherOwnerIsNotFound() {
    EntityFixtures.Project project = EntityFixtures.project("Work Epic Dossier");
    String epic = EntityFixtures.epic(project.id());
    String ticket = EntityFixtures.ticket(project.id());
    String epicDossier = WORK + EntityFixtures.qualifiedId(epic) + "/dossier";

    String pageId =
        send("POST", epicDossier, map("title", "Scope", "body", "In and out."))
            .statusCode(201)
            .body("epicId", equalTo(epic))
            .extract()
            .path("id");
    given().when().get(epicDossier + "/scope").then().statusCode(200).body("id", equalTo(pageId));
    // The same page under the ticket is not found, by slug or by id.
    String ticketDossier = WORK + EntityFixtures.qualifiedId(ticket) + "/dossier";
    given().when().get(ticketDossier + "/scope").then().statusCode(404);
    given().when().get(ticketDossier + "/" + pageId).then().statusCode(404);
  }

  @Test
  void anArchetypeWithNoDossierIsNotFound() {
    EntityFixtures.Project project = EntityFixtures.project("Work No Dossier");
    String feature = EntityFixtures.feature(EntityFixtures.epic(project.id()));
    String campaign = EntityFixtures.campaign(project.id());

    given()
        .when()
        .get(WORK + EntityFixtures.qualifiedId(feature) + "/dossier")
        .then()
        .statusCode(404)
        .body("message", containsString("has no dossier"));
    given().when().get(WORK + EntityFixtures.qualifiedId(campaign) + "/dossier").then().statusCode(404);
    given()
        .when()
        .get(WORK + EntityFixtures.qualifiedId(feature) + "/dossier-assets")
        .then()
        .statusCode(404);
  }

  // --- the figures -----------------------------------------------------------------------------

  @Test
  void anEpicsFiguresAreInlinedListedAndServedByQualifiedId() {
    EntityFixtures.Project project = EntityFixtures.project("Work Figures");
    String epic = EntityFixtures.epic(project.id());
    String qualified = EntityFixtures.qualifiedId(epic);
    long room =
        ((Number)
                send("POST", WORK + qualified + "/refinement", null)
                    .statusCode(200)
                    .body("refinement.entityId", equalTo(epic))
                    .extract()
                    .path("refinement.id"))
            .longValue();
    String sketch =
        send(
                "POST",
                "/projects/api/refinements/" + room + "/prompt-attachments",
                map(
                    "label",
                    "The claim loop",
                    "source",
                    "SKETCH",
                    "dataBase64",
                    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="))
            .statusCode(201)
            .extract()
            .path("id");

    String assets = WORK + qualified + "/dossier-assets";
    send("POST", assets, map("sourceId", sketch, "kind", "IMAGE"))
        .statusCode(200)
        .body("id", equalTo(sketch))
        .body(
            "markdown",
            equalTo("![The claim loop](/epics/" + epic + "/dossier-assets/" + sketch + "/content)"));
    // A page naming it keeps the copy; the listing shows which.
    send(
            "POST",
            WORK + qualified + "/dossier",
            map("title", "Flow", "body", "![The claim loop](/epics/" + epic + "/dossier-assets/" + sketch + "/content)"))
        .statusCode(201);
    given()
        .when()
        .get(assets)
        .then()
        .statusCode(200)
        .body("assets", hasSize(1))
        .body("assets[0].id", equalTo(sketch))
        .body("assets[0].pageIds", hasSize(1));
    given()
        .when()
        .get(assets + "/" + sketch + "/content")
        .then()
        .statusCode(200)
        .contentType("image/png")
        .header("Content-Security-Policy", DossierAssetContent.SANDBOX_CSP)
        .header("X-Content-Type-Options", "nosniff");
    given().when().get(assets + "/no-such-asset/content").then().statusCode(404);
  }

  // --- the children ----------------------------------------------------------------------------

  @Test
  void anEpicsFeaturesAndAFeaturesTasksAreItsChildren() {
    EntityFixtures.Project project = EntityFixtures.project("Work Children");
    String repository = EntityFixtures.repository(project.id());
    String epic = EntityFixtures.epic(project.id());
    String epicQualified = EntityFixtures.qualifiedId(epic);

    ValidatableResponse first =
        send("POST", WORK + epicQualified + "/children", map("title", "First"))
            .statusCode(201)
            .body("archetype", equalTo("FEATURE"))
            .body("parent", equalTo(epic));
    String firstId = first.extract().path("id");
    String firstQualified = first.extract().path("qualifiedId");
    send(
            "POST",
            WORK + epicQualified + "/children",
            map("title", "Second", "dependsOn", firstQualified))
        .statusCode(201)
        .body("dependsOn", equalTo(firstId));

    given()
        .when()
        .get(WORK + epicQualified + "/children")
        .then()
        .statusCode(200)
        .body("children", hasSize(2))
        .body("children[0].id", equalTo(firstId))
        .body("children[0].qualifiedId", equalTo(firstQualified));

    String task =
        send(
                "POST",
                WORK + firstQualified + "/children",
                map("title", "A step", "repositoryId", repository))
            .statusCode(201)
            .body("archetype", equalTo("TASK"))
            .extract()
            .path("id");
    given()
        .when()
        .get(WORK + firstId + "/children")
        .then()
        .statusCode(200)
        .body("children", hasSize(1))
        .body("children[0].id", equalTo(task));

    // A task has none, a body naming what the path decides is a 400.
    String taskQualified = EntityFixtures.qualifiedId(task);
    given().when().get(WORK + taskQualified + "/children").then().statusCode(200).body("children", hasSize(0));
    send("POST", WORK + taskQualified + "/children", map("title", "Nope")).statusCode(409);
    send("POST", WORK + epicQualified + "/children", map("title", "Nope", "archetype", "TASK"))
        .statusCode(400)
        .body("message", containsString("decided by the path"));
  }

  // --- the history -----------------------------------------------------------------------------

  @Test
  void theHistoryIsReadByQualifiedIdAndOutlivesTheEntity() {
    EntityFixtures.Project project = EntityFixtures.project("Work Audit");
    String epic = EntityFixtures.epic(project.id());
    String feature = EntityFixtures.feature(epic);

    given()
        .when()
        .get(WORK + EntityFixtures.qualifiedId(epic) + "/audit")
        .then()
        .statusCode(200)
        .body("entries.entityId", hasItem(feature))
        .body("entries.entityId", hasItem(epic));
    given()
        .when()
        .get(WORK + EntityFixtures.qualifiedId(feature) + "/audit")
        .then()
        .statusCode(200)
        .body("entries", hasSize(1))
        .body("entries[0].entityType", equalTo("FEATURE"));

    send("DELETE", WORK + EntityFixtures.qualifiedId(epic), null).statusCode(200);
    // By UUID the log still answers; a qualified id has nothing left to resolve through.
    given()
        .when()
        .get(WORK + epic + "/audit")
        .then()
        .statusCode(200)
        .body("entries.operation", hasItem("DELETE"));
    given().when().get(WORK + project.slug() + "-99999/audit").then().statusCode(404);
  }

  // --- the delete ------------------------------------------------------------------------------

  @Test
  void anEntityIsDeletedByQualifiedIdAndACampaignIsNot() {
    EntityFixtures.Project project = EntityFixtures.project("Work Delete");
    String ticket = EntityFixtures.ticket(project.id());
    String campaign = EntityFixtures.campaign(project.id());
    String qualified = EntityFixtures.qualifiedId(ticket);

    send("DELETE", WORK + qualified, null).statusCode(200).body("success", equalTo(true));
    given().when().get(WORK + ticket).then().statusCode(404);
    send("DELETE", WORK + EntityFixtures.qualifiedId(campaign), null)
        .statusCode(409)
        .body("message", containsString("drop it"));
  }

  // --- a campaign ------------------------------------------------------------------------------

  @Test
  void aCampaignsMembersAreWrittenByQualifiedIds() {
    EntityFixtures.Project project = EntityFixtures.project("Work Members");
    String campaign = EntityFixtures.campaign(project.id());
    String first = EntityFixtures.ticket(project.id());
    String second = EntityFixtures.ticket(project.id());
    String members = WORK + EntityFixtures.qualifiedId(campaign) + "/members";

    String firstMembership =
        send("POST", members, map("entityId", EntityFixtures.qualifiedId(first)))
            .statusCode(201)
            .body("member.entity.id", equalTo(first))
            .extract()
            .path("member.membershipId");
    String secondMembership =
        send("POST", members, map("entityId", second))
            .statusCode(201)
            .extract()
            .path("member.membershipId");
    send("POST", members, map("entityId", project.slug() + "-99999")).statusCode(404);

    given()
        .when()
        .get(members)
        .then()
        .statusCode(200)
        .body("members", hasSize(2))
        .body("members[0].membershipId", equalTo(firstMembership));

    send("PUT", members + "/" + secondMembership + "/position", map("position", 0))
        .statusCode(200)
        .body("members[0].membershipId", equalTo(secondMembership));

    // A condition naming its target by qualified id is stored by UUID.
    send(
            "PUT",
            members + "/" + firstMembership + "/condition",
            Map.of(
                "groups",
                List.of(
                    Map.of(
                        "criteria",
                        List.of(
                            Map.of(
                                "kind",
                                "ENTITY_STATUS",
                                "predicate",
                                Map.of(
                                    "entityId",
                                    EntityFixtures.qualifiedId(second),
                                    "status",
                                    "VERIFIED")),
                            Map.of("kind", "APPROVAL"))))))
        .statusCode(200)
        .body("member.groups[0].criteria[0].predicate.entityId", equalTo(second));

    String approval =
        given()
            .when()
            .get(members)
            .then()
            .extract()
            .path("members.find { it.membershipId == '" + firstMembership + "' }.groups[0].criteria.find { it.kind == 'APPROVAL' }.id");
    // Approve is a person's: asserted headers alone are refused, a session is a person.
    send(
            "POST",
            members + "/" + firstMembership + "/criteria/" + approval + "/approve",
            map("note", "ok"))
        .statusCode(403);
    given()
        .cookie(PersonCheck.SESSION_COOKIE, FakeSessionIntrospection.admin("dana"))
        .contentType(ContentType.JSON)
        .body(map("note", "Signed off."))
        .when()
        .post(members + "/" + firstMembership + "/criteria/" + approval + "/approve")
        .then()
        .statusCode(200)
        .body("member.membershipId", equalTo(firstMembership));

    send("DELETE", members + "/" + secondMembership, null)
        .statusCode(409); // the first member's criterion targets it
    send(
            "PUT",
            members + "/" + firstMembership + "/condition",
            Map.of("groups", List.of()))
        .statusCode(200);
    send("DELETE", members + "/" + secondMembership, null)
        .statusCode(200)
        .body("members", hasSize(1))
        .body("members[0].membershipId", equalTo(firstMembership));

    given()
        .when()
        .get(WORK + EntityFixtures.qualifiedId(campaign) + "/progress")
        .then()
        .statusCode(200)
        .body("progress", notNullValue());
    // Members and progress are a campaign's.
    given().when().get(WORK + EntityFixtures.qualifiedId(first) + "/members").then().statusCode(404);
    given().when().get(WORK + EntityFixtures.qualifiedId(first) + "/progress").then().statusCode(404);
  }

  // --- the dispatch and the refinement room ----------------------------------------------------

  @Test
  void theDispatchIsReadAndPressedByQualifiedId() {
    EntityFixtures.Project project = EntityFixtures.project("Work Dispatch");
    String ticket = EntityFixtures.ticket(project.id());
    String dispatch = WORK + EntityFixtures.qualifiedId(ticket) + "/dispatch";

    given()
        .when()
        .get(dispatch)
        .then()
        .statusCode(200)
        .body("state.entityId", equalTo(ticket))
        .body("state.nextPhase", equalTo("refine"));
    send("POST", dispatch, map()).statusCode(400).body("message", containsString("mode is required"));
    send("POST", dispatch, map("mode", "PHASE"))
        .statusCode(200)
        .body("dispatch.entityId", equalTo(ticket))
        .body("dispatch.mode", equalTo("PHASE"));
    send("POST", WORK + project.slug() + "-99999/dispatch", map("mode", "PHASE")).statusCode(404);
  }

  @Test
  void theWorkspacesNamingAnEntityAreReadByQualifiedId() {
    EntityFixtures.Project project = EntityFixtures.project("Work Workspaces");
    String ticket = EntityFixtures.ticket(project.id());
    String other = EntityFixtures.ticket(project.id());
    workspaces.willReference(
        RecordingWorkspaceAgentDispatch.live(41, "wrapper", "ws-41", "ticket/x", ticket, null));

    given()
        .when()
        .get(WORK + EntityFixtures.qualifiedId(ticket) + "/workspaces")
        .then()
        .statusCode(200)
        .body("workspaces", hasSize(1))
        .body("workspaces[0].workspaceId", equalTo("ws-41"));
    given()
        .when()
        .get(WORK + EntityFixtures.qualifiedId(other) + "/workspaces")
        .then()
        .statusCode(200)
        .body("workspaces", hasSize(0));
    given().when().get(WORK + project.slug() + "-99999/workspaces").then().statusCode(404);
  }

  @Test
  void theRefinementRoomIsFoundAndOpenedByQualifiedId() {
    EntityFixtures.Project project = EntityFixtures.project("Work Refinement");
    String ticket = EntityFixtures.ticket(project.id());
    String refinement = WORK + EntityFixtures.qualifiedId(ticket) + "/refinement";

    given().when().get(refinement).then().statusCode(200).body("refinement", nullValue());
    Number opened =
        send("POST", refinement, null)
            .statusCode(200)
            .body("refinement.entityId", equalTo(ticket))
            .extract()
            .path("refinement.id");
    given()
        .when()
        .get(refinement)
        .then()
        .statusCode(200)
        .body("refinement.id", equalTo(opened.intValue()));
    given().when().get(WORK + project.slug() + "-99999/refinement").then().statusCode(404);
  }
}
