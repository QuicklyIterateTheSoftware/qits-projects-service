package eu.wohlben.qits.entities.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.entities.api.CampaignDtos.CampaignMemberDto;
import eu.wohlben.qits.entities.campaign.CampaignService;
import eu.wohlben.qits.entities.control.EntityWrite;
import eu.wohlben.qits.entities.control.Mover;
import eu.wohlben.qits.entities.control.WorkEntityService;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.entities.error.ForbiddenException;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.security.AgentTokens;
import eu.wohlben.qits.projects.security.FakeSessionIntrospection;
import eu.wohlben.qits.projects.security.PersonCheck;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A campaign over HTTP (qits-413), on the {@code /work} family since qits-976 deleted the {@code
 * /campaigns} routes: created with {@code POST /work} (archetype CAMPAIGN), its membership on
 * {@code /work/{qualifiedId}/members…}, its progress on {@code …/progress}, its status moved with
 * {@code POST /work/{qualifiedId}/status}, listed with {@code GET /projects/{p}/work?archetype=
 * CAMPAIGN}. The addresses themselves — every id in a path or a body taken as a qualified id, the
 * members and the progress a campaign's alone — are {@code WorkSubresourcesApiTest}'s; this class is
 * about the rules and the roles.
 *
 * <p>{@code EntityAgentBoundsTest} explains the split this class follows: roles are read over HTTP
 * with a forwarded {@code X-Qits-Roles: qits:agent}, and the claim binding — which a forwarded agent
 * can never satisfy, having no token — is driven directly through {@link CampaignDoors}, the bean
 * {@link WorkMembersController} delegates to, with {@link AgentTokens} identities.
 *
 * <p>No {@code @TestProfile} (the test-profile budget rule).
 */
@QuarkusTest
class WorkCampaignApiTest {

  private static final String PROJECT = "campaign-api";
  private static final String FOREIGN_PROJECT = "campaign-api-foreign";
  private static final String WORK = "/projects/api/work/";

  private static final SecurityIdentity AGENT =
      AgentTokens.token(Map.of("project", PROJECT), "qits:agent");

  private static final SecurityIdentity FOREIGN_AGENT =
      AgentTokens.token(Map.of("project", FOREIGN_PROJECT), "qits:agent");

  @Inject WorkEntityService workEntities;
  @Inject CampaignService campaigns;
  @Inject CampaignDoors doors;

  @BeforeEach
  void seed() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              seedProject(PROJECT);
              seedProject(FOREIGN_PROJECT);
            });
  }

  // --- the routes and their JSON -------------------------------------------------------------------

  @Test
  void aCampaignIsBuiltOrderedGatedApprovedAndMovedThroughItsDoors() {
    WorkEntity a = ticket("First");
    WorkEntity b = ticket("Second");

    String campaignId =
        WorkRequests.create(
                WorkRequests.map(
                    "archetype", "CAMPAIGN",
                    "project", PROJECT,
                    "title", "Rename qits-x",
                    "description", "the order"))
            .then()
            .body("archetype", equalTo("CAMPAIGN"))
            .body("status", equalTo("REPORTED"))
            .body("qualifiedId", equalTo(PROJECT + "-" + numberOf("Rename qits-x")))
            .extract()
            .path("id");
    String qualified = PROJECT + "-" + numberOf("Rename qits-x");
    String base = WORK + qualified;
    String members = base + "/members";
    given().get(members).then().statusCode(200).body("members.size()", equalTo(0));
    given().get(base + "/progress").then().statusCode(200).body("progress.campaign.start", nullValue());

    given()
        .contentType(ContentType.JSON)
        .body(Map.of("entityId", a.id))
        .post(members)
        .then()
        .statusCode(201)
        .body("member.position", equalTo(0))
        .body("member.entity.qualifiedId", equalTo(PROJECT + "-" + a.number))
        .body("member.entity.archetype", equalTo("TICKET"))
        .body("member.joinedRunning", equalTo(false))
        .body("member.dispatch.workspaceId", nullValue())
        .body("member.groups.size()", equalTo(0));
    String second =
        given()
            .contentType(ContentType.JSON)
            .body(Map.of("entityId", b.id))
            .post(members)
            .then()
            .statusCode(201)
            // The second member is seeded to wait on the first.
            .body("member.groups[0].criteria[0].kind", equalTo("ENTITY_STATUS"))
            .body("member.groups[0].criteria[0].seeded", equalTo(true))
            .body("member.groups[0].criteria[0].predicate.entityId", equalTo(a.id))
            .body("member.groups[0].criteria[0].predicate.status", equalTo("VERIFIED"))
            .body("member.groups[0].criteria[0].evidence", nullValue())
            .extract()
            .path("member.membershipId");

    given()
        .contentType(ContentType.JSON)
        .body(
            Map.of(
                "groups",
                List.of(
                    Map.of(
                        "criteria",
                        List.of(Map.of("kind", "APPROVAL", "predicate", Map.of()))))))
        .put(members + "/" + second + "/condition")
        .then()
        .statusCode(200)
        .body("member.groups[0].criteria[0].kind", equalTo("APPROVAL"));
    String criterionId =
        given().get(members).then().statusCode(200).extract().path("members[1].groups[0].criteria[0].id");

    // The %test dev user is qits:admin by header alone, which is not a person (qits-891).
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("note", "go"))
        .post(members + "/" + second + "/criteria/" + criterionId + "/approve")
        .then()
        .statusCode(403);
    asPerson("ada")
        .body(Map.of("note", "go"))
        .post(members + "/" + second + "/criteria/" + criterionId + "/approve")
        .then()
        .statusCode(200)
        .body("member.groups[0].criteria[0].satisfiedAt", notNullValue())
        .body("member.groups[0].criteria[0].approval.approvedBy", equalTo("ada"))
        .body("member.groups[0].criteria[0].approval.note", equalTo("go"));
    asPerson("ada")
        .body(Map.of())
        .post(members + "/" + second + "/criteria/" + criterionId + "/approve")
        .then()
        .statusCode(409)
        .body("message", containsString("already approved by"));

    given()
        .contentType(ContentType.JSON)
        .body(Map.of("position", 0))
        .put(members + "/" + second + "/position")
        .then()
        .statusCode(200)
        .body("members[0].membershipId", equalTo(second));

    move(base, "REFINED").statusCode(200).body("status", equalTo("REFINED"));
    // A campaign never enters IMPLEMENTING (qits-749): a 409, and it stays REFINED.
    move(base, "IMPLEMENTING")
        .statusCode(409)
        .body("message", containsString("never moves to IMPLEMENTING"));
    // ... it is READY_FOR_DEV only once its members are (qits-942, MEMBERS_SCHEDULED) ...
    move(base, "READY_FOR_DEV")
        .statusCode(409)
        .body("message", containsString("MEMBERS_SCHEDULED: 2 members are not READY_FOR_DEV yet"));
    given().get(base).then().statusCode(200).body("status", equalTo("REFINED"));
    for (WorkEntity member : List.of(a, b)) {
      for (String status : List.of("REFINED", "READY_FOR_DEV")) {
        workEntities.transition(Archetype.TICKET, member.id, status, Mover.person("dev"));
      }
    }
    // ... it walks READY_FOR_DEV (qits-887), and its BACK moves are IMPLEMENTED -> READY_FOR_DEV and
    // VERIFIED -> IMPLEMENTED, with VERIFYING refused the same way (qits-749), through the same door.
    for (String target :
        List.of("READY_FOR_DEV", "IMPLEMENTED", "VERIFIED", "IMPLEMENTED", "READY_FOR_DEV", "REFINED")) {
      if (target.equals("VERIFIED")) {
        move(base, "VERIFYING")
            .statusCode(409)
            .body("message", containsString("never moves to VERIFYING"));
      }
      move(base, target).statusCode(200).body("status", equalTo(target));
    }

    // The listing names it among the project's campaigns; its members and its unstarted state are
    // the members' and the progress' reads (the listing is the work summary, archetype-free).
    given()
        .get("/projects/api/projects/" + PROJECT + "/work?archetype=CAMPAIGN")
        .then()
        .statusCode(200)
        .body("entities.id", hasItem(campaignId))
        .body("entities.find { it.id == '" + campaignId + "' }.status", equalTo("REFINED"))
        .body("entities.archetype.unique()", equalTo(List.of("CAMPAIGN")));
    given().get(members).then().body("members.size()", equalTo(2));
    given().get(base + "/progress").then().body("progress.campaign.start", nullValue());

    given()
        .delete(members + "/" + second)
        .then()
        .statusCode(200)
        .body("members.size()", equalTo(1));
    given().get(members).then().body("members.size()", equalTo(1));
  }

  @Test
  void theRefusalsAnswerAsDocumented() {
    WorkEntity campaign = workEntities.createCampaign(PROJECT, "Refusals", null, "t");
    WorkEntity epic =
        workEntities
            .create(
                Archetype.EPIC,
                PROJECT,
                EntityWrite.epic("Plan", null).withAcceptanceCriteria(TestCriteria.CRITERIA),
                "t")
            .entity();
    WorkEntity feature =
        workEntities
            .create(Archetype.FEATURE, epic.id, EntityWrite.feature("Part", null, null), "t")
            .entity();
    String base = WORK + campaign.id;

    given()
        .contentType(ContentType.JSON)
        .body(Map.of("entityId", feature.id))
        .post(base + "/members")
        .then()
        .statusCode(409)
        .body("message", containsString("runs no phase of its own"));
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("entityId", "no-such-entity"))
        .post(base + "/members")
        .then()
        .statusCode(404);
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("entityId", epic.id))
        .post(WORK + "no-such-campaign/members")
        .then()
        .statusCode(404);
    // A path naming another archetype is no campaign.
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("entityId", feature.id))
        .post(WORK + epic.id + "/members")
        .then()
        .statusCode(404);
    String membership =
        given()
            .contentType(ContentType.JSON)
            .body(Map.of("entityId", epic.id))
            .post(base + "/members")
            .then()
            .statusCode(201)
            .extract()
            .path("member.membershipId");
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("entityId", epic.id))
        .post(base + "/members")
        .then()
        .statusCode(409);
    given()
        .contentType(ContentType.JSON)
        .body(
            Map.of(
                "groups",
                List.of(
                    Map.of("criteria", List.of()),
                    Map.of(
                        "criteria",
                        List.of(Map.of("kind", "SCM_RELEASE", "predicate", Map.of()))))))
        .put(base + "/members/" + membership + "/condition")
        .then()
        .statusCode(400)
        .body("message", containsString("group 1 has no criteria"))
        .body("message", containsString("group 2, criterion 1: repositoryName is required"));
    move(base, "SOMEWHERE").statusCode(409);
    given().get(base).then().statusCode(200).body("status", equalTo("REPORTED"));
  }

  // --- the progress read (qits-418) ----------------------------------------------------------------

  /**
   * The progress door answers {@code {"progress": CampaignProgressDto}}: a member with no condition
   * is READY, one waiting on it WAITING with the sentence that would satisfy it, and the evaluator
   * reads dark because the bus is off in {@code %test}. An agent reads it; an unknown id is a 404.
   */
  @Test
  void theProgressDoorDerivesEachMembersStateAndAdmitsTheAgent() {
    WorkEntity campaign = workEntities.createCampaign(PROJECT, "Progress", null, "t");
    WorkEntity first = ticket("Progress first");
    WorkEntity second = ticket("Progress second");
    campaigns.addMember(campaign.id, first.id, null, false, "t");
    campaigns.addMember(campaign.id, second.id, null, false, "t");
    String base = WORK + PROJECT + "-" + campaign.number;

    given()
        .get(base + "/progress")
        .then()
        .statusCode(200)
        .body("progress.campaign.id", equalTo(campaign.id))
        .body("progress.campaign.qualifiedId", equalTo(PROJECT + "-" + campaign.number))
        .body("progress.campaign.status", equalTo("REPORTED"))
        .body("progress.campaign.start", nullValue())
        .body("progress.evaluator.connected", equalTo(false))
        .body("progress.evaluator.stalled", equalTo(false))
        .body("progress.members.size()", equalTo(2))
        .body("progress.members[0].state", equalTo("READY"))
        .body("progress.members[0].waitsFor.size()", equalTo(0))
        .body("progress.members[1].state", equalTo("WAITING"))
        .body("progress.members[1].waitsFor", equalTo(List.of(first.id)))
        .body("progress.members[1].groups[0].satisfied", equalTo(false))
        .body("progress.members[1].groups[0].criteria[0].seeded", equalTo(true))
        .body(
            "progress.members[1].groups[0].criteria[0].wouldBeSatisfiedBy",
            equalTo(PROJECT + "-" + first.number + " (Progress first) reaches VERIFIED"))
        .body("progress.members[1].groups[0].criteria[0].satisfiable", equalTo(true))
        .body("progress.members[1].groups[0].criteria[0].reason", nullValue())
        .body("progress.members[1].dispatch.workspaceId", nullValue());

    asForwardedAgent()
        .get(base + "/progress")
        .then()
        .statusCode(200)
        .body("progress.members.size()", equalTo(2));
    asForwardedAgent().get(WORK + "no-such-campaign/progress").then().statusCode(404);
    given()
        .header("X-Qits-User", "someone")
        .header("X-Qits-Roles", "qits:system")
        .get(base + "/progress")
        .then()
        .statusCode(403);
  }

  // --- roles ---------------------------------------------------------------------------------------

  /** Approve is the sign-off: the role list refuses the agent before any body runs. */
  @Test
  void anAgentIsRefusedApproveAtTheDoor() {
    WorkEntity campaign = workEntities.createCampaign(PROJECT, "Gated", null, "t");
    asForwardedAgent()
        .body(Map.of("note", "me"))
        .post(
            WORK
                + campaign.id
                + "/members/no-such-member/criteria/no-such-criterion/approve")
        .then()
        .statusCode(403);
  }

  /**
   * The build doors pass the role check: an id naming nothing is the route's own 404, where approve
   * above answered the door's 403. The reads admit the agent outright.
   */
  @Test
  void theBuildDoorsAdmitTheAgentRole() {
    asForwardedAgent()
        .body(Map.of("entityId", "anything"))
        .post(WORK + "no-such-campaign/members")
        .then()
        .statusCode(404);
    asForwardedAgent()
        .body(Map.of("position", 0))
        .put(WORK + "no-such-campaign/members/no-such-member/position")
        .then()
        .statusCode(404);
    asForwardedAgent()
        .body(Map.of("groups", List.of()))
        .put(WORK + "no-such-campaign/members/no-such-member/condition")
        .then()
        .statusCode(404);
    asForwardedAgent()
        .delete(WORK + "no-such-campaign/members/no-such-member")
        .then()
        .statusCode(404);
    asForwardedAgent()
        .body(Map.of("target", "REFINED"))
        .post(WORK + "no-such-campaign/status")
        .then()
        .statusCode(404);
    asForwardedAgent()
        .get("/projects/api/projects/" + PROJECT + "/work?archetype=CAMPAIGN")
        .then()
        .statusCode(200);
    WorkEntity campaign = workEntities.createCampaign(PROJECT, "Agent read", null, "t");
    asForwardedAgent().get(WORK + campaign.id + "/members").then().statusCode(200);
  }

  /** An agent holding its own project's token adds a member; another project's agent is refused. */
  @Test
  void anAgentAddsAMemberToItsOwnProjectsCampaign() {
    WorkEntity campaign = workEntities.createCampaign(PROJECT, "Agent built", null, "t");
    WorkEntity ticket = ticket("Agent's work");

    CampaignMemberDto added = doors.addMember(AGENT, campaign.id, ticket.id, null, null);
    assertEquals(ticket.id, added.entity().id());
    assertFalse(added.joinedRunning(), "REPORTED, and nobody stands on its branch");

    String other = ticket("Other").id;
    ForbiddenException refused =
        assertThrows(
            ForbiddenException.class,
            () -> doors.addMember(FOREIGN_AGENT, campaign.id, other, null, null));
    assertEquals(403, refused.statusCode());
    assertEquals(1, campaigns.get(campaign.id).members().size(), "nothing was added");

    // The same binding holds on every other membership write.
    String membershipId = added.membershipId();
    assertThrows(
        ForbiddenException.class,
        () -> doors.moveMember(FOREIGN_AGENT, campaign.id, membershipId, 0));
    assertThrows(
        ForbiddenException.class,
        () -> doors.setCondition(FOREIGN_AGENT, campaign.id, membershipId, List.of()));
    assertThrows(
        ForbiddenException.class,
        () -> doors.removeMember(FOREIGN_AGENT, campaign.id, membershipId));
    assertEquals(1, campaigns.get(campaign.id).members().size(), "nothing was removed");
    doors.removeMember(AGENT, campaign.id, membershipId);
    assertEquals(0, campaigns.get(campaign.id).members().size());
  }

  // --- the in-flight default -----------------------------------------------------------------------

  @Test
  void anOmittedInFlightIsDecidedFromTheMembersStatusAndAnExplicitOneWins() {
    WorkEntity campaign = workEntities.createCampaign(PROJECT, "In flight", null, "t");
    WorkEntity implemented = ticket("Implemented");
    workEntities.transition(Archetype.TICKET, implemented.id, "REFINED", "t");
    workEntities.transition(Archetype.TICKET, implemented.id, "READY_FOR_DEV", Mover.person("t"));
    workEntities.transition(Archetype.TICKET, implemented.id, "IMPLEMENTED", "t");
    WorkEntity overridden = ticket("Overridden");
    workEntities.transition(Archetype.TICKET, overridden.id, "REFINED", "t");
    workEntities.transition(Archetype.TICKET, overridden.id, "READY_FOR_DEV", Mover.person("t"));
    workEntities.transition(Archetype.TICKET, overridden.id, "IMPLEMENTED", "t");

    CampaignMemberDto joined = doors.addMember(AGENT, campaign.id, implemented.id, null, null);
    assertTrue(joined.joinedRunning());
    assertNotNull(joined.claimedAt());

    CampaignMemberDto stated = doors.addMember(AGENT, campaign.id, overridden.id, null, false);
    assertFalse(stated.joinedRunning());

    // The same default over HTTP, the explicit flag in the body.
    WorkEntity viaRoute = ticket("Via the route");
    workEntities.transition(Archetype.TICKET, viaRoute.id, "REFINED", "t");
    workEntities.transition(Archetype.TICKET, viaRoute.id, "READY_FOR_DEV", Mover.person("t"));
    workEntities.transition(Archetype.TICKET, viaRoute.id, "IMPLEMENTED", "t");
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("entityId", PROJECT + "-" + viaRoute.number, "inFlight", false))
        .post(WORK + campaign.id + "/members")
        .then()
        .statusCode(201)
        .body("member.joinedRunning", equalTo(false));
  }

  // --- fixtures ------------------------------------------------------------------------------------

  private WorkEntity ticket(String title) {
    return workEntities
        .create(
            Archetype.TICKET,
            PROJECT,
            EntityWrite.ticket(title, "it occurs", null, "BUG", null)
                .withAcceptanceCriteria(TestCriteria.CRITERIA),
            "t")
        .entity();
  }

  private long numberOf(String campaignTitle) {
    return campaigns.listByProject(PROJECT).stream()
        .filter(summary -> summary.campaign().title.equals(campaignTitle))
        .findFirst()
        .orElseThrow()
        .campaign()
        .number;
  }

  /** {@code POST /work/{qualifiedId}/status}, as a person: a campaign's moves are a person's. */
  private static io.restassured.response.ValidatableResponse move(String base, String target) {
    return asPerson("dev").body(Map.of("target", target)).post(base + "/status").then();
  }

  /** A browser session: the %test dev user's headers plus a session this service verifies. */
  private static RequestSpecification asPerson(String user) {
    return given()
        .cookie(PersonCheck.SESSION_COOKIE, FakeSessionIntrospection.admin(user))
        .contentType(ContentType.JSON);
  }

  private static RequestSpecification asForwardedAgent() {
    return given()
        .header("X-Qits-User", "someone")
        .header("X-Qits-Roles", "qits:agent")
        .contentType(ContentType.JSON);
  }

  private static void seedProject(String projectId) {
    if (Project.findById(projectId) == null) {
      Project project = new Project();
      project.id = projectId;
      project.name = projectId;
      project.slug = projectId;
      project.persist();
    }
  }
}
