package eu.wohlben.qits.projects.releasehost;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.control.ReleaseRequestNumbers;
import eu.wohlben.qits.projects.control.ReleaseRequestStateAnnouncer;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.entity.ReleasedTagPendingMerge;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import eu.wohlben.qits.projects.entity.RepositoryName;
import eu.wohlben.qits.projects.maintenancehost.FakeReleaseRequestAutomations;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * qits-1158: a release request's logical id, {@code <repo>-rr-<n>}. Allocated per repository on
 * create, frozen across a rename, the name of the backing branch, carried on the DTO and the
 * events, and accepted by the doors in place of the uuid.
 */
@QuarkusTest
public class ReleaseRequestQualifiedIdTest {

  @Inject ReleaseRequestNumbers numbers;

  @Inject FakeActiveBuilds activeBuilds;

  @Inject RecordingReleaseExecutor executor;

  @Inject RecordingBackingBranchMerger merger;

  @Inject RecordingReleaseGitHost gitHost;

  @Inject FakeReleaseRequestAutomations automations;

  @Inject RecordingReleaseRequestAnnouncer changes;

  @Inject RecordingReleaseRequestStateAnnouncer states;

  private String projectId;
  private String repoId;
  private String otherRepoId;

  @BeforeEach
  void seed() {
    activeBuilds.reset();
    executor.reset();
    merger.reset();
    gitHost.reset();
    automations.reset();
    changes.reset();
    states.reset();
    activeBuilds.answer(Optional.of(0));

    String unique = UUID.randomUUID().toString();
    projectId = "qid-project-" + unique;
    repoId = "qid-repo-" + unique;
    otherRepoId = "qid-other-" + unique;
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Project project = new Project();
              project.id = projectId;
              project.name = "qid";
              project.slug = "qid-" + unique;
              project.persist();
              alias(project, repository(project, repoId), "qid-thing-service");
              alias(project, repository(project, otherRepoId), "qid-other-service");
            });
    gitHost.gatedTree("refs/heads/main", Map.of());
    gitHost.gatedTree("refs/heads/work", Map.of());
  }

  @AfterEach
  void dropTheFixturesRows() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              ReleaseRequest.delete("projectId = ?1", projectId);
              ReleasedTagPendingMerge.delete("repoId in ?1", List.of(repoId, otherRepoId));
            });
  }

  @Test
  public void theAllocatorCountsPerRepositoryAndNeverHandsANumberOutTwice() throws Exception {
    String repo = "qid-alloc-" + UUID.randomUUID();
    assertEquals(1, numbers.next(repo), "a repository with no counter starts at 1");
    assertEquals(2, numbers.next(repo));
    assertEquals(1, numbers.next(repo + "-other"), "each repository counts on its own");

    Set<Long> taken = ConcurrentHashMap.newKeySet();
    ExecutorService pool = Executors.newFixedThreadPool(8);
    try {
      List<Future<Long>> asked =
          java.util.stream.IntStream.range(0, 40)
              .mapToObj(i -> pool.submit(() -> numbers.next(repo)))
              .toList();
      for (Future<Long> one : asked) {
        assertTrue(taken.add(one.get()), "a number is handed out once");
      }
    } finally {
      pool.shutdownNow();
    }
    assertEquals(40, taken.size());
    assertTrue(taken.stream().allMatch(n -> n >= 3 && n <= 42), "and above what came before");
  }

  @Test
  public void aNewRequestIsNamedAfterItsRepositoryAndFoldsOntoThatName() {
    String id = create(repoId);

    request(repoId, id)
        .body("qualifiedId", equalTo("qid-thing-service-rr-1"))
        .body("backingBranch", equalTo("release/qid-thing-service-rr-1"));
    assertTrue(
        !merger.foldsOf("refs/heads/release/qid-thing-service-rr-1").isEmpty(),
        "the fold lands on release/<qualified id>");
    assertEquals(
        "Release request qid-thing-service-rr-1: work",
        merger.foldsOf("refs/heads/release/qid-thing-service-rr-1").get(0).message().lines()
            .findFirst()
            .orElseThrow(),
        "the fold commit names the qualified id");

    RecordingReleaseRequestAnnouncer.Announced changed = changes.announcedFor(id).get(0);
    assertEquals("qid-thing-service-rr-1", changed.releaseRequestQualifiedId());
    assertEquals("release/qid-thing-service-rr-1", changed.backingBranch());
    ReleaseRequestStateAnnouncer.StateChange created = states.changesOf(id).get(0);
    assertEquals("qid-thing-service-rr-1", created.releaseRequestQualifiedId());
    assertEquals("release/qid-thing-service-rr-1", created.backingBranch());
  }

  @Test
  public void everyDoorTakesTheUuidOrTheQualifiedIdInAnyCase() {
    String id = create(repoId);

    request(repoId, id).body("id", equalTo(id));
    request(repoId, "qid-thing-service-rr-1").body("id", equalTo(id));
    request(repoId, "QID-Thing-Service-RR-1").body("id", equalTo(id));
    given()
        .get(base(repoId) + "/qid-thing-service-rr-1/approvals")
        .then()
        .statusCode(200);

    // A qualified id is looked up within the repository the path names.
    given().get(base(otherRepoId) + "/qid-thing-service-rr-1").then().statusCode(404);
    given().get(base(repoId) + "/qid-thing-service-rr-9").then().statusCode(404);

    given()
        .contentType(ContentType.JSON)
        .body("{\"reason\":\"not wanted\"}")
        .post(base(repoId) + "/qid-thing-service-rr-1/withdraw")
        .then()
        .statusCode(200)
        .body("request.id", equalTo(id))
        .body("request.state", equalTo("WITHDRAWN"));
  }

  @Test
  public void aRenameKeepsTheOldIdsAndTheCounterGoesOnUnderTheNewName() {
    String first = create(repoId);
    withdraw(repoId, first);

    QuarkusTransaction.requiringNew()
        .run(
            () ->
                RepositoryName.update(
                    "name = ?1 where repository.id = ?2", "qid-renamed-service", repoId));

    String second = create(repoId);

    request(repoId, first)
        .body("qualifiedId", equalTo("qid-thing-service-rr-1"))
        .body("backingBranch", equalTo("release/qid-thing-service-rr-1"));
    request(repoId, second)
        .body("qualifiedId", equalTo("qid-renamed-service-rr-2"))
        .body("backingBranch", equalTo("release/qid-renamed-service-rr-2"));
    request(repoId, "qid-thing-service-rr-1").body("id", equalTo(first));
  }

  // -----------------------------------------------------------------------------------------

  private static Repository repository(Project project, String id) {
    Repository repository = new Repository();
    repository.id = id;
    repository.project = project;
    repository.mainBranch = "main";
    repository.archetype = RepositoryArchetype.SERVICE;
    repository.persist();
    return repository;
  }

  private static void alias(Project project, Repository repository, String name) {
    RepositoryName row = new RepositoryName();
    row.project = project;
    row.repository = repository;
    row.name = name;
    row.persist();
  }

  private static String base(String repoId) {
    return "/projects/api/repositories/" + repoId + "/release-requests";
  }

  private static String create(String repoId) {
    return given()
        .header("X-Qits-User", "ada")
        .header("X-Qits-Roles", "qits:admin")
        .contentType(ContentType.JSON)
        .body("{\"branch\":\"work\",\"summary\":\"a release\"}")
        .post(base(repoId))
        .then()
        .statusCode(200)
        .extract()
        .path("request.id");
  }

  private static void withdraw(String repoId, String id) {
    given()
        .contentType(ContentType.JSON)
        .body("{\"reason\":\"make room\"}")
        .post(base(repoId) + "/" + id + "/withdraw")
        .then()
        .statusCode(200);
  }

  private static io.restassured.response.ValidatableResponse request(String repoId, String id) {
    return given().get(base(repoId) + "/" + id).then().statusCode(200).rootPath("request");
  }
}
