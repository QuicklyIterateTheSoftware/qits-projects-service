package eu.wohlben.qits.projects.releasehost;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.entities.api.PersonGateIdpTenant;
import eu.wohlben.qits.entities.control.EntityWrite;
import eu.wohlben.qits.entities.control.WorkEntityService;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.projects.bus.BuildStatusListener;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.entity.ReleaseRequestApproval;
import eu.wohlben.qits.projects.entity.ReleaseRequestApprovalNotice;
import eu.wohlben.qits.projects.entity.ReleasedTagPendingMerge;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.entity.RepositoryArchetype;
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
 * <b>The delivery desk approves</b> (epic qits-1440, task qits-1498): a {@code desk-delivery} token
 * holds {@code desk} and {@code agent} of its project; a requirement the project's {@code
 * front_desk.delivery.approves} lists admits {@code desk}; and the approve door then holds the desk
 * to the owner's rule — its own project, every gate before approval passed, and for {@code
 * manual-review} only work-item branches whose items are VERIFIED or DONE. A desk approval's note
 * names the rule; the desk never declines.
 *
 * <p>The suite of {@link ReleaseRequestApprovalRolesTest}, booted the same way ({@link
 * PersonGateIdpTenant}), so the desk's bearer is a real token validated against the mock idp.
 */
@QuarkusTest
@WithTestResource(PersonGateIdpTenant.class)
public class ReleaseRequestDeskApprovalTest {

  private static final String RELEASE_YML_BEFORE =
      "archetype: java-service\nartifacts:\n  - { type: docker, name: qits/thing }\n";

  private static final String RELEASE_YML_AFTER =
      RELEASE_YML_BEFORE + "  - { type: docs, name: \"@apidocs/thing\", path: docs/openapi.yml }\n";

  @Inject BuildStatusListener listener;

  @Inject FakeActiveBuilds activeBuilds;

  @Inject RecordingReleaseExecutor executor;

  @Inject RecordingBackingBranchMerger merger;

  @Inject RecordingReleaseGitHost gitHost;

  @Inject RecordingFoldChanges foldChanges;

  @Inject RecordingApprovalRequirementAnnouncer announcer;

  @Inject WorkEntityService entities;

  private String projectId;
  private String otherProjectId;
  private String wrapperRepoId;
  private String declarationRepoId;
  private String configRepoId;
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
    requestIds.clear();
    activeBuilds.answer(Optional.of(0));
    gitHost.gatedTree("refs/heads/main", Map.of("README.md", "a service"));
    String token = UUID.randomUUID().toString().substring(0, 8);
    projectId = "desk-" + token;
    otherProjectId = "desk-other-" + token;
    wrapperRepoId = "desk-wrapper-" + UUID.randomUUID();
    declarationRepoId = "desk-declaration-" + UUID.randomUUID();
    configRepoId = "desk-config-" + UUID.randomUUID();
    // The wrapper says manual-review: true at its main, as the platform's own does.
    gitHost.gatedTreeFor(
        wrapperRepoId,
        "refs/heads/main",
        Map.of(
            "README.md", "a wrapper",
            ".config/qits/release-requests.yml", "manual-review: true\n"));
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Project project = project(projectId);
              Project other = project(otherProjectId);
              repository(project, wrapperRepoId, RepositoryArchetype.PROJECT);
              repository(project, declarationRepoId, RepositoryArchetype.SERVICE);
              repository(project, configRepoId, RepositoryArchetype.SERVICE);
              repository(other, "desk-other-repo-" + UUID.randomUUID(), RepositoryArchetype.SERVICE);
            });
    // The declaration repository's fold only declares an artifact in release.yml.
    foldChanges.changes(
        declarationRepoId,
        List.of(
            RecordingFoldChanges.fileWithBlobs(
                "MODIFIED", ".config/qits/release.yml", "b1ob0000before", "b1ob0000after")));
    foldChanges.scriptBlob("b1ob0000before", RELEASE_YML_BEFORE);
    foldChanges.scriptBlob("b1ob0000after", RELEASE_YML_AFTER);
    // The config repository's fold changes the release-request settings: config-directory.
    foldChanges.changes(
        configRepoId,
        List.of(RecordingFoldChanges.file("MODIFIED", ".config/qits/release-requests.yml", null)));
    ticket =
        entities
            .create(
                Archetype.TICKET,
                projectId,
                EntityWrite.ticket("Ship the thing", "it is verified", null, "BUG", null),
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
              for (String repoId : List.of(wrapperRepoId, declarationRepoId, configRepoId)) {
                ReleasedTagPendingMerge.delete("repoId = ?1", repoId);
              }
              if (!requestIds.isEmpty()) {
                ReleaseRequestApproval.delete("requestId in ?1", requestIds);
                ReleaseRequestApprovalNotice.delete("requestId in ?1", requestIds);
              }
            });
    foldChanges.reset();
  }

  // ---- manual-review ---------------------------------------------------------------------------

  /**
   * The owner's case: the project lists {@code manual-review}, the wrapper request's gates are
   * green and its ticket is VERIFIED — the desk approves, the note names the rule, and the trail
   * says {@code desk}.
   */
  @Test
  void theDeskApprovesManualReviewOnAWrapperRequestWhenListedGreenAndVerified() {
    approves("manual-review");
    status(ticket, "VERIFIED");
    String id = create(wrapperRepoId, ticketBranch);
    String fold = mergedShaOf(wrapperRepoId, id);
    verdict(wrapperRepoId, fold);

    JsonPath held = request(wrapperRepoId, id);
    assertEquals(List.of("manual-review"), held.getList("approvalRequirements.id", String.class));
    assertEquals(
        List.of("desk", "person"), held.getList("approvalRequirements[0].admits", String.class));

    desk(projectId)
        .get(base(wrapperRepoId) + "/" + id + "/approval-eligibility")
        .then()
        .statusCode(200)
        .body("eligibility.viewer", equalTo("desk-of-" + projectId))
        .body("eligibility.heldRoles", equalTo(List.of("desk")))
        .body("eligibility.eligibleRequirementIds", equalTo(List.of("manual-review")));

    desk(projectId)
        .body("{\"mergedSha\":\"" + fold + "\",\"note\":\"read the fold: one README line\"}")
        .post(base(wrapperRepoId) + "/" + id + "/approve")
        .then()
        .statusCode(200)
        .body("request.approvalRequirements[0].satisfied", equalTo(true))
        .body("request.approvalRequirements[0].satisfiedBy.approver", equalTo("desk-of-" + projectId))
        .body("request.approvalState", equalTo("APPROVED"));

    person("ada")
        .get(base(wrapperRepoId) + "/" + id + "/approvals")
        .then()
        .statusCode(200)
        .body("approvals[0].roles", equalTo(List.of("desk")))
        .body(
            "approvals[0].note",
            equalTo("delivery desk, rule manual-review-verified: read the fold: one README line"));
  }

  /** Not listed: {@code manual-review} admits a person alone, and the 403 says so. */
  @Test
  void manualReviewTheProjectDoesNotListIsRefusedNamingPerson() {
    status(ticket, "VERIFIED");
    String id = create(wrapperRepoId, ticketBranch);
    String fold = mergedShaOf(wrapperRepoId, id);
    verdict(wrapperRepoId, fold);

    desk(projectId)
        .body("{\"mergedSha\":\"" + fold + "\"}")
        .post(base(wrapperRepoId) + "/" + id + "/approve")
        .then()
        .statusCode(403)
        .body("message", containsString("manual-review"))
        .body("message", containsString("admits person"));
  }

  /** CI still running: a gate before approval is PENDING, and the desk waits. */
  @Test
  void aPendingGateBeforeApprovalIsRefusedNamingTheRule() {
    approves("manual-review");
    status(ticket, "VERIFIED");
    String id = create(wrapperRepoId, ticketBranch);
    String fold = mergedShaOf(wrapperRepoId, id);

    desk(projectId)
        .body("{\"mergedSha\":\"" + fold + "\"}")
        .post(base(wrapperRepoId) + "/" + id + "/approve")
        .then()
        .statusCode(403)
        .body("message", containsString("rule manual-review-verified"))
        .body("message", containsString("PENDING"));
  }

  /** A red build: the gate is FAILED, and the desk does not approve over it. */
  @Test
  void aRedGateBeforeApprovalIsRefused() {
    approves("manual-review");
    status(ticket, "VERIFIED");
    String id = create(wrapperRepoId, ticketBranch);
    String fold = mergedShaOf(wrapperRepoId, id);
    verdict(wrapperRepoId, fold, "BuildFailed");

    desk(projectId)
        .body("{\"mergedSha\":\"" + fold + "\"}")
        .post(base(wrapperRepoId) + "/" + id + "/approve")
        .then()
        .statusCode(403)
        .body("message", containsString("rule manual-review-verified"))
        .body("message", containsString("FAILED"));
  }

  /** The branch's ticket is only IMPLEMENTED: not VERIFIED or DONE, so not the desk's to ship. */
  @Test
  void aSourceWhoseItemIsImplementedIsRefused() {
    approves("manual-review");
    status(ticket, "IMPLEMENTED");
    String id = create(wrapperRepoId, ticketBranch);
    String fold = mergedShaOf(wrapperRepoId, id);
    verdict(wrapperRepoId, fold);

    desk(projectId)
        .body("{\"mergedSha\":\"" + fold + "\"}")
        .post(base(wrapperRepoId) + "/" + id + "/approve")
        .then()
        .statusCode(403)
        .body("message", containsString("rule manual-review-verified"))
        .body("message", containsString("is IMPLEMENTED, not VERIFIED or DONE"));
  }

  /** A branch naming no work item (nor maintenance/*) is not the desk's either. */
  @Test
  void aSourceBranchNamingNoWorkItemIsRefused() {
    approves("manual-review");
    String id = create(wrapperRepoId, "work");
    String fold = mergedShaOf(wrapperRepoId, id);
    verdict(wrapperRepoId, fold);

    desk(projectId)
        .body("{\"mergedSha\":\"" + fold + "\"}")
        .post(base(wrapperRepoId) + "/" + id + "/approve")
        .then()
        .statusCode(403)
        .body("message", containsString("rule manual-review-verified"))
        .body("message", containsString("branch work names no work item"));
  }

  /** Another project's desk: refused, naming the rule, however the request's project is set. */
  @Test
  void anotherProjectsDeskIsRefused() {
    approves("manual-review");
    status(ticket, "VERIFIED");
    String id = create(wrapperRepoId, ticketBranch);
    String fold = mergedShaOf(wrapperRepoId, id);
    verdict(wrapperRepoId, fold);

    desk(otherProjectId)
        .body("{\"mergedSha\":\"" + fold + "\"}")
        .post(base(wrapperRepoId) + "/" + id + "/approve")
        .then()
        .statusCode(403)
        .body("message", containsString("rule manual-review-verified"))
        .body("message", containsString("only its own project's requests"));
  }

  /** The desk does not decline; it asks the owner. */
  @Test
  void aDeskDeclineIsRefused() {
    approves("manual-review");
    status(ticket, "VERIFIED");
    String id = create(wrapperRepoId, ticketBranch);
    String fold = mergedShaOf(wrapperRepoId, id);
    verdict(wrapperRepoId, fold);

    desk(projectId)
        .body("{\"mergedSha\":\"" + fold + "\",\"note\":\"no\"}")
        .post(base(wrapperRepoId) + "/" + id + "/decline")
        .then()
        .statusCode(403)
        .body(
            "message",
            equalTo("The delivery desk does not decline; it asks the owner on the item's thread."));
  }

  /** A person approves a listed manual-review exactly as before: {@code person}, the note as said. */
  @Test
  void aPersonIsUnchanged() {
    approves("manual-review");
    String id = create(wrapperRepoId, "work");
    String fold = mergedShaOf(wrapperRepoId, id);
    verdict(wrapperRepoId, fold);

    person("ada")
        .body("{\"mergedSha\":\"" + fold + "\",\"note\":\"looks right\"}")
        .post(base(wrapperRepoId) + "/" + id + "/approve")
        .then()
        .statusCode(200)
        .body("request.approvalState", equalTo("APPROVED"));
    person("ada")
        .get(base(wrapperRepoId) + "/" + id + "/approvals")
        .then()
        .statusCode(200)
        .body("approvals[0].roles", equalTo(List.of("person")))
        .body("approvals[0].note", equalTo("looks right"));
  }

  // ---- artifact-declarations and config-directory ----------------------------------------------

  /** Listed, {@code artifact-declarations} is the desk's: approved under its own rule. */
  @Test
  void theDeskApprovesArtifactDeclarationsWhenListed() {
    approves("artifact-declarations");
    String id = create(declarationRepoId, "work");
    String fold = mergedShaOf(declarationRepoId, id);
    verdict(declarationRepoId, fold);

    JsonPath held = request(declarationRepoId, id);
    assertEquals(
        List.of("artifact-declarations"), held.getList("approvalRequirements.id", String.class));
    assertEquals(
        List.of("agent", "desk", "person"),
        held.getList("approvalRequirements[0].admits", String.class));

    desk(projectId)
        .body("{\"mergedSha\":\"" + fold + "\"}")
        .post(base(declarationRepoId) + "/" + id + "/approve")
        .then()
        .statusCode(200)
        .body("request.approvalRequirements[0].satisfied", equalTo(true));
    person("ada")
        .get(base(declarationRepoId) + "/" + id + "/approvals")
        .then()
        .statusCode(200)
        .body("approvals[0].roles", equalTo(List.of("desk")))
        .body("approvals[0].note", equalTo("delivery desk, rule artifact-declarations: -"));
  }

  /**
   * Not listed, {@code artifact-declarations} is not the desk's, although it admits the request's
   * agents: the desk holds {@code desk} alone, so it neither approves nor declines it as an agent.
   */
  @Test
  void artifactDeclarationsTheProjectDoesNotListRefusesTheDesk() {
    String id = create(declarationRepoId, "work");
    String fold = mergedShaOf(declarationRepoId, id);
    verdict(declarationRepoId, fold);

    desk(projectId)
        .body("{\"mergedSha\":\"" + fold + "\"}")
        .post(base(declarationRepoId) + "/" + id + "/approve")
        .then()
        .statusCode(403);
    desk(projectId)
        .body("{\"mergedSha\":\"" + fold + "\"}")
        .post(base(declarationRepoId) + "/" + id + "/decline")
        .then()
        .statusCode(403);
  }

  /** {@code config-directory} never admits the desk, whatever the project lists. */
  @Test
  void configDirectoryIsRefusedEvenWithEverythingListed() {
    approves("artifact-declarations", "manual-review");
    String id = create(configRepoId, "work");
    String fold = mergedShaOf(configRepoId, id);
    verdict(configRepoId, fold);

    JsonPath held = request(configRepoId, id);
    assertEquals(List.of("config-directory"), held.getList("approvalRequirements.id", String.class));
    assertEquals(List.of("person"), held.getList("approvalRequirements[0].admits", String.class));
    desk(projectId)
        .body("{\"mergedSha\":\"" + fold + "\"}")
        .post(base(configRepoId) + "/" + id + "/approve")
        .then()
        .statusCode(403)
        .body("message", containsString("config-directory"))
        .body("message", containsString("admits person"));
  }

  // ---- fixtures ----------------------------------------------------------------------------------

  private static Project project(String id) {
    Project project = new Project();
    project.id = id;
    project.name = id;
    project.slug = id;
    project.persist();
    return project;
  }

  private static void repository(Project project, String repoId, RepositoryArchetype archetype) {
    Repository repository = new Repository();
    repository.id = repoId;
    repository.project = project;
    repository.mainBranch = "main";
    repository.archetype = archetype;
    repository.persist();
  }

  /** {@code front_desk.delivery.approves} of the project, as the reconcile stores it. */
  private void approves(String... ids) {
    String column = String.join(" ", new java.util.TreeSet<>(List.of(ids)));
    QuarkusTransaction.requiringNew()
        .run(() -> Project.update("deliveryApproves = ?1 where id = ?2", column, projectId));
  }

  private static void status(WorkEntity entity, String status) {
    QuarkusTransaction.requiringNew()
        .run(() -> WorkEntity.update("status = ?1 where id = ?2", status, entity.id));
  }

  private static RequestSpecification person(String name) {
    return given()
        .contentType(ContentType.JSON)
        .header("X-Qits-User", name)
        .header("X-Qits-Roles", "qits:admin")
        .cookie(PersonCheck.SESSION_COOKIE, FakeSessionIntrospection.admin(name));
  }

  /**
   * The delivery desk's token as this service commissions it for {@code project}: kind {@code
   * desk-delivery}, {@code qits:agent} and {@code qits:desk}, and qits-idp's {@code clients/<sub>}.
   */
  private static RequestSpecification desk(String project) {
    String token =
        MockIdp.attach()
            .token()
            .subject("desk-of-" + project)
            .audience("qits-platform")
            .groups("qits:agent", "qits:desk", "clients/desk-of-" + project)
            .claim("context_kind", "desk-delivery")
            .claim("project", project)
            .mint();
    return given().contentType(ContentType.JSON).header("Authorization", "Bearer " + token);
  }

  private static String base(String repoId) {
    return "/projects/api/repositories/" + repoId + "/release-requests";
  }

  private String create(String repoId, String branch) {
    gitHost.tree("refs/heads/" + branch, Map.of("README.md", "a change"));
    String id =
        person("ada")
            .body(
                "{\"branch\":\"" + branch + "\",\"summary\":\"a release\",\"requester\":\"wohlben\"}")
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

  private void verdict(String repoId, String sha) {
    verdict(repoId, sha, "BuildSuccessful");
  }

  private void verdict(String repoId, String sha, String signature) {
    listener.onFrame(
        new EventFrame(
            UUID.randomUUID().toString(),
            signature,
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
}
