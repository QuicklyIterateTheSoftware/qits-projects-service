package eu.wohlben.qits.projects.releasehost;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.entities.api.PersonGateIdpTenant;
import eu.wohlben.qits.entities.control.EntityCommentService;
import eu.wohlben.qits.entities.control.EntityWrite;
import eu.wohlben.qits.entities.control.WorkEntityService;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.EntityComment;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.projects.bus.BuildStatusListener;
import eu.wohlben.qits.projects.control.DownstreamPins;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.entity.ReleaseRequestApproval;
import eu.wohlben.qits.projects.entity.ReleaseRequestApprovalNotice;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import eu.wohlben.qits.projects.maintenancehost.FakeDownstreamPins;
import eu.wohlben.qits.projects.security.FakeSessionIntrospection;
import eu.wohlben.qits.projects.security.PersonCheck;
import eu.wohlben.qits.servicemock.idp.MockIdp;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>Approval roles through the doors</b> (epic qits-1038, task qits-1043): a fold's requirements
 * name who may approve them, an agent's own token is an approver where a requirement admits agents,
 * an OPEN requirement is an event and a comment on the work item, and nothing an agent holds
 * satisfies what only a person may.
 *
 * <p>The OIDC tenant is on ({@link PersonGateIdpTenant}, shared with the person-gate suites so it
 * costs no extra boot), so an agent's bearer is a real token validated against a mock idp — the one
 * posture in which {@code ApproverCheck}'s "a validated idp JWT" can be observed at all. People
 * come in as the edge forwards a browser session: the headers and the {@code qits-session} cookie.
 *
 * <p>The fold's content is scripted through {@link RecordingFoldChanges}: the declaration repository
 * changes only {@code .config/qits/release.yml}, and only by declaring a docs artifact — {@code
 * artifact-declarations}, admitting {@code person} and {@code agent}. The manual-review repository
 * says {@code manual-review: true} at its main — {@code manual-review}, a person alone.
 */
@QuarkusTest
@WithTestResource(PersonGateIdpTenant.class)
public class ReleaseRequestApprovalRolesTest {

  private static final String RELEASE_YML_BEFORE =
      "archetype: java-service\nartifacts:\n  - { type: docker, name: qits/thing }\n";

  private static final String RELEASE_YML_AFTER =
      RELEASE_YML_BEFORE + "  - { type: docs, name: \"@apidocs/thing\", path: docs/openapi.yml }\n";

  @Inject BuildStatusListener listener;

  @Inject eu.wohlben.qits.projects.bus.ReleaseRequestHeadListener headListener;

  @Inject FakeActiveBuilds activeBuilds;

  @Inject RecordingReleaseExecutor executor;

  @Inject RecordingBackingBranchMerger merger;

  @Inject RecordingReleaseGitHost gitHost;

  @Inject RecordingFoldChanges foldChanges;

  @Inject RecordingApprovalRequirementAnnouncer announcer;

  @Inject WorkEntityService entities;

  @Inject EntityCommentService comments;

  @Inject FakeDownstreamPins downstreamPins;

  private String projectId;
  private String otherProjectId;
  private String declarationRepoId;
  private String manualRepoId;
  private String contractRepoId;
  private String consumerRepoId;
  private WorkEntity ticket;
  private String ticketBranch;
  private final List<String> requestIds = new ArrayList<>();

  @BeforeEach
  void seed() {
    activeBuilds.reset();
    executor.reset();
    merger.reset();
    gitHost.reset();
    foldChanges.reset();
    announcer.reset();
    downstreamPins.reset();
    requestIds.clear();
    activeBuilds.answer(Optional.of(0));
    gitHost.gatedTree("refs/heads/main", Map.of("README.md", "a service"));
    String token = UUID.randomUUID().toString().substring(0, 8);
    projectId = "roles-" + token;
    otherProjectId = "roles-other-" + token;
    declarationRepoId = "roles-declaration-" + UUID.randomUUID();
    manualRepoId = "roles-manual-" + UUID.randomUUID();
    contractRepoId = "roles-contract-" + UUID.randomUUID();
    consumerRepoId = "roles-consumer-" + UUID.randomUUID();
    gitHost.gatedTreeFor(
        manualRepoId,
        "refs/heads/main",
        Map.of(
            "README.md", "a wrapper",
            ".config/qits/release-requests.yml", "manual-review: true\n"));
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Project project = project(projectId);
              Project other = project(otherProjectId);
              repository(project, declarationRepoId);
              repository(project, manualRepoId);
              repository(project, contractRepoId);
              repository(other, consumerRepoId);
            });
    // The declaration repository's fold changes release.yml, and only by declaring an artifact.
    foldChanges.changes(
        declarationRepoId,
        List.of(
            RecordingFoldChanges.fileWithBlobs(
                "MODIFIED", ".config/qits/release.yml", "b1ob0000before", "b1ob0000after")));
    foldChanges.scriptBlob("b1ob0000before", RELEASE_YML_BEFORE);
    foldChanges.scriptBlob("b1ob0000after", RELEASE_YML_AFTER);
    ticket =
        entities
            .create(
                Archetype.TICKET,
                projectId,
                EntityWrite.ticket("Declare the API docs", "they are not published", null, "BUG", null),
                "seed")
            .entity();
    ticketBranch = "ticket/" + projectId + "-" + ticket.number;
  }

  @AfterEach
  void drop() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              ReleaseRequest.delete("projectId = ?1", projectId);
              if (!requestIds.isEmpty()) {
                ReleaseRequestApproval.delete("requestId in ?1", requestIds);
                ReleaseRequestApprovalNotice.delete("requestId in ?1", requestIds);
              }
            });
    foldChanges.reset();
    downstreamPins.reset();
  }

  // ---- an agent acknowledges a declaration -----------------------------------------------------

  /**
   * The owner's "agent acknowledgement" and "artifact declarations" cases in one walk: the request
   * holds at approval with one requirement admitting {@code agent, person}; it is announced OPEN and
   * the ticket its branch names is told — the reason, the path, the command; the request's own agent
   * reads that it may approve, approves, and the request releases; the trail says it was an agent.
   */
  @Test
  void theRequestsOwnAgentApprovesADeclarationAndItReleases() {
    String id = create(declarationRepoId, ticketBranch);
    String fold = mergedShaOf(declarationRepoId, id);
    verdict(declarationRepoId, fold);

    JsonPath held = request(declarationRepoId, id);
    assertEquals("PENDING", held.getString("state"));
    assertEquals("WAITING", held.getString("approvalState"));
    assertEquals(
        List.of("artifact-declarations"), held.getList("approvalRequirements.id", String.class));
    assertEquals(
        List.of("agent", "person"), held.getList("approvalRequirements[0].admits", String.class));
    assertEquals(false, held.getBoolean("approvalRequirements[0].satisfied"));
    assertTrue(
        held.getString("detail").startsWith("Waiting for approval of "),
        "not 'a person', since an agent may: " + held.getString("detail"));

    assertEquals(List.of("artifact-declarations:OPEN@" + fold.substring(0, 7)), announcer.statesOf(id));
    List<EntityComment> told = comments.listComments(ticket.id);
    assertEquals(1, told.size(), "one comment on the work item the branch names");
    String body = told.get(0).body;
    assertTrue(body.contains(".config/qits/release.yml"), body);
    assertTrue(
        body.contains("qits release-request approve " + qualifiedIdOf(declarationRepoId, id) + " --sha " + fold),
        body);
    assertTrue(body.contains("Who may run it: agent, person."), body);

    agent(projectId)
        .get(base(declarationRepoId) + "/" + id + "/approval-eligibility")
        .then()
        .statusCode(200)
        .body("eligibility.viewer", equalTo("agent-of-" + projectId))
        .body("eligibility.heldRoles", equalTo(List.of("agent")))
        .body("eligibility.eligibleRequirementIds", equalTo(List.of("artifact-declarations")));

    agent(projectId)
        .body("{\"mergedSha\":\"" + fold + "\"}")
        .post(base(declarationRepoId) + "/" + id + "/approve")
        .then()
        .statusCode(200)
        .body("request.approvalRequirements[0].satisfied", equalTo(true))
        .body("request.approvalRequirements[0].satisfiedBy.approver", equalTo("agent-of-" + projectId))
        .body("request.approvalRequirements[0].satisfiedBy.roles", equalTo(List.of("agent")))
        .body("request.approvedBy", equalTo("agent-of-" + projectId));

    awaitState(declarationRepoId, id, "RELEASED");
    person("ada")
        .get(base(declarationRepoId) + "/" + id + "/approvals")
        .then()
        .statusCode(200)
        .body("approvals[0].roles", equalTo(List.of("agent")));
    assertEquals(
        List.of(
            "artifact-declarations:OPEN@" + fold.substring(0, 7),
            "artifact-declarations:SATISFIED@" + fold.substring(0, 7)),
        announcer.statesOf(id),
        "each state once, however many evaluations saw it");
    assertEquals(1, comments.listComments(ticket.id).size(), "and the comment was made once");
  }

  /**
   * <b>An agent of another project is not {@code agent}</b>: a requirement admitting the request's
   * own agent refuses it with a 403 naming the requirement and its roles.
   */
  @Test
  void anAgentOfAnotherProjectMayNotSatisfyTheRequestsOwnAgentRole() {
    String id = create(declarationRepoId, ticketBranch);
    String fold = mergedShaOf(declarationRepoId, id);
    verdict(declarationRepoId, fold);

    agent(otherProjectId)
        .body("{\"mergedSha\":\"" + fold + "\"}")
        .post(base(declarationRepoId) + "/" + id + "/approve")
        .then()
        .statusCode(403)
        .body("message", containsString("artifact-declarations"))
        .body("message", containsString("admits agent, person"))
        .body("message", containsString("You hold agent@" + otherProjectId));
    agent(otherProjectId)
        .get(base(declarationRepoId) + "/" + id + "/approval-eligibility")
        .then()
        .statusCode(200)
        .body("eligibility.heldRoles", equalTo(List.of("agent@" + otherProjectId)))
        .body("eligibility.eligibleRequirementIds", equalTo(List.of()));
  }

  // ---- admin stays admin ------------------------------------------------------------------------

  /**
   * The owner's "admin stays admin": an agent approving a manual-review request gets 403 naming
   * {@code manual-review} and {@code person}, and may not decline it either; a person approves as
   * today and the trail says {@code person}. Asserted headers alone are refused.
   */
  @Test
  void anAgentMayNotSatisfyManualReviewAndAPersonDoes() {
    String id = create(manualRepoId, "work");
    String fold = mergedShaOf(manualRepoId, id);
    verdict(manualRepoId, fold);

    agent(projectId)
        .body("{\"mergedSha\":\"" + fold + "\"}")
        .post(base(manualRepoId) + "/" + id + "/approve")
        .then()
        .statusCode(403)
        .body("message", containsString("manual-review"))
        .body("message", containsString("admits person"));
    agent(projectId)
        .body("{\"mergedSha\":\"" + fold + "\"}")
        .post(base(manualRepoId) + "/" + id + "/decline")
        .then()
        .statusCode(403);
    given()
        .contentType(ContentType.JSON)
        .header("X-Qits-User", "mallory")
        .header("X-Qits-Roles", "qits:admin")
        .body("{\"mergedSha\":\"" + fold + "\"}")
        .post(base(manualRepoId) + "/" + id + "/approve")
        .then()
        .statusCode(403);
    agent(projectId)
        .get(base(manualRepoId) + "/" + id + "/approval-eligibility")
        .then()
        .statusCode(200)
        .body("eligibility.eligibleRequirementIds", equalTo(List.of()));
    person("ada")
        .get(base(manualRepoId) + "/" + id + "/approval-eligibility")
        .then()
        .statusCode(200)
        .body("eligibility.viewer", equalTo("ada"))
        .body("eligibility.heldRoles", equalTo(List.of("person")))
        .body("eligibility.eligibleRequirementIds", equalTo(List.of("manual-review")));

    person("ada")
        .body("{\"mergedSha\":\"" + fold + "\"}")
        .post(base(manualRepoId) + "/" + id + "/approve")
        .then()
        .statusCode(200)
        .body("request.approvalState", equalTo("APPROVED"));
    awaitState(manualRepoId, id, "RELEASED");
    person("ada")
        .get(base(manualRepoId) + "/" + id + "/approvals")
        .then()
        .statusCode(200)
        .body("approvals[0].roles", equalTo(List.of("person")));
  }

  /** Approving once every requirement is satisfied is a 409, not a second row. */
  @Test
  void approvingWhatIsSatisfiedIsAConflict() {
    String id = create(declarationRepoId, ticketBranch);
    String fold = mergedShaOf(declarationRepoId, id);
    // No verdict: CI still runs, so the approval lands and nothing releases.
    person("ada")
        .body("{\"mergedSha\":\"" + fold + "\"}")
        .post(base(declarationRepoId) + "/" + id + "/approve")
        .then()
        .statusCode(200);
    agent(projectId)
        .body("{\"mergedSha\":\"" + fold + "\"}")
        .post(base(declarationRepoId) + "/" + id + "/approve")
        .then()
        .statusCode(409)
        .body("message", containsString("satisfied already"));
    assertEquals(List.of(), announcer.statesOf(id), "the gate was never reached, so nothing opened");
  }

  // ---- decline, re-fold ----------------------------------------------------------------------------

  /**
   * An agent qualified for the requirement declines: REJECTED with its sentence, announced DECLINED.
   * A push re-folds it: the old fold's requirement is VOID and the new one is OPEN.
   */
  @Test
  void aDeclineRejectsAndAReFoldVoidsAndReopens() {
    String id = create(declarationRepoId, ticketBranch);
    String first = mergedShaOf(declarationRepoId, id);
    verdict(declarationRepoId, first);

    agent(projectId)
        .body("{\"mergedSha\":\"" + first + "\",\"note\":\"the docs path is wrong\"}")
        .post(base(declarationRepoId) + "/" + id + "/decline")
        .then()
        .statusCode(200)
        .body("request.state", equalTo("REJECTED"))
        .body("request.approvalState", equalTo("DECLINED"))
        .body("request.detail", equalTo("Declined by agent-of-" + projectId + ": the docs path is wrong"));

    headMoved(declarationRepoId, ticketBranch);
    awaitFoldToMove(declarationRepoId, id, first);
    String second = mergedShaOf(declarationRepoId, id);
    assertNotEquals(first, second);
    verdict(declarationRepoId, second);

    assertEquals(
        List.of(
            "artifact-declarations:OPEN@" + first.substring(0, 7),
            "artifact-declarations:DECLINED@" + first.substring(0, 7),
            "artifact-declarations:VOID@" + first.substring(0, 7),
            "artifact-declarations:OPEN@" + second.substring(0, 7)),
        announcer.statesOf(id));
    assertEquals(2, comments.listComments(ticket.id).size(), "one comment per fold");
  }

  // ---- a contract another project consumes (qits-1291) ------------------------------------------

  /**
   * The owner's "cross-project" case: the contract repository's fold changes its golden masters,
   * and a repository of the other project pins them — so the request needs {@code
   * agent@<other project>} or a person. An agent of the request's own project is refused with a 403
   * naming the requirement; the consumer project's agent satisfies it and the request releases.
   */
  @Test
  void aContractConsumedByAnotherProjectNeedsThatProjectsAgent() {
    consumesTheContract("eu.wohlben.qits:qits-roles-golden-masters");
    String id = create(contractRepoId, ticketBranch);
    String fold = mergedShaOf(contractRepoId, id);
    verdict(contractRepoId, fold);

    JsonPath held = request(contractRepoId, id);
    assertEquals("PENDING", held.getString("state"));
    assertEquals(
        List.of("contract:" + otherProjectId),
        held.getList("approvalRequirements.id", String.class));
    assertEquals(
        List.of("agent@" + otherProjectId, "person"),
        held.getList("approvalRequirements[0].admits", String.class));
    assertEquals(true, held.getBoolean("approvalRequirements[0].excludesAuthors"));
    assertEquals(
        "golden-masters/a-state/getThing.json",
        held.getString("approvalRequirements[0].detail"));

    agent(projectId)
        .body("{\"mergedSha\":\"" + fold + "\"}")
        .post(base(contractRepoId) + "/" + id + "/approve")
        .then()
        .statusCode(403)
        .body("message", containsString("contract:" + otherProjectId))
        .body("message", containsString("admits agent@" + otherProjectId + ", person"));

    agent(otherProjectId)
        .get(base(contractRepoId) + "/" + id + "/approval-eligibility")
        .then()
        .statusCode(200)
        .body("eligibility.heldRoles", equalTo(List.of("agent@" + otherProjectId)))
        .body("eligibility.eligibleRequirementIds", equalTo(List.of("contract:" + otherProjectId)));
    agent(otherProjectId)
        .body("{\"mergedSha\":\"" + fold + "\"}")
        .post(base(contractRepoId) + "/" + id + "/approve")
        .then()
        .statusCode(200)
        .body("request.approvalRequirements[0].satisfied", equalTo(true))
        .body(
            "request.approvalRequirements[0].satisfiedBy.approver",
            equalTo("agent-of-" + otherProjectId));

    awaitState(contractRepoId, id, "RELEASED");
  }

  /** A consumer pinning some other artifact of the repository is no consumer of its contract. */
  @Test
  void aConsumerOfAnotherArtifactOpensNoContractRequirement() {
    consumesTheContract("eu.wohlben.qits:qits-roles-service-pacts-telemetry");
    String id = create(contractRepoId, ticketBranch);
    String fold = mergedShaOf(contractRepoId, id);
    verdict(contractRepoId, fold);

    awaitState(contractRepoId, id, "RELEASED");
    assertEquals(List.of(), announcer.statesOf(id), "nothing needed approving");
  }

  /** The contract repository changes its golden masters and the consumer repository pins {@code pin}. */
  private void consumesTheContract(String pin) {
    foldChanges.changes(
        contractRepoId,
        List.of(
            RecordingFoldChanges.fileWithBlobs(
                "MODIFIED", "golden-masters/a-state/getThing.json", "b1ob0000gm0", "b1ob0000gm1")));
    foldChanges.scriptFile(
        contractRepoId,
        ".config/qits/release.yml",
        "archetype: java-service\ncontracts:\n  application: qits-roles\n"
            + "  golden-masters: { from: golden-masters/, packages: [maven, npm] }\n");
    downstreamPins.downstream(
        contractRepoId,
        List.of(new DownstreamPins.Downstream("telemetry-consumer-service", consumerRepoId, 1)));
    downstreamPins.pins(
        "telemetry-consumer-service", List.of(new DownstreamPins.Pin("maven", pin)));
  }

  // ---- fixtures ------------------------------------------------------------------------------------

  private static Project project(String id) {
    Project project = new Project();
    project.id = id;
    project.name = id;
    project.slug = id;
    project.persist();
    return project;
  }

  private static void repository(Project project, String repoId) {
    Repository repository = new Repository();
    repository.id = repoId;
    repository.project = project;
    repository.mainBranch = "main";
    repository.archetype = RepositoryArchetype.SERVICE;
    repository.persist();
  }

  private static RequestSpecification person(String name) {
    return given()
        .contentType(ContentType.JSON)
        .header("X-Qits-User", name)
        .header("X-Qits-Roles", "qits:admin")
        .cookie(PersonCheck.SESSION_COOKIE, FakeSessionIntrospection.admin(name));
  }

  /**
   * An agent's own workspace token, as qits-idp mints it for a commission of {@code project}: the
   * client id as {@code sub}, and {@code clients/<client id>} beside {@code qits:agent} in its
   * groups — qits-idp adds that self-role to every client token it mints.
   */
  private static RequestSpecification agent(String project) {
    String token =
        MockIdp.attach()
            .token()
            .subject("agent-of-" + project)
            .audience("qits-platform")
            .groups("qits:agent", "clients/agent-of-" + project)
            .claim("context_kind", "workspace")
            .claim("project", project)
            .mint();
    return given().contentType(ContentType.JSON).header("Authorization", "Bearer " + token);
  }

  /**
   * The decision is recorded under the token's principal — {@code preferred_username} where it has
   * one, what {@code addedBy} records for the same caller on every other door — not its {@code sub}.
   */
  @Test
  void anAgentDecidesUnderItsPrincipal() {
    String id = create(declarationRepoId, ticketBranch);
    String fold = mergedShaOf(declarationRepoId, id);
    String token =
        MockIdp.attach()
            .token()
            .subject("client-of-" + projectId)
            .audience("qits-platform")
            .groups("qits:agent", "clients/client-of-" + projectId)
            .claim("preferred_username", "workspace-agent-" + projectId)
            .claim("context_kind", "workspace")
            .claim("project", projectId)
            .mint();
    RequestSpecification agent =
        given().contentType(ContentType.JSON).header("Authorization", "Bearer " + token);
    agent
        .get(base(declarationRepoId) + "/" + id + "/approval-eligibility")
        .then()
        .statusCode(200)
        .body("eligibility.viewer", equalTo("workspace-agent-" + projectId));
    given()
        .contentType(ContentType.JSON)
        .header("Authorization", "Bearer " + token)
        .body("{\"mergedSha\":\"" + fold + "\"}")
        .post(base(declarationRepoId) + "/" + id + "/approve")
        .then()
        .statusCode(200);
    person("ada")
        .get(base(declarationRepoId) + "/" + id + "/approvals")
        .then()
        .statusCode(200)
        .body("approvals[0].actor", equalTo("workspace-agent-" + projectId));
  }

  private static String base(String repoId) {
    return "/projects/api/repositories/" + repoId + "/release-requests";
  }

  private String create(String repoId, String branch) {
    gitHost.tree("refs/heads/" + branch, Map.of("README.md", "a change"));
    String id =
        person("ada")
            .body("{\"branch\":\"" + branch + "\",\"summary\":\"a release\",\"requester\":\"wohlben\"}")
            .post(base(repoId))
            .then()
            .statusCode(200)
            .extract()
            .path("request.id");
    requestIds.add(id);
    return id;
  }

  private JsonPath request(String repoId, String id) {
    return person("ada")
        .get(base(repoId) + "/" + id)
        .then()
        .statusCode(200)
        .extract()
        .jsonPath()
        .setRootPath("request");
  }

  private String mergedShaOf(String repoId, String id) {
    return request(repoId, id).getString("mergedSha");
  }

  private String qualifiedIdOf(String repoId, String id) {
    return request(repoId, id).getString("qualifiedId");
  }

  private void verdict(String repoId, String sha) {
    listener.onFrame(
        new EventFrame(
            UUID.randomUUID().toString(),
            "BuildSuccessful",
            Instant.now(),
            "{\"branch\":\""
                + BackingBranches.atFold(repoId, sha)
                + "\",\"commitSha\":\""
                + sha
                + "\",\"repoId\":\""
                + repoId
                + "\",\"runId\":\"run-"
                + UUID.randomUUID()
                + "\"}",
            null,
            null,
            null));
  }

  private void headMoved(String repoId, String branch) {
    headListener.onFrame(
        new EventFrame(
            UUID.randomUUID().toString(),
            "SCMPublishCommit",
            Instant.now(),
            "{\"branch\":\""
                + branch
                + "\",\"repoId\":\""
                + repoId
                + "\",\"sha\":\""
                + UUID.randomUUID().toString().replace("-", "")
                + "\"}",
            null,
            null,
            null));
  }

  private void awaitFoldToMove(String repoId, String id, String from) {
    poll(() -> !from.equals(mergedShaOf(repoId, id)), "the fold never moved off " + from);
  }

  private void awaitState(String repoId, String id, String expected) {
    poll(
        () -> expected.equals(request(repoId, id).getString("state")),
        "request " + id + " never reached " + expected);
  }

  private static void poll(java.util.function.BooleanSupplier done, String why) {
    long deadline = System.currentTimeMillis() + 10_000;
    while (System.currentTimeMillis() < deadline) {
      if (done.getAsBoolean()) {
        return;
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail("interrupted");
      }
    }
    fail(why);
  }
}
