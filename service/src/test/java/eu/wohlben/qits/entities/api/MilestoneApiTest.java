package eu.wohlben.qits.entities.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.entities.campaign.CampaignService;
import eu.wohlben.qits.entities.control.EntityWrite;
import eu.wohlben.qits.entities.control.Mover;
import eu.wohlben.qits.entities.control.WorkEntityService;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.entities.error.ForbiddenException;
import eu.wohlben.qits.entities.milestone.Milestone;
import eu.wohlben.qits.entities.milestone.MilestoneRepository;
import eu.wohlben.qits.entities.milestone.WorkMilestoneRepository;
import eu.wohlben.qits.projects.bus.MilestoneMet;
import eu.wohlben.qits.projects.bus.RecordingMilestoneAnnouncer;
import eu.wohlben.qits.projects.contracts.ContractBearers;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.security.AgentTokens;
import eu.wohlben.qits.projects.security.FakeSessionIntrospection;
import eu.wohlben.qits.projects.security.PersonCheck;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The milestone doors over HTTP (epic qits-615): a project's list and create, one milestone's read,
 * rename, condition replace and approve, a work entity's attach, detach and read, a campaign
 * membership's milestones, and the work reads carrying {@code milestones} and {@code
 * unmetMilestones}. The agent binding is driven through {@link MilestoneDoors} with {@link
 * AgentTokens} identities — {@code EntityAgentBoundsTest} explains why a forwarded agent cannot
 * carry a project claim over HTTP.
 *
 * <p>No {@code @TestProfile} (the test-profile budget rule).
 */
@QuarkusTest
@TestSecurity(user = "dev", roles = "qits:admin")
class MilestoneApiTest {

  private static final String PROJECT = "milestone-api";
  private static final String FOREIGN_PROJECT = "milestone-api-foreign";
  private static final String API = "/projects/api";

  private static final SecurityIdentity AGENT =
      AgentTokens.token(Map.of("project", PROJECT), "qits:agent");

  private static final SecurityIdentity FOREIGN_AGENT =
      AgentTokens.token(Map.of("project", FOREIGN_PROJECT), "qits:agent");

  @Inject WorkEntityService workEntities;
  @Inject CampaignService campaigns;
  @Inject MilestoneDoors doors;
  @Inject RecordingMilestoneAnnouncer announcer;
  @Inject MilestoneRepository milestoneRows;
  @Inject WorkMilestoneRepository workMilestones;

  @BeforeEach
  void seed() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              seedProject(PROJECT);
              seedProject(FOREIGN_PROJECT);
            });
    announcer.reset();
  }

  // --- a milestone's life over HTTP ------------------------------------------------------------

  @Test
  void anApprovalMilestoneIsCreatedAttachedApprovedAndTheWorkReadsFollow() {
    WorkEntity ticket = ticket(PROJECT, "Bootstrap on a fresh node");
    String qualified = PROJECT + "-" + ticket.number;

    String milestoneId =
        given()
            .contentType(ContentType.JSON)
            .body(approvalMilestone("bootstrap test node"))
            .post(API + "/projects/" + PROJECT + "/milestones")
            .then()
            .statusCode(201)
            .body("milestone.displayName", equalTo("bootstrap test node"))
            .body("milestone.projectId", equalTo(PROJECT))
            .body("milestone.met", equalTo(false))
            .body("milestone.satisfiedAt", nullValue())
            .body("milestone.createdAt", notNullValue())
            .body("milestone.groups[0].position", equalTo(0))
            .body("milestone.groups[0].createdAt", notNullValue())
            .body("milestone.groups[0].satisfiedAt", nullValue())
            .body("milestone.groups[0].criteria[0].kind", equalTo("APPROVAL"))
            .body("milestone.groups[0].criteria[0].createdAt", notNullValue())
            .body("milestone.groups[0].criteria[0].satisfiedAt", nullValue())
            .body("milestone.entities.size()", equalTo(0))
            .body("milestone.memberships.size()", equalTo(0))
            .extract()
            .path("milestone.id");

    // Nothing waits on it yet: the work reads say so, empty, not absent.
    given()
        .get(API + "/work/" + qualified)
        .then()
        .statusCode(200)
        .body("milestones.size()", equalTo(0))
        .body("unmetMilestones", equalTo(0));

    given()
        .put(API + "/work/" + qualified + "/milestones/" + milestoneId)
        .then()
        .statusCode(200)
        .body("milestones[0].id", equalTo(milestoneId))
        .body("milestones[0].displayName", equalTo("bootstrap test node"))
        .body("milestones[0].satisfiedAt", nullValue());
    // Idempotent.
    given()
        .put(API + "/work/" + qualified + "/milestones/" + milestoneId)
        .then()
        .statusCode(200)
        .body("milestones.size()", equalTo(1));
    given()
        .get(API + "/work/" + qualified + "/milestones")
        .then()
        .statusCode(200)
        .body("milestones.id", equalTo(List.of(milestoneId)));
    given()
        .get(API + "/work/" + qualified)
        .then()
        .statusCode(200)
        .body("milestones[0].id", equalTo(milestoneId))
        .body("milestones[0].satisfiedAt", nullValue())
        .body("unmetMilestones", equalTo(1));
    given()
        .get(API + "/projects/" + PROJECT + "/work")
        .then()
        .statusCode(200)
        .body("entities.find { it.id == '" + ticket.id + "' }.unmetMilestones", equalTo(1))
        .body(
            "entities.find { it.id == '" + ticket.id + "' }.milestones[0].displayName",
            equalTo("bootstrap test node"));
    given()
        .get(API + "/milestones/" + milestoneId)
        .then()
        .statusCode(200)
        .body("milestone.entities[0].id", equalTo(ticket.id))
        .body("milestone.entities[0].qualifiedId", equalTo(qualified))
        .body("milestone.entities[0].title", equalTo("Bootstrap on a fresh node"))
        .body("milestone.entities[0].status", equalTo("REPORTED"))
        .body("milestone.entities[0].archetype", equalTo("TICKET"));
    given()
        .get(API + "/projects/" + PROJECT + "/milestones?met=false")
        .then()
        .statusCode(200)
        .body("milestones.id", hasItem(milestoneId));

    String criterionId =
        given()
            .get(API + "/milestones/" + milestoneId)
            .then()
            .extract()
            .path("milestone.groups[0].criteria[0].id");
    // The %test dev user is qits:admin by header alone, which is not a person.
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("note", "go"))
        .post(API + "/milestones/" + milestoneId + "/criteria/" + criterionId + "/approve")
        .then()
        .statusCode(403);
    assertTrue(announcer.met().isEmpty(), "a refused approval announces nothing");

    asPerson("ada")
        .body(Map.of("note", "node 7 is up"))
        .post(API + "/milestones/" + milestoneId + "/criteria/" + criterionId + "/approve")
        .then()
        .statusCode(200)
        .body("milestone.met", equalTo(true))
        .body("milestone.satisfiedAt", notNullValue())
        .body("milestone.groups[0].satisfiedAt", notNullValue())
        .body("milestone.groups[0].criteria[0].satisfiedAt", notNullValue())
        .body("milestone.groups[0].criteria[0].approval.approvedBy", equalTo("ada"))
        .body("milestone.groups[0].criteria[0].approval.note", equalTo("node 7 is up"));
    List<MilestoneMet> met = announcer.met();
    assertEquals(1, met.size(), "the approval announces MilestoneMet once: " + met);
    assertEquals(milestoneId, met.get(0).milestoneId());
    assertEquals(PROJECT, met.get(0).projectId());
    assertEquals("bootstrap test node", met.get(0).displayName());

    asPerson("ada")
        .body(Map.of())
        .post(API + "/milestones/" + milestoneId + "/criteria/" + criterionId + "/approve")
        .then()
        .statusCode(409)
        .body("message", containsString("already approved by"));

    given()
        .get(API + "/work/" + qualified)
        .then()
        .statusCode(200)
        .body("milestones[0].satisfiedAt", notNullValue())
        .body("unmetMilestones", equalTo(0));
    given()
        .get(API + "/projects/" + PROJECT + "/work")
        .then()
        .statusCode(200)
        .body("entities.find { it.id == '" + ticket.id + "' }.unmetMilestones", equalTo(0));
    given()
        .get(API + "/projects/" + PROJECT + "/milestones?met=false")
        .then()
        .statusCode(200)
        .body("milestones.id", not(hasItem(milestoneId)));
    given()
        .get(API + "/projects/" + PROJECT + "/milestones?met=true")
        .then()
        .statusCode(200)
        .body("milestones.id", hasItem(milestoneId));

    // An edit restating a changed criterion un-meets it: the work item needs it again.
    announcer.reset();
    given()
        .contentType(ContentType.JSON)
        .body(approvalGroups())
        .put(API + "/milestones/" + milestoneId + "/groups")
        .then()
        .statusCode(200)
        .body("milestone.met", equalTo(false))
        .body("milestone.groups[0].criteria[0].id", not(equalTo(criterionId)));
    assertEquals(1, announcer.unmet().size(), "the edit announces MilestoneUnmet");
    given().get(API + "/work/" + qualified).then().body("unmetMilestones", equalTo(1));

    given()
        .contentType(ContentType.JSON)
        .body(Map.of("displayName", "bootstrap test node (arm64)"))
        .patch(API + "/milestones/" + milestoneId)
        .then()
        .statusCode(200)
        .body("milestone.displayName", equalTo("bootstrap test node (arm64)"))
        .body("milestone.met", equalTo(false));

    given()
        .delete(API + "/work/" + qualified + "/milestones/" + milestoneId)
        .then()
        .statusCode(200)
        .body("milestones.size()", equalTo(0));
    given()
        .delete(API + "/work/" + qualified + "/milestones/" + milestoneId)
        .then()
        .statusCode(404);
    given().get(API + "/work/" + qualified).then().body("unmetMilestones", equalTo(0));
  }

  @Test
  void anEntityStatusCriterionOnAFinishedTargetLatchesFromTheCurrentState() {
    WorkEntity target = ticket(PROJECT, "Already shipped");
    for (String status : List.of("REFINED", "READY_FOR_DEV", "IMPLEMENTING", "IMPLEMENTED")) {
      workEntities.transition(Archetype.TICKET, target.id, status, Mover.person("dev"));
    }
    given()
        .contentType(ContentType.JSON)
        .body(
            Map.of(
                "displayName",
                "shipped",
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
                                    PROJECT + "-" + target.number,
                                    "status",
                                    "IMPLEMENTED")))))))
        .post(API + "/projects/" + PROJECT + "/milestones")
        .then()
        .statusCode(201)
        .body("milestone.met", equalTo(true))
        .body("milestone.groups[0].criteria[0].predicate.entityId", equalTo(target.id))
        .body("milestone.groups[0].criteria[0].evidence.signature", equalTo("CURRENT_STATE"));
  }

  @Test
  void theRefusalsAnswerAsDocumented() {
    WorkEntity own = ticket(PROJECT, "Own");
    WorkEntity foreign = ticket(FOREIGN_PROJECT, "Foreign");
    String milestoneId = createdMilestone(PROJECT, "gate");
    String foreignMilestone = createdMilestone(FOREIGN_PROJECT, "foreign gate");

    given().get(API + "/milestones/no-such-milestone").then().statusCode(404);
    given().get(API + "/projects/no-such-project/milestones").then().statusCode(404);
    given()
        .contentType(ContentType.JSON)
        .body(approvalMilestone("x"))
        .post(API + "/projects/no-such-project/milestones")
        .then()
        .statusCode(404);
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("displayName", "empty", "groups", List.of()))
        .post(API + "/projects/" + PROJECT + "/milestones")
        .then()
        .statusCode(400)
        .body("message", containsString("at least one group"));
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("displayName", " ", "groups", approvalGroups().get("groups")))
        .post(API + "/projects/" + PROJECT + "/milestones")
        .then()
        .statusCode(400)
        .body("message", containsString("displayName is required"));
    // An ENTITY_STATUS target of another project.
    given()
        .contentType(ContentType.JSON)
        .body(
            Map.of(
                "displayName",
                "cross",
                "groups",
                List.of(
                    Map.of(
                        "criteria",
                        List.of(
                            Map.of(
                                "kind",
                                "ENTITY_STATUS",
                                "predicate",
                                Map.of("entityId", foreign.id, "status", "DONE")))))))
        .post(API + "/projects/" + PROJECT + "/milestones")
        .then()
        .statusCode(400)
        .body("message", containsString("is not a work entity of this milestone's project"));
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("displayName", "x"))
        .patch(API + "/milestones/no-such-milestone")
        .then()
        .statusCode(404);
    given()
        .contentType(ContentType.JSON)
        .body(approvalGroups())
        .put(API + "/milestones/no-such-milestone/groups")
        .then()
        .statusCode(404);
    asPerson("ada")
        .body(Map.of())
        .post(API + "/milestones/" + milestoneId + "/criteria/no-such-criterion/approve")
        .then()
        .statusCode(404);
    asPerson("ada")
        .body(Map.of())
        .post(API + "/milestones/no-such-milestone/criteria/x/approve")
        .then()
        .statusCode(404);

    // Cross-project attach: the milestone and the entity must share a project.
    given()
        .put(API + "/work/" + PROJECT + "-" + own.number + "/milestones/" + foreignMilestone)
        .then()
        .statusCode(409)
        .body("message", containsString("belongs to project " + FOREIGN_PROJECT));
    given()
        .put(API + "/work/" + PROJECT + "-" + own.number + "/milestones/no-such-milestone")
        .then()
        .statusCode(404);
    given().put(API + "/work/no-such-entity/milestones/" + milestoneId).then().statusCode(404);
    given().get(API + "/work/no-such-entity/milestones").then().statusCode(404);

    // A forwarded agent reaches the reads and the bound writes' roles, never the approve.
    asForwardedAgent().get(API + "/milestones/" + milestoneId).then().statusCode(200);
    asForwardedAgent()
        .get(API + "/projects/" + PROJECT + "/milestones")
        .then()
        .statusCode(200);
    asForwardedAgent()
        .body(Map.of())
        .post(API + "/milestones/" + milestoneId + "/criteria/x/approve")
        .then()
        .statusCode(403);
  }

  // --- the agent binding, through the doors -------------------------------------------------------

  @Test
  void anAgentWritesOnlyItsOwnProjectsMilestones() {
    WorkEntity own = ticket(PROJECT, "Own work");
    WorkEntity foreign = ticket(FOREIGN_PROJECT, "Foreign work");
    String ownMilestone = createdMilestone(PROJECT, "own gate");
    String foreignMilestone = createdMilestone(FOREIGN_PROJECT, "foreign gate");

    MilestoneDtos.MilestoneDto created =
        doors.create(AGENT, PROJECT, "made by an agent", approvalSpecs());
    assertEquals(PROJECT, created.projectId());
    assertEquals("renamed", doors.rename(AGENT, ownMilestone, "renamed").displayName());
    doors.replaceGroups(AGENT, ownMilestone, approvalSpecs());
    assertEquals(1, doors.attach(AGENT, own, ownMilestone).size());
    assertEquals(0, doors.detach(AGENT, own, ownMilestone).size());

    assertThrows(
        ForbiddenException.class,
        () -> doors.create(AGENT, FOREIGN_PROJECT, "not mine", approvalSpecs()));
    assertThrows(ForbiddenException.class, () -> doors.rename(AGENT, foreignMilestone, "x"));
    assertThrows(
        ForbiddenException.class,
        () -> doors.replaceGroups(AGENT, foreignMilestone, approvalSpecs()));
    assertThrows(ForbiddenException.class, () -> doors.attach(AGENT, foreign, foreignMilestone));
    assertThrows(ForbiddenException.class, () -> doors.detach(AGENT, foreign, foreignMilestone));
    assertThrows(
        ForbiddenException.class, () -> doors.rename(FOREIGN_AGENT, ownMilestone, "not yours"));
    // An id naming nothing stays a 404 for an agent too, never a 403.
    assertThrows(
        eu.wohlben.qits.entities.error.NotFoundException.class,
        () -> doors.rename(AGENT, "no-such-milestone", "x"));
  }

  // --- a campaign membership's milestones ---------------------------------------------------------

  @Test
  void aMembershipWaitsOnTheMilestonesItIsGiven() {
    WorkEntity first = ticket(PROJECT, "First member");
    WorkEntity second = ticket(PROJECT, "Second member");
    WorkEntity campaign = workEntities.createCampaign(PROJECT, "Ship the bootstrap", null, "t");
    String campaignQualified = PROJECT + "-" + campaign.number;
    String members = API + "/work/" + campaignQualified + "/members";
    campaigns.addMember(campaign.id, first.id, null, false, "t");
    // Seeded to wait on the first: a generated condition, attached to the member entity too.
    String membership =
        campaigns.addMember(campaign.id, second.id, null, false, "t").membership().id;
    String generated =
        given()
            .get(members)
            .then()
            .statusCode(200)
            .body("members[1].milestones.size()", equalTo(1))
            .body("members[1].milestones[0].satisfiedAt", nullValue())
            .extract()
            .path("members[1].milestones[0].id");
    given()
        .get(API + "/work/" + PROJECT + "-" + second.number)
        .then()
        .body("milestones.id", equalTo(List.of(generated)))
        .body("unmetMilestones", equalTo(1));
    given()
        .get(API + "/milestones/" + generated)
        .then()
        .statusCode(200)
        .body("milestone.memberships[0].membershipId", equalTo(membership))
        .body("milestone.memberships[0].campaignId", equalTo(campaign.id))
        .body("milestone.memberships[0].campaignQualifiedId", equalTo(campaignQualified))
        .body("milestone.memberships[0].campaignTitle", equalTo("Ship the bootstrap"))
        .body("milestone.memberships[0].entityId", equalTo(second.id))
        .body("milestone.memberships[0].entityQualifiedId", equalTo(PROJECT + "-" + second.number))
        .body("milestone.memberships[0].claimedAt", nullValue())
        .body("milestone.memberships[0].generatedCondition", equalTo(true));

    String node = createdMilestone(PROJECT, "bootstrap test node");
    // The generated condition kept, and a milestone of the project's added.
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("milestoneIds", List.of(generated, node)))
        .put(members + "/" + membership + "/milestones")
        .then()
        .statusCode(200)
        .body("member.membershipId", equalTo(membership))
        .body("member.milestones.id", equalTo(List.of(generated, node)))
        // The condition doors still read the generated one.
        .body("member.groups[0].criteria[0].kind", equalTo("ENTITY_STATUS"));
    given()
        .get(API + "/work/" + PROJECT + "-" + second.number)
        .then()
        .body("unmetMilestones", equalTo(2));

    // Left off, the generated condition is detached from the member and, unused, deleted.
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("milestoneIds", List.of(node)))
        .put(members + "/" + membership + "/milestones")
        .then()
        .statusCode(200)
        .body("member.milestones.id", equalTo(List.of(node)))
        .body("member.groups.size()", equalTo(0));
    given().get(API + "/milestones/" + generated).then().statusCode(404);
    given()
        .get(API + "/work/" + PROJECT + "-" + second.number)
        .then()
        .body("milestones.id", equalTo(List.of(node)));

    // The refusals: another project's milestone, an unknown one, a membership of another campaign.
    String foreign = createdMilestone(FOREIGN_PROJECT, "foreign");
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("milestoneIds", List.of(foreign)))
        .put(members + "/" + membership + "/milestones")
        .then()
        .statusCode(409);
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("milestoneIds", List.of("no-such-milestone")))
        .put(members + "/" + membership + "/milestones")
        .then()
        .statusCode(404);
    WorkEntity other = workEntities.createCampaign(PROJECT, "Another", null, "t");
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("milestoneIds", List.of(node)))
        .put(API + "/work/" + other.id + "/members/" + membership + "/milestones")
        .then()
        .statusCode(404);
    assertThrows(
        ForbiddenException.class,
        () -> doors.setMemberMilestones(FOREIGN_AGENT, campaign.id, membership, List.of(node)));

    // Removing the member drops nothing the person chose: the plain milestone stays.
    given().delete(members + "/" + membership).then().statusCode(200);
    given().get(API + "/milestones/" + node).then().statusCode(200);
  }

  @Test
  void deletingACampaignDropsItsGeneratedConditions() {
    WorkEntity first = ticket(PROJECT, "Before");
    WorkEntity second = ticket(PROJECT, "After");
    WorkEntity campaign = workEntities.createCampaign(PROJECT, "Doomed", null, "t");
    campaigns.addMember(campaign.id, first.id, null, false, "t");
    campaigns.addMember(campaign.id, second.id, null, false, "t");
    List<Milestone> needs = QuarkusTransaction.requiringNew().call(() -> milestonesOf(second.id));
    assertEquals(1, needs.size(), "the seed's generated condition is attached to the member");
    String generated = needs.get(0).id;

    workEntities.delete(Archetype.CAMPAIGN, campaign.id, "t");

    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              assertEquals(null, milestoneRows.findById(generated));
              assertTrue(workMilestones.ofEntity(second.id).isEmpty());
            });
    given()
        .get(API + "/work/" + PROJECT + "-" + second.number)
        .then()
        .body("unmetMilestones", equalTo(0));
  }

  // --- fixtures ------------------------------------------------------------------------------------

  private List<Milestone> milestonesOf(String entityId) {
    return workMilestones.ofEntity(entityId).stream()
        .map(row -> milestoneRows.findById(row.milestoneId))
        .toList();
  }

  private String createdMilestone(String project, String name) {
    return given()
        .contentType(ContentType.JSON)
        .body(approvalMilestone(name))
        .post(API + "/projects/" + project + "/milestones")
        .then()
        .statusCode(201)
        .extract()
        .path("milestone.id");
  }

  private static Map<String, Object> approvalMilestone(String name) {
    return Map.of("displayName", name, "groups", approvalGroups().get("groups"));
  }

  private static Map<String, Object> approvalGroups() {
    return Map.of(
        "groups",
        List.of(Map.of("criteria", List.of(Map.of("kind", "APPROVAL", "predicate", Map.of())))));
  }

  private static List<MilestoneDoors.GroupSpec> approvalSpecs() {
    return List.of(
        new MilestoneDoors.GroupSpec(
            List.of(new MilestoneDoors.CriterionSpec(null, "APPROVAL", Map.of()))));
  }

  private WorkEntity ticket(String project, String title) {
    return workEntities
        .create(
            Archetype.TICKET,
            project,
            EntityWrite.ticket(title, "it occurs", null, "BUG", null)
                .withAcceptanceCriteria(TestCriteria.CRITERIA),
            "t")
        .entity();
  }

  /** A browser session: the %test dev user's headers plus a session this service verifies. */
  private static RequestSpecification asPerson(String user) {
    return given()
        .cookie(PersonCheck.SESSION_COOKIE, FakeSessionIntrospection.admin(user))
        .contentType(ContentType.JSON);
  }

  private static RequestSpecification asForwardedAgent() {
    return given()
        .header("Authorization", ContractBearers.caller("someone", "qits:agent"))
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
