package eu.wohlben.qits.projects.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.entities.control.EntityWrite;
import eu.wohlben.qits.entities.control.WorkEntityService;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.projects.control.ReleaseRequests;
import eu.wohlben.qits.projects.control.RepositoryService;
import eu.wohlben.qits.projects.dto.ReleaseRequestDto;
import eu.wohlben.qits.projects.dto.ReleaseRequestItemDto;
import eu.wohlben.qits.projects.dto.ReleaseRequestSourceDto;
import eu.wohlben.qits.projects.dto.ReleaseRequestWorkItemDto;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.releasehost.FakeActiveBuilds;
import eu.wohlben.qits.projects.releasehost.RecordingBackingBranchMerger;
import eu.wohlben.qits.projects.releasehost.RecordingReleaseExecutor;
import eu.wohlben.qits.projects.releasehost.RecordingReleaseGitHost;
import eu.wohlben.qits.projects.security.AgentTokens;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>A release request is attributed to its caller, and says what it is about</b> (qits-1673).
 *
 * <p>Attribution: the doors take no stated {@code requester} any more — a body still carrying one is
 * read past — so an agent cannot put another agent's name on its request, nor claim to be {@code
 * dev-qits-maintenance} and have its red request filed as unattended. Every ask records its caller
 * as a participant, and participants are never deleted, so a request keeps the author of a branch
 * that has since been deleted. Unattended is "every participant a machine": a maintenance bump is,
 * and the same bump once an agent joined it is not.
 *
 * <p>Items: one per named source other than {@code main} — a work item's id and title for its branch,
 * the head commit subject for a {@code maintenance/*} branch, read when the request folds.
 *
 * <p>Two ways in, as {@link ReleaseRequestAgentBoundsTest} explains: an
 * agent's token cannot be put in front of the service by a {@code @QuarkusTest}, so its cases drive
 * the controller directly with a hand-made token; what a JSON body may still say is driven over HTTP
 * as a forwarded identity. The gate is held open ({@link FakeActiveBuilds} answering "a run is still
 * active"), so nothing settles a fixture mid-test.
 */
@QuarkusTest
public class ReleaseRequestAttributionTest {

  /** A workspace agent's token subject, as qits-idp mints it. */
  private static final String AGENT = "tok-workspace-agent-0a1b2c3d-4e5f-6a7b-zpwOo-qhaQ_FX7nkWaYA";

  /** Another agent's, which the first must not be able to name. */
  private static final String OTHER_AGENT =
      "tok-workspace-agent-9f8e7d6c-5b4a-3f2e-zpwOo-qhaQ_FX7nkWaYB";

  /** The default of {@code qits.projects.release-requests.unattended-requesters}. */
  private static final String MAINTENANCE = "dev-qits-maintenance";

  @Inject ReleaseRequests releaseRequests;

  @Inject RepositoryService repositories;

  @Inject WorkEntityService entities;

  @Inject FakeActiveBuilds activeBuilds;

  @Inject RecordingReleaseExecutor executor;

  @Inject RecordingBackingBranchMerger merger;

  @Inject RecordingReleaseGitHost gitHost;

  private String projectId;
  private String repoId;

  @BeforeEach
  void seed() {
    activeBuilds.reset();
    executor.reset();
    merger.reset();
    gitHost.reset();
    activeBuilds.answer(Optional.of(1));
    projectId = "attr-" + UUID.randomUUID().toString().substring(0, 8);
    repoId = "attr-repo-" + UUID.randomUUID();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Project project = new Project();
              project.id = projectId;
              project.name = projectId;
              project.slug = projectId;
              project.persist();
              Repository repository = new Repository();
              repository.id = repoId;
              repository.project = project;
              repository.mainBranch = "main";
              repository.persist();
            });
  }

  /** No open request outlives its test: the sweeps walk every open row there is. */
  @AfterEach
  void drop() {
    QuarkusTransaction.requiringNew()
        .run(() -> ReleaseRequest.delete("projectId = ?1", projectId));
  }

  // ---- attribution ------------------------------------------------------------------------------

  /**
   * An agent's create is the agent's: the source it adds and the participant it becomes are its own
   * principal. Over the controller with its token, and over HTTP with a body that still states
   * another agent as {@code requester} — read past, not refused.
   */
  @Test
  void anAgentStatingAnotherRequesterIsAttributedToItself() {
    String branch = "ticket/" + projectId + "-own";
    ReleaseRequestDto viaToken =
        door(agentToken(branch))
            .create(repoId, new ReleaseRequestController.CreateReleaseRequest(branch, null, null))
            .request();
    assertEquals(AGENT, addedByOf(viaToken, branch));
    assertEquals(List.of(AGENT), viaToken.participants());

    String id =
        asCaller(AGENT)
            .body(
                "{\"branch\":\"feature/stated\",\"summary\":\"typed words\",\"requester\":\""
                    + OTHER_AGENT
                    + "\"}")
            .post(base())
            .then()
            .statusCode(200)
            .extract()
            .path("request.id");
    assertEquals(viaToken.id(), id, "the second ask converges on the open request");
    JsonPath read = read(id);
    assertEquals(AGENT, read.getString("request.sources.find { it.name == 'feature/stated' }.addedBy"));
    assertEquals(List.of(AGENT), read.getList("request.participants"));
    assertFalse(read.getList("request.participants").contains(OTHER_AGENT));
  }

  /** Naming the maintenance robot in the body does not make an agent's request unattended. */
  @Test
  void anAgentStatingTheMaintenanceRobotIsNotUnattended() {
    String id =
        asCaller(AGENT)
            .body("{\"branch\":\"main\",\"requester\":\"" + MAINTENANCE + "\"}")
            .post(base())
            .then()
            .statusCode(200)
            .extract()
            .path("request.id");

    JsonPath read = read(id);
    assertEquals(List.of(AGENT), read.getList("request.participants"));
    assertFalse(read.getBoolean("request.unattended"));
    assertEquals(AGENT, read.getString("request.requester"));
  }

  /**
   * The robot's own main-only bump: its one source is the implied {@code main}, which names nobody,
   * and still the robot is a participant — so the request is unattended.
   */
  @Test
  void aMainOnlyBumpByTheRobotHasItAsParticipantAndIsUnattended() {
    String id =
        asCaller(MAINTENANCE)
            .body("{\"branch\":\"main\",\"summary\":\"bump(dependencies): 2 dependencies\"}")
            .post(base())
            .then()
            .statusCode(200)
            .extract()
            .path("request.id");

    JsonPath read = read(id);
    assertEquals(List.of(MAINTENANCE), read.getList("request.participants"));
    assertTrue(read.getBoolean("request.unattended"));
    assertNull(read.getString("request.sources.find { it.name == 'main' }.addedBy"));
    assertEquals(List.of(), read.getList("request.items"));
    assertEquals("main", read.getString("request.summary"), "no item reads as main");
  }

  /** A maintenance request an agent joined has a reader, so it is attended. */
  @Test
  void aMaintenanceRequestAnAgentJoinedIsAttended() {
    String id = releaseRequests.request(repoId, "main", MAINTENANCE, "LOWEST").id();
    assertTrue(releaseRequests.get(id).unattended(), "the premise: the robot's bump is unattended");

    String branch = "ticket/" + projectId + "-joined";
    ReleaseRequestDto joined =
        door(agentToken(branch))
            .addSource(
                repoId, id, new ReleaseRequestController.AddReleaseRequestSource(branch, null))
            .request();

    assertEquals(List.of(MAINTENANCE, AGENT), joined.participants());
    assertFalse(joined.unattended());
    assertFalse(releaseRequests.get(id).unattended());
  }

  /** A deleted branch takes its source row with it, and leaves its author a participant. */
  @Test
  void aParticipantSurvivesItsBranchsDeletion() {
    String id = releaseRequests.request(repoId, "feature/ada", "ada", null).id();
    assertEquals(id, releaseRequests.request(repoId, "feature/grace", "grace", null).id());

    releaseRequests.onBranchDeleted(repoId, "feature/ada");

    ReleaseRequestDto read = releaseRequests.get(id);
    assertFalse(
        read.sources().stream().anyMatch(source -> source.name().equals("feature/ada")),
        "the premise: the deleted branch's source is gone");
    assertEquals(List.of("ada", "grace"), read.participants());
  }

  // ---- items ------------------------------------------------------------------------------------

  /** A ticket's branch is an item naming the ticket and its title, and the text says the same. */
  @Test
  void aTicketBranchIsAnItemWithItsTitle() {
    long number =
        entities
            .create(
                Archetype.TICKET,
                projectId,
                EntityWrite.ticket("Fix the export", "because", null, "BUG", null),
                "seed")
            .entity()
            .number;
    String qualifiedId = projectId + "-" + number;
    String branch = "ticket/" + qualifiedId;

    String id = releaseRequests.request(repoId, branch, "ada", null).id();

    ReleaseRequestDto read = releaseRequests.get(id);
    assertEquals(
        List.of(
            new ReleaseRequestItemDto(
                branch,
                qualifiedId + " Fix the export",
                new ReleaseRequestWorkItemDto(qualifiedId, "Fix the export"))),
        read.items());
    assertEquals(qualifiedId + " Fix the export", read.summary());
    assertEquals(
        "Release request " + read.qualifiedId() + ": " + qualifiedId + " Fix the export",
        merger.foldsOf("refs/heads/" + read.backingBranch()).get(0).message().lines()
            .findFirst()
            .orElseThrow(),
        "the fold's merge message carries the derived text");
  }

  /**
   * A {@code maintenance/*} branch is labelled by its head commit's subject, read from the git host
   * when the request folds and stored on the source, so a read asks the git host nothing.
   */
  @Test
  void aMaintenanceBranchIsLabelledByItsCommitSubject() {
    String branch = "maintenance/dependencies/" + UUID.randomUUID();
    gitHost.commit(
        repoId,
        projectId,
        null,
        "refs/heads/" + branch,
        null,
        null,
        "bump(dependencies): 2 dependencies\n\nquarkus 3.30 -> 3.31\n",
        Map.of(),
        Map.of());

    String id = releaseRequests.request(repoId, branch, MAINTENANCE, null).id();

    ReleaseRequestDto read = releaseRequests.get(id);
    assertEquals(
        List.of(new ReleaseRequestItemDto(branch, "bump(dependencies): 2 dependencies", null)),
        read.items());
    assertEquals("bump(dependencies): 2 dependencies", read.summary());
    assertEquals(
        "bump(dependencies): 2 dependencies",
        QuarkusTransaction.requiringNew()
            .call(() -> ReleaseRequest.<ReleaseRequest>findById(id).summary),
        "the stored summary is the derived text the fold wrote");
  }

  // ---- helpers ----------------------------------------------------------------------------------

  /** An agent's token for this project, allowed to push {@code branch}. */
  private SecurityIdentity agentToken(String branch) {
    return AgentTokens.token(
        Map.of("sub", AGENT, "project", projectId, "git_refs", List.of("refs/heads/" + branch)),
        "qits:agent");
  }

  private ReleaseRequestController door(SecurityIdentity caller) {
    ReleaseRequestController door = new ReleaseRequestController();
    door.releaseRequests = releaseRequests;
    door.repositories = repositories;
    door.identity = caller;
    return door;
  }

  /** A forwarded caller named {@code user}: its name is the identity a write is attributed to. */
  private static io.restassured.specification.RequestSpecification asCaller(String user) {
    return given()
        .header("X-Qits-User", user)
        .header("X-Qits-Roles", "qits:admin")
        .contentType(ContentType.JSON);
  }

  private String base() {
    return "/projects/api/repositories/" + repoId + "/release-requests";
  }

  private JsonPath read(String id) {
    return given().get(base() + "/" + id).then().statusCode(200).extract().jsonPath();
  }

  private static String addedByOf(ReleaseRequestDto request, String branch) {
    return request.sources().stream()
        .filter(source -> source.name().equals(branch))
        .map(ReleaseRequestSourceDto::addedBy)
        .findFirst()
        .orElseThrow();
  }
}
