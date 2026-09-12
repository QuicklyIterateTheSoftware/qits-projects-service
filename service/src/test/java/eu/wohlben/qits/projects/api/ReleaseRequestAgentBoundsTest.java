package eu.wohlben.qits.projects.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import eu.wohlben.qits.projects.control.ReleaseRequests;
import eu.wohlben.qits.projects.control.RepositoryService;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.error.DomainException;
import eu.wohlben.qits.projects.security.AgentTokens;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * An agent at the release-request door: it reads every request, writes only for its own project,
 * asks to release only a branch its token may push, and never approves.
 *
 * <p>Two layers, tested where each can be reached. The <b>bounds</b> read the token's claims, and
 * a {@code @QuarkusTest} cannot put a token in front of this service: the forwarded-header
 * mechanism's dev user wins over a {@code @TestSecurity} identity. So the controller is driven
 * directly, with a hand-made token and the real beans over real rows. The <b>roles</b> are what
 * the forwarded header carries, so they are tested over HTTP with {@code X-Qits-Roles: qits:agent}.
 */
@QuarkusTest
public class ReleaseRequestAgentBoundsTest {

  private static final String OWN_PROJECT = "agent-bounds-own";
  private static final String OWN_REPO = "agent-bounds-own-repo";
  private static final String FOREIGN_PROJECT = "agent-bounds-foreign";
  private static final String FOREIGN_REPO = "agent-bounds-foreign-repo";

  /** The agent's token: its project, and the one branch it may push. */
  private static final SecurityIdentity AGENT =
      AgentTokens.token(
          Map.of("project", OWN_PROJECT, "git_refs", List.of("refs/heads/ticket/own")),
          "qits:agent");

  @Inject ReleaseRequests releaseRequests;

  @Inject RepositoryService repositories;

  @BeforeEach
  void seed() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              seed(OWN_PROJECT, OWN_REPO);
              seed(FOREIGN_PROJECT, FOREIGN_REPO);
            });
  }

  private static void seed(String projectId, String repoId) {
    if (Project.findById(projectId) != null) {
      return;
    }
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
  }

  /** Open requests must not outlive the class: other tests sweep every open row. */
  @AfterEach
  void dropRequests() {
    QuarkusTransaction.requiringNew()
        .run(() -> ReleaseRequest.delete("projectId in ?1", List.of(OWN_PROJECT, FOREIGN_PROJECT)));
  }

  private ReleaseRequestController door(SecurityIdentity caller) {
    ReleaseRequestController door = new ReleaseRequestController();
    door.releaseRequests = releaseRequests;
    door.repositories = repositories;
    door.identity = caller;
    return door;
  }

  private static ReleaseRequestController.CreateReleaseRequest ask(String branch) {
    return new ReleaseRequestController.CreateReleaseRequest(branch, "an agent's release", null, null);
  }

  private String requestBySomebodyElse(String repoId, String branch) {
    return releaseRequests.request(repoId, branch, "somebody's release", "setup", null).id();
  }

  private static void refused(Executable call) {
    assertEquals(403, assertThrows(DomainException.class, call).statusCode());
  }

  @Test
  void anAgentAsksToReleaseABranchItMayPush() {
    var answer = door(AGENT).create(OWN_REPO, ask("ticket/own"));

    assertEquals(OWN_REPO, answer.request().repoId());
  }

  @Test
  void aBranchOutsideItsGitRefsIs403() {
    refused(() -> door(AGENT).create(OWN_REPO, ask("ticket/other")));
    refused(() -> door(AGENT).create(OWN_REPO, ask("main")));

    String own = requestBySomebodyElse(OWN_REPO, "ticket/own");
    refused(
        () ->
            door(AGENT)
                .addSource(
                    OWN_REPO,
                    own,
                    new ReleaseRequestController.AddReleaseRequestSource("feature/wider", null, null)));
  }

  @Test
  void anotherProjectsRepositoryIs403() {
    refused(() -> door(AGENT).create(FOREIGN_REPO, ask("ticket/own")));
  }

  @Test
  void anAgentWithdrawsInItsOwnProjectOnly() {
    String own = requestBySomebodyElse(OWN_REPO, "ticket/own");
    String foreign = requestBySomebodyElse(FOREIGN_REPO, "feature/elsewhere");
    var reason = new ReleaseRequestController.WithdrawReleaseRequest(null);

    // A foreign request reached through the agent's own repository's path is still foreign.
    refused(() -> door(AGENT).withdraw(OWN_REPO, foreign, reason));
    refused(() -> door(AGENT).withdraw(FOREIGN_REPO, foreign, reason));
    assertEquals("WITHDRAWN", door(AGENT).withdraw(OWN_REPO, own, reason).request().state());
  }

  @Test
  void anAgentReadsEveryProjectsRequests() {
    String foreign = requestBySomebodyElse(FOREIGN_REPO, "feature/elsewhere");

    assertEquals(foreign, door(AGENT).get(FOREIGN_REPO, foreign).request().id());
    door(AGENT).list(FOREIGN_REPO, null);
  }

  /** A caller that also holds qits:system is judged as before, on any repository and branch. */
  @Test
  void aPlatformCallerIsJudgedAsBefore() {
    SecurityIdentity platform = AgentTokens.token(Map.of(), "qits:system", "qits:agent");

    assertEquals(
        FOREIGN_REPO, door(platform).create(FOREIGN_REPO, ask("feature/anything")).request().repoId());
  }

  // ---- the roles, over HTTP ----------------------------------------------------------------------

  private static io.restassured.specification.RequestSpecification asForwardedAgent() {
    return given()
        .header("X-Qits-User", "someone")
        .header("X-Qits-Roles", "qits:agent")
        .contentType(ContentType.JSON);
  }

  private static String base(String repoId) {
    return "/projects/api/repositories/" + repoId + "/release-requests";
  }

  @Test
  void anAgentNeverApprovesOrDeclines() {
    String own = requestBySomebodyElse(OWN_REPO, "ticket/own");

    asForwardedAgent()
        .body("{\"mergedSha\":\"0000000\"}")
        .post(base(OWN_REPO) + "/" + own + "/approve")
        .then()
        .statusCode(403);
    asForwardedAgent()
        .body("{\"mergedSha\":\"0000000\"}")
        .post(base(OWN_REPO) + "/" + own + "/decline")
        .then()
        .statusCode(403);
  }

  /** The agent role from a forwarded header carries no token: it reads, and writes nothing. */
  @Test
  void anAgentRoleWithNoTokenReadsButDoesNotWrite() {
    String own = requestBySomebodyElse(OWN_REPO, "ticket/own");

    asForwardedAgent().get(base(FOREIGN_REPO)).then().statusCode(200);
    asForwardedAgent().get(base(OWN_REPO) + "/" + own).then().statusCode(200);
    asForwardedAgent()
        .body("{\"branch\":\"ticket/own\",\"summary\":\"no token\"}")
        .post(base(OWN_REPO))
        .then()
        .statusCode(403);
  }
}
