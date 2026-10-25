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
import eu.wohlben.qits.projects.control.AutomationKinds;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.entity.ReleaseRequestApproval;
import eu.wohlben.qits.projects.entity.ReleaseRequestApprovalNotice;
import eu.wohlben.qits.projects.entity.ReleasedTagPendingMerge;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import eu.wohlben.qits.projects.maintenancehost.FakeAutomationKinds;
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
 * manual-review} only work-item branches whose items are VERIFYING (qits-1560), VERIFIED or DONE;
 * for an {@code automation:*} or {@code contract:*} hold (qits-1517, rule {@code member-ci-green}),
 * and a {@code config-directory} one (qits-1560), a member repository, never the wrapper, whose work
 * items are IMPLEMENTING or later; for a listed {@code no-changes} (qits-1568) the {@code
 * manual-review-verified} rule plus a branch naming a work item. A desk approval's note names the
 * rule; the desk never declines.
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

  @Inject FakeAutomationKinds automationKinds;

  /** qits-maintenance's catalogue: the entity diagram is acknowledged by an agent or a person. */
  private static final List<AutomationKinds.Kind> KINDS =
      List.of(
          new AutomationKinds.Kind(
              "entity-diagram",
              "Entity diagram",
              List.of(":(glob)docs/database/**"),
              List.of("agent", "person")));

  private String projectId;
  private String otherProjectId;
  private String wrapperRepoId;
  private String declarationRepoId;
  private String configRepoId;
  private String memberRepoId;
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
    automationKinds.reset();
    requestIds.clear();
    activeBuilds.answer(Optional.of(0));
    gitHost.gatedTree("refs/heads/main", Map.of("README.md", "a service"));
    String token = UUID.randomUUID().toString().substring(0, 8);
    projectId = "desk-" + token;
    otherProjectId = "desk-other-" + token;
    wrapperRepoId = "desk-wrapper-" + UUID.randomUUID();
    declarationRepoId = "desk-declaration-" + UUID.randomUUID();
    configRepoId = "desk-config-" + UUID.randomUUID();
    memberRepoId = "desk-member-" + UUID.randomUUID();
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
              repository(project, memberRepoId, RepositoryArchetype.SERVICE);
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
    // The member repository's fold regenerates the entity diagram: automation:entity-diagram.
    foldChanges.changes(
        memberRepoId,
        List.of(
            RecordingFoldChanges.file("MODIFIED", "docs/database/entities.md", null),
            RecordingFoldChanges.file("MODIFIED", "src/main/java/Thing.java", null)));
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
              for (String repoId :
                  List.of(wrapperRepoId, declarationRepoId, configRepoId, memberRepoId)) {
                ReleasedTagPendingMerge.delete("repoId = ?1", repoId);
              }
              if (!requestIds.isEmpty()) {
                ReleaseRequestApproval.delete("requestId in ?1", requestIds);
                ReleaseRequestApprovalNotice.delete("requestId in ?1", requestIds);
              }
            });
    foldChanges.reset();
    automationKinds.reset();
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

  /**
   * The branch's ticket is only IMPLEMENTED: not VERIFYING, VERIFIED or DONE, so not the desk's to
   * ship.
   */
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
        .body("message", containsString("is IMPLEMENTED, not VERIFYING, VERIFIED or DONE"));
  }

  /**
   * qits-1560: a verify probe ships from an item under verification — the wrapper request of a
   * VERIFYING ticket is the desk's under the same rule, and its id is unchanged in the note.
   */
  @Test
  void theDeskApprovesAWrapperRequestWhoseItemIsVerifying() {
    approves("manual-review");
    status(ticket, "VERIFYING");
    String id = create(wrapperRepoId, ticketBranch);
    String fold = mergedShaOf(wrapperRepoId, id);
    verdict(wrapperRepoId, fold);

    desk(projectId)
        .body("{\"mergedSha\":\"" + fold + "\",\"note\":\"the ticket's verify probe\"}")
        .post(base(wrapperRepoId) + "/" + id + "/approve")
        .then()
        .statusCode(200)
        .body("request.approvalState", equalTo("APPROVED"));
    person("ada")
        .get(base(wrapperRepoId) + "/" + id + "/approvals")
        .then()
        .statusCode(200)
        .body("approvals[0].roles", equalTo(List.of("desk")))
        .body(
            "approvals[0].note",
            equalTo("delivery desk, rule manual-review-verified: the ticket's verify probe"));
  }

  /**
   * qits-1560, the owner's example (qits-qits-rr-182): a VERIFYING ticket's wrapper probe changes
   * {@code .config/qits/}; with {@code config-directory} listed, both holds admit the desk, and one
   * approval under {@code manual-review-verified} — the wrapper's rule, which wins — satisfies both.
   */
  @Test
  void theDeskApprovesAWrapperProbeThatChangesTheConfigDirectory() {
    approves("manual-review", "config-directory");
    status(ticket, "VERIFYING");
    foldChanges.changes(
        wrapperRepoId,
        List.of(RecordingFoldChanges.file("MODIFIED", ".config/qits/project.yml", null)));
    String id = create(wrapperRepoId, ticketBranch);
    String fold = mergedShaOf(wrapperRepoId, id);
    verdict(wrapperRepoId, fold);

    JsonPath held = request(wrapperRepoId, id);
    assertEquals(
        List.of("manual-review", "config-directory"),
        held.getList("approvalRequirements.id", String.class));
    assertEquals(
        List.of("desk", "person"), held.getList("approvalRequirements[1].admits", String.class));

    desk(projectId)
        .body("{\"mergedSha\":\"" + fold + "\",\"note\":\"the interval probe the ticket plans\"}")
        .post(base(wrapperRepoId) + "/" + id + "/approve")
        .then()
        .statusCode(200)
        .body("request.approvalRequirements[0].satisfied", equalTo(true))
        .body("request.approvalRequirements[1].satisfied", equalTo(true))
        .body("request.approvalState", equalTo("APPROVED"));
    person("ada")
        .get(base(wrapperRepoId) + "/" + id + "/approvals")
        .then()
        .statusCode(200)
        .body(
            "approvals[0].note",
            equalTo(
                "delivery desk, rule manual-review-verified: the interval probe the ticket plans"));
  }

  // ---- no-changes (qits-1568) -------------------------------------------------------------------

  /**
   * qits-1568, the owner's example (qits-qits-rr-194): a VERIFIED ticket's work lives elsewhere, so
   * the {@code manual-review: true} request's fold adds nothing to main. With {@code no-changes}
   * listed beside {@code manual-review}, both holds admit the desk, and one approval under {@code
   * manual-review-verified} satisfies both. (A {@code PROJECT}-archetype wrapper's empty fold is
   * held or withdrawn before approval by the qits-1273 backstop, so the suite stages the same holds
   * on a member repository configured {@code manual-review: true}.)
   */
  @Test
  void theDeskApprovesAManualReviewRequestThatAddsNothingForAVerifiedItem() {
    approves("manual-review", "no-changes");
    status(ticket, "VERIFIED");
    String id = createEmptyManualReview(ticketBranch);
    String fold = mergedShaOf(memberRepoId, id);
    verdict(memberRepoId, fold);

    JsonPath held = request(memberRepoId, id);
    assertEquals(
        List.of("manual-review", "no-changes"),
        held.getList("approvalRequirements.id", String.class));
    assertEquals(
        List.of("desk", "person"), held.getList("approvalRequirements[1].admits", String.class));

    desk(projectId)
        .body("{\"mergedSha\":\"" + fold + "\",\"note\":\"work shipped in its submodules\"}")
        .post(base(memberRepoId) + "/" + id + "/approve")
        .then()
        .statusCode(200)
        .body("request.approvalRequirements[0].satisfied", equalTo(true))
        .body("request.approvalRequirements[1].satisfied", equalTo(true))
        .body("request.approvalState", equalTo("APPROVED"));
    person("ada")
        .get(base(memberRepoId) + "/" + id + "/approvals")
        .then()
        .statusCode(200)
        .body("approvals[0].roles", equalTo(List.of("desk")))
        .body(
            "approvals[0].note",
            equalTo("delivery desk, rule manual-review-verified: work shipped in its submodules"));
  }

  /**
   * qits-1568: an empty release with no work to resolve stays the owner's. A main-only request of a
   * member repository that adds nothing holds {@code no-changes} alone; listed, it admits the desk,
   * and the door refuses it under {@code manual-review-verified} — never the looser member rule —
   * because no branch names a work item.
   */
  @Test
  void aMainOnlyRequestThatAddsNothingIsRefusedNamingTheRule() {
    approves("no-changes");
    foldChanges.changes(memberRepoId, List.of());
    foldChanges.empty(memberRepoId);
    String id = createMainOnly(memberRepoId);
    String fold = mergedShaOf(memberRepoId, id);
    verdict(memberRepoId, fold);

    JsonPath held = request(memberRepoId, id);
    assertEquals(List.of("no-changes"), held.getList("approvalRequirements.id", String.class));
    assertEquals(
        List.of("desk", "person"), held.getList("approvalRequirements[0].admits", String.class));

    desk(projectId)
        .body("{\"mergedSha\":\"" + fold + "\"}")
        .post(base(memberRepoId) + "/" + id + "/approve")
        .then()
        .statusCode(403)
        .body("message", containsString("rule manual-review-verified"))
        .body("message", containsString("names a work item of this project"));
  }

  /** qits-1568: a maintenance/* source names no work either, so an empty bump stays the owner's. */
  @Test
  void aMaintenanceOnlyRequestThatAddsNothingIsRefused() {
    approves("no-changes");
    foldChanges.changes(memberRepoId, List.of());
    foldChanges.empty(memberRepoId);
    String id = create(memberRepoId, "maintenance/bump-" + UUID.randomUUID());
    String fold = mergedShaOf(memberRepoId, id);
    verdict(memberRepoId, fold);

    desk(projectId)
        .body("{\"mergedSha\":\"" + fold + "\"}")
        .post(base(memberRepoId) + "/" + id + "/approve")
        .then()
        .statusCode(403)
        .body("message", containsString("rule manual-review-verified"))
        .body("message", containsString("names a work item of this project"));
  }

  /**
   * qits-1568: not listed, {@code no-changes} admits a person alone. The desk's approval satisfies
   * {@code manual-review}, and the empty fold still waits on a person.
   */
  @Test
  void noChangesTheProjectDoesNotListStaysAPersons() {
    approves("manual-review");
    status(ticket, "VERIFIED");
    String id = createEmptyManualReview(ticketBranch);
    String fold = mergedShaOf(memberRepoId, id);
    verdict(memberRepoId, fold);

    JsonPath held = request(memberRepoId, id);
    assertEquals(
        List.of("manual-review", "no-changes"),
        held.getList("approvalRequirements.id", String.class));
    assertEquals(List.of("person"), held.getList("approvalRequirements[1].admits", String.class));

    desk(projectId)
        .body("{\"mergedSha\":\"" + fold + "\"}")
        .post(base(memberRepoId) + "/" + id + "/approve")
        .then()
        .statusCode(200)
        .body("request.approvalRequirements[0].satisfied", equalTo(true))
        .body("request.approvalRequirements[1].satisfied", equalTo(false));
    desk(projectId)
        .body("{\"mergedSha\":\"" + fold + "\"}")
        .post(base(memberRepoId) + "/" + id + "/approve")
        .then()
        .statusCode(403)
        .body("message", containsString("no-changes"))
        .body("message", containsString("admits person"));
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

  /**
   * Another project's desk: refused before any rule is read (qits-1596, {@code
   * ReleaseRequests.requireOwnProjectOfDesk}), on decline too, and eligible for nothing.
   */
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
        .body("message", containsString("only its own project's requests"));
    desk(otherProjectId)
        .body("{\"mergedSha\":\"" + fold + "\"}")
        .post(base(wrapperRepoId) + "/" + id + "/decline")
        .then()
        .statusCode(403)
        .body("message", containsString("only its own project's requests"));
    desk(otherProjectId)
        .get(base(wrapperRepoId) + "/" + id + "/approval-eligibility")
        .then()
        .statusCode(200)
        .body("eligibility.eligibleRequirementIds", equalTo(List.of()));
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

  /** Not listed, {@code config-directory} admits a person alone, whatever else the project lists. */
  @Test
  void configDirectoryTheProjectDoesNotListIsRefused() {
    approves("artifact-declarations", "manual-review", "automation", "contract");
    status(ticket, "IMPLEMENTING");
    String id = create(configRepoId, ticketBranch + "-config");
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

  /**
   * qits-1560: listed, a member request of work in flight held by {@code config-directory} is the
   * desk's under {@code member-ci-green} once its CI is green.
   */
  @Test
  void theDeskApprovesAMemberRequestHeldByConfigDirectoryWhenListedAndGreen() {
    approves("config-directory");
    status(ticket, "IMPLEMENTING");
    String id = create(configRepoId, ticketBranch + "-config");
    String fold = mergedShaOf(configRepoId, id);
    verdict(configRepoId, fold);

    JsonPath held = request(configRepoId, id);
    assertEquals(List.of("config-directory"), held.getList("approvalRequirements.id", String.class));
    assertEquals(
        List.of("desk", "person"), held.getList("approvalRequirements[0].admits", String.class));

    desk(projectId)
        .body("{\"mergedSha\":\"" + fold + "\",\"note\":\"the ticket asks for this setting\"}")
        .post(base(configRepoId) + "/" + id + "/approve")
        .then()
        .statusCode(200)
        .body("request.approvalRequirements[0].satisfied", equalTo(true))
        .body("request.approvalState", equalTo("APPROVED"));
    person("ada")
        .get(base(configRepoId) + "/" + id + "/approvals")
        .then()
        .statusCode(200)
        .body("approvals[0].roles", equalTo(List.of("desk")))
        .body(
            "approvals[0].note",
            equalTo("delivery desk, rule member-ci-green: the ticket asks for this setting"));
  }

  /** Listed, a config change still waits for green CI. */
  @Test
  void aMemberConfigChangeWithRedCiIsRefused() {
    approves("config-directory");
    status(ticket, "IMPLEMENTING");
    String red = create(configRepoId, ticketBranch + "-red");
    String redFold = mergedShaOf(configRepoId, red);
    verdict(configRepoId, redFold, "BuildFailed");
    desk(projectId)
        .body("{\"mergedSha\":\"" + redFold + "\"}")
        .post(base(configRepoId) + "/" + red + "/approve")
        .then()
        .statusCode(403)
        .body("message", containsString("rule member-ci-green"))
        .body("message", containsString("FAILED"));
  }

  /** Listed, a config change of work that has not started is not the desk's. */
  @Test
  void aMemberConfigChangeBeforeImplementingIsRefused() {
    approves("config-directory");
    status(ticket, "REFINED");
    String early = create(configRepoId, ticketBranch + "-early");
    String earlyFold = mergedShaOf(configRepoId, early);
    verdict(configRepoId, earlyFold);
    desk(projectId)
        .body("{\"mergedSha\":\"" + earlyFold + "\"}")
        .post(base(configRepoId) + "/" + early + "/approve")
        .then()
        .statusCode(403)
        .body("message", containsString("rule member-ci-green"))
        .body("message", containsString("is REFINED, not IMPLEMENTING"));
  }

  // ---- member-ci-green (qits-1517) ------------------------------------------------------------

  /**
   * The owner's case: a member request of a ticket in flight, held by {@code
   * automation:entity-diagram}, CI green, the project listing {@code automation} — the desk approves
   * under {@code member-ci-green}.
   */
  @Test
  void theDeskApprovesAMemberRequestOfWorkInFlightWithGreenCi() {
    automationKinds.answer(KINDS);
    approves("automation");
    status(ticket, "IMPLEMENTING");
    String id = create(memberRepoId, ticketBranch + "-diagram");
    String fold = mergedShaOf(memberRepoId, id);
    verdict(memberRepoId, fold);

    JsonPath held = request(memberRepoId, id);
    assertEquals(
        List.of("automation:entity-diagram"),
        held.getList("approvalRequirements.id", String.class));
    assertEquals(
        List.of("agent", "desk", "person"),
        held.getList("approvalRequirements[0].admits", String.class));

    desk(projectId)
        .body("{\"mergedSha\":\"" + fold + "\",\"note\":\"diagram matches the entity\"}")
        .post(base(memberRepoId) + "/" + id + "/approve")
        .then()
        .statusCode(200)
        .body("request.approvalRequirements[0].satisfied", equalTo(true))
        .body("request.approvalRequirements[0].satisfiedBy.approver", equalTo("desk-of-" + projectId));
    person("ada")
        .get(base(memberRepoId) + "/" + id + "/approvals")
        .then()
        .statusCode(200)
        .body("approvals[0].roles", equalTo(List.of("desk")))
        .body(
            "approvals[0].note",
            equalTo("delivery desk, rule member-ci-green: diagram matches the entity"));
  }

  /** Not listed: {@code automation:*} is the request's agent's or a person's, never the desk's. */
  @Test
  void anAutomationHoldTheProjectDoesNotListIsNotTheDesks() {
    automationKinds.answer(KINDS);
    approves("manual-review", "artifact-declarations");
    status(ticket, "IMPLEMENTING");
    String id = create(memberRepoId, ticketBranch + "-diagram");
    String fold = mergedShaOf(memberRepoId, id);
    verdict(memberRepoId, fold);

    assertEquals(
        List.of("agent", "person"),
        request(memberRepoId, id).getList("approvalRequirements[0].admits", String.class));
    desk(projectId)
        .body("{\"mergedSha\":\"" + fold + "\"}")
        .post(base(memberRepoId) + "/" + id + "/approve")
        .then()
        .statusCode(403);
  }

  /** A red build: the desk does not approve a member request over it. */
  @Test
  void aMemberRequestWithRedCiIsRefused() {
    automationKinds.answer(KINDS);
    approves("automation");
    status(ticket, "IMPLEMENTING");
    String id = create(memberRepoId, ticketBranch + "-diagram");
    String fold = mergedShaOf(memberRepoId, id);
    verdict(memberRepoId, fold, "BuildFailed");

    desk(projectId)
        .body("{\"mergedSha\":\"" + fold + "\"}")
        .post(base(memberRepoId) + "/" + id + "/approve")
        .then()
        .statusCode(403)
        .body("message", containsString("rule member-ci-green"))
        .body("message", containsString("FAILED"));
  }

  /**
   * The wrapper is never a member request: its automation hold is refused under {@code
   * member-ci-green}, even with its ticket VERIFIED and its CI green — the wrapper ships under
   * {@code manual-review-verified} alone.
   */
  @Test
  void aWrapperRequestIsRefusedUnderTheMemberRule() {
    automationKinds.answer(KINDS);
    approves("automation");
    status(ticket, "VERIFIED");
    foldChanges.changes(
        wrapperRepoId,
        List.of(RecordingFoldChanges.file("MODIFIED", "docs/database/entities.md", null)));
    String id = create(wrapperRepoId, ticketBranch);
    String fold = mergedShaOf(wrapperRepoId, id);
    verdict(wrapperRepoId, fold);

    JsonPath held = request(wrapperRepoId, id);
    assertEquals(
        List.of("manual-review", "automation:entity-diagram"),
        held.getList("approvalRequirements.id", String.class));

    desk(projectId)
        .body("{\"mergedSha\":\"" + fold + "\"}")
        .post(base(wrapperRepoId) + "/" + id + "/approve")
        .then()
        .statusCode(403)
        .body("message", containsString("rule member-ci-green"))
        .body("message", containsString("the project's wrapper"));
  }

  /** An item the owner has not let start — REFINED, or still REPORTED — is not work in flight. */
  @Test
  void aMemberRequestOfAnItemNotYetStartedIsRefused() {
    automationKinds.answer(KINDS);
    approves("automation");
    for (String status : List.of("REFINED", "REPORTED")) {
      status(ticket, status);
      String id = create(memberRepoId, ticketBranch + "-" + status.toLowerCase());
      String fold = mergedShaOf(memberRepoId, id);
      verdict(memberRepoId, fold);

      desk(projectId)
          .body("{\"mergedSha\":\"" + fold + "\"}")
          .post(base(memberRepoId) + "/" + id + "/approve")
          .then()
          .statusCode(403)
          .body("message", containsString("rule member-ci-green"))
          .body("message", containsString("is " + status + ", not IMPLEMENTING"));
    }
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

  /**
   * A request of the member repository, configured {@code manual-review: true} at its main as
   * {@code qits-agent-config} is, whose fold adds nothing to main.
   */
  private String createEmptyManualReview(String branch) {
    gitHost.gatedTreeFor(
        memberRepoId,
        "refs/heads/main",
        Map.of(
            "README.md", "a member",
            ".config/qits/release-requests.yml", "manual-review: true\n"));
    foldChanges.changes(memberRepoId, List.of());
    foldChanges.empty(memberRepoId);
    return create(memberRepoId, branch);
  }

  /** A main-only request: its one source is main, whose tree the seed staged and this keeps. */
  private String createMainOnly(String repoId) {
    String id =
        person("ada")
            .body("{\"branch\":\"main\",\"summary\":\"a release\",\"requester\":\"wohlben\"}")
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
