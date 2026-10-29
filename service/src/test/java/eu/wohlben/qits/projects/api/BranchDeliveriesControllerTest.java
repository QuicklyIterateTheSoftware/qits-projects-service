package eu.wohlben.qits.projects.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import eu.wohlben.qits.projects.control.BranchDeliveries;
import eu.wohlben.qits.projects.entity.BranchDelivery;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code GET /projects/api/branch-deliveries} (qits-1720): one test per answer the door gives — a
 * branch's rows with its {@code everywhere}, a repository narrowing the rows and not the word, an
 * unknown branch's empty 200, a branch in two projects refused without {@code project} and answered
 * with it (by id and by slug), the 400 and 404 for a missing branch and an unknown project, and
 * the project's {@code qits-agent-config} row listed but never counted (qits-1736).
 *
 * <p>The ledger rows are written straight to {@code branch_delivery}: what is under test is the
 * read, and {@code BranchDeliveryTest} is where the flows that write them are driven.
 */
@QuarkusTest
public class BranchDeliveriesControllerTest {

  private static final String DOOR = "/projects/api/branch-deliveries";

  private String projectId;
  private String otherProjectId;
  private String slug;
  private String branch;
  private final List<String> repositories = new ArrayList<>();

  @BeforeEach
  void seed() {
    String unique = UUID.randomUUID().toString().substring(0, 8);
    projectId = "branch-door-" + UUID.randomUUID();
    otherProjectId = "branch-door-other-" + UUID.randomUUID();
    slug = "branch-door-" + unique;
    branch = "ticket/branch-door-" + unique;
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              project(projectId, slug);
              project(otherProjectId, slug + "-other");
              row(projectId, "repo-svc", "door-service", "RELEASED", "2026.1028.10000");
              row(projectId, "repo-app", "door-app", "PUSHED", null);
            });
  }

  @AfterEach
  void drop() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              BranchDelivery.delete("branch", branch);
              if (!repositories.isEmpty()) {
                Repository.delete("id in ?1", repositories);
              }
              Project.delete("id in ?1", List.of(projectId, otherProjectId));
            });
  }

  @Test
  public void aBranchAnswersItsRowsAndItsLeastAdvancedStep() {
    JsonPath answer =
        given().queryParam("branch", branch).get(DOOR).then().statusCode(200).extract().jsonPath();

    assertEquals(branch, answer.getString("branch"));
    assertEquals("PUSHED", answer.getString("everywhere"), "door-app has not asked yet");
    assertEquals(List.of("door-app", "door-service"), answer.getList("deliveries.repository"));
    assertEquals(List.of("PUSHED", "RELEASED"), answer.getList("deliveries.step"));
    assertEquals("2026.1028.10000", answer.getString("deliveries[1].version"));
    assertEquals("door-service-rr-1", answer.getString("deliveries[1].qualifiedId"));
    assertEquals(projectId, answer.getString("deliveries[1].projectId"));
    assertNull(answer.get("deliveries[0].requestId"), "a PUSHED row names no request");
  }

  @Test
  public void aRepositoryNarrowsTheDeliveriesButNotEverywhere() {
    JsonPath answer =
        given()
            .queryParam("branch", branch)
            .queryParam("repository", "door-service")
            .get(DOOR)
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();

    assertEquals(List.of("door-service"), answer.getList("deliveries.repository"));
    assertEquals("PUSHED", answer.getString("everywhere"), "computed over the whole project");
  }

  @Test
  public void anUnknownBranchIsAnEmptyAnswerRatherThanA404() {
    JsonPath answer =
        given()
            .queryParam("branch", "ticket/nothing-happened-" + UUID.randomUUID())
            .get(DOOR)
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();

    assertEquals(List.of(), answer.getList("deliveries"));
    assertNull(answer.get("everywhere"));
  }

  @Test
  public void aBranchInTwoProjectsNeedsTheProjectNamed() {
    QuarkusTransaction.requiringNew()
        .run(() -> row(otherProjectId, "repo-other", "door-other", "LIVE", "2026.1028.20000"));

    assertEquals(
        BranchDeliveries.SEVERAL_PROJECTS,
        given()
            .queryParam("branch", branch)
            .get(DOOR)
            .then()
            .statusCode(400)
            .extract()
            .path("message"));

    JsonPath byId =
        given()
            .queryParam("branch", branch)
            .queryParam("project", otherProjectId)
            .get(DOOR)
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
    assertEquals(List.of("door-other"), byId.getList("deliveries.repository"));
    assertEquals("LIVE", byId.getString("everywhere"), "per project: the other one is all live");

    JsonPath bySlug =
        given()
            .queryParam("branch", branch)
            .queryParam("project", slug)
            .get(DOOR)
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
    assertEquals(List.of("door-app", "door-service"), bySlug.getList("deliveries.repository"));
    assertEquals("PUSHED", bySlug.getString("everywhere"));
  }

  /**
   * qits-1736: the agent configuration's row is listed, but it is platform state a desk approval
   * releases after the work, so it never holds the branch below LIVE.
   */
  @Test
  public void theAgentConfigRowIsListedButNeverCountsTowardEverywhere() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              BranchDelivery.delete("branch", branch);
              row(projectId, "repo-svc", "door-service", "LIVE", "2026.1028.30000");
              agentConfigRow(projectId, "PUSHED");
            });

    JsonPath answer =
        given().queryParam("branch", branch).get(DOOR).then().statusCode(200).extract().jsonPath();

    assertEquals("LIVE", answer.getString("everywhere"), "qits-agent-config never counts");
    assertEquals(
        List.of("door-service", "qits-agent-config"), answer.getList("deliveries.repository"));
    assertEquals(List.of("LIVE", "PUSHED"), answer.getList("deliveries.step"));
  }

  @Test
  public void aBranchKnownOnlyInTheAgentConfigHasNoEverywhere() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              BranchDelivery.delete("branch", branch);
              agentConfigRow(projectId, "JOINED");
            });

    JsonPath answer =
        given().queryParam("branch", branch).get(DOOR).then().statusCode(200).extract().jsonPath();

    assertEquals(List.of("qits-agent-config"), answer.getList("deliveries.repository"));
    assertEquals(List.of("JOINED"), answer.getList("deliveries.step"));
    assertNull(answer.get("everywhere"), "no row that counts");
  }

  @Test
  public void aMissingBranchIsA400AndAnUnknownProjectA404() {
    given().get(DOOR).then().statusCode(400);
    given()
        .queryParam("branch", branch)
        .queryParam("project", "no-such-project-" + UUID.randomUUID())
        .get(DOOR)
        .then()
        .statusCode(404);
  }

  private static void project(String id, String slug) {
    Project project = new Project();
    project.id = id;
    project.name = slug;
    project.slug = slug;
    project.persist();
  }

  /** A real {@code AGENT_CONFIG} repository of {@code project} and the branch's row in it. */
  private void agentConfigRow(String project, String step) {
    Repository repository = new Repository();
    repository.id = "repo-agent-config-" + UUID.randomUUID();
    repository.project = Project.findById(project);
    repository.mainBranch = "main";
    repository.archetype = RepositoryArchetype.AGENT_CONFIG;
    repository.persist();
    repositories.add(repository.id);
    BranchDelivery row = new BranchDelivery();
    row.projectId = project;
    row.repoId = repository.id;
    row.repoName = "qits-agent-config";
    row.branch = branch;
    row.step = step;
    if (!"PUSHED".equals(step)) {
      row.requestId = UUID.randomUUID().toString();
      row.qualifiedId = "qits-agent-config-rr-1";
      row.foldSha = "f01d";
    }
    row.changedAt = Instant.now();
    row.persist();
  }

  private void row(String project, String repoId, String repoName, String step, String version) {
    BranchDelivery row = new BranchDelivery();
    row.projectId = project;
    row.repoId = repoId + "-" + UUID.randomUUID();
    row.repoName = repoName;
    row.branch = branch;
    row.step = step;
    if (!"PUSHED".equals(step)) {
      row.requestId = UUID.randomUUID().toString();
      row.qualifiedId = repoName + "-rr-1";
      row.foldSha = "f01d";
    }
    row.version = version;
    row.changedAt = Instant.now();
    row.persist();
  }
}
