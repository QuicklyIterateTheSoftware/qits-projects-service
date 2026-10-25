package eu.wohlben.qits.projects.releasehost;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.contracts.ContractBearers;
import eu.wohlben.qits.projects.control.ReleasePipelineRuns;
import eu.wohlben.qits.projects.control.ReleaseRequestAnnouncer;
import eu.wohlben.qits.projects.control.ReleaseRequests;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.ReleasePipelineRun;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.entity.ReleaseRequestUpstreamOverride;
import eu.wohlben.qits.projects.entity.ReleasedTagPendingMerge;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.entity.RepositoryArchetype;
import eu.wohlben.qits.projects.entity.RepositoryName;
import eu.wohlben.qits.projects.maintenancehost.FakeDownstreamComponents;
import eu.wohlben.qits.projects.maintenancehost.FakeReleaseRequestAutomations;
import eu.wohlben.qits.projects.security.FakeSessionIntrospection;
import eu.wohlben.qits.projects.security.NoDevUserProfile;
import eu.wohlben.qits.projects.security.PersonCheck;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
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
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>A person overrides gate 3 for one upstream request</b> (epic qits-897, qits-914): {@code POST
 * …/release-requests/{requestId}/upstream-overrides}. {@code UpstreamInFlightGateTest}'s fixture —
 * a frontend and a service, the fake closure saying the service is downstream of the frontend —
 * reached through the door a person presses.
 *
 * <p>Under {@link NoDevUserProfile}, as {@code ReleaseRequestApprovalDoorTest} is and for its
 * reason: with the {@code %test} dev user blanked an identity is exactly what the caller sends, so
 * every call here states its caller. A person is a browser session (the cookie this service
 * introspects); a service client and an agent are their bearers ({@link ContractBearers}); an
 * administrator asserted in {@code X-Qits-*} headers alone is nobody.
 */
@QuarkusTest
@TestProfile(NoDevUserProfile.class)
public class UpstreamOverrideDoorTest {

  private static final String SERVICE_CLIENT_BEARER = "Bearer upstream-override-service-client";
  private static final String SERVICE_CLIENT_AS_ADMIN_BEARER =
      "Bearer upstream-override-service-client-admin";
  private static final String AGENT_BEARER = "Bearer upstream-override-agent";
  private static final String ADMIN_AGENT_BEARER = "Bearer upstream-override-admin-agent";
  private static final String DESK_BEARER = "Bearer upstream-override-desk";
  private static final String OTHER_DESK_BEARER = "Bearer upstream-override-other-desk";

  @Inject FakeActiveBuilds activeBuilds;

  @Inject RecordingReleaseExecutor executor;

  @Inject RecordingBackingBranchMerger merger;

  @Inject RecordingReleaseGitHost gitHost;

  @Inject RecordingFoldChanges foldChanges;

  @Inject RecordingReleaseRequestAnnouncer announcer;

  @Inject RecordingQaRunCancellations cancellations;

  @Inject FakeReleaseRequestAutomations automations;

  @Inject FakeDownstreamComponents downstream;

  @Inject ContractBearers bearers;

  @Inject ReleaseRequests releaseRequests;

  @Inject ReleasePipelineRuns pipelineRuns;

  private String projectId;
  private String feRepo;
  private String svcRepo;
  private String svcName;

  private final List<String> repoIds = new ArrayList<>();

  private final List<String> requestIds = new ArrayList<>();

  /** Strictly increasing frame instants, so every run transition is newer than the last. */
  private final AtomicLong clock = new AtomicLong(Instant.now().toEpochMilli());

  @BeforeEach
  void seed() {
    activeBuilds.reset();
    executor.reset();
    merger.reset();
    gitHost.reset();
    foldChanges.reset();
    automations.reset();
    announcer.reset();
    cancellations.reset();
    downstream.reset();
    bearers.reset();
    repoIds.clear();
    requestIds.clear();
    activeBuilds.answer(Optional.of(0));
    gitHost.gatedTree("refs/heads/work", Map.of("README.md", "work"));

    String unique = UUID.randomUUID().toString();
    projectId = "override-project-" + unique;
    String token = unique.substring(0, 8);
    feRepo = "override-fe-" + unique;
    String feName = "ofe-" + token;
    svcRepo = "override-svc-" + unique;
    svcName = "osvc-" + token;
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Project project = new Project();
              project.id = projectId;
              project.name = "override";
              project.slug = "override-" + unique;
              project.persist();
            });
    repository(feRepo, feName);
    repository(svcRepo, svcName);
    downstream.answerFor(feRepo, Optional.of(List.of(svcName)));
    downstream.answerFor(svcRepo, Optional.of(List.of()));

    bearers.grant(
        SERVICE_CLIENT_BEARER,
        "dev-qits-maintenance",
        Map.of("groups", List.of("clients/dev-qits-maintenance", "qits:system")),
        "qits:system");
    bearers.grant(
        SERVICE_CLIENT_AS_ADMIN_BEARER,
        "dev-qits-ops",
        Map.of("groups", List.of("clients/dev-qits-ops", "qits:admin")),
        "qits:admin");
    bearers.grant(
        AGENT_BEARER,
        "agent-client",
        Map.of("context_kind", "agent", "project", projectId, "groups", List.of("qits:agent")),
        "qits:agent");
    bearers.grant(
        ADMIN_AGENT_BEARER,
        "admin-agent-client",
        Map.of(
            "context_kind",
            "agent",
            "project",
            projectId,
            "credential_type",
            "cli",
            "groups",
            List.of("qits:admin-agent")),
        "qits:admin-agent");
    grantDesk(DESK_BEARER, projectId);
    grantDesk(OTHER_DESK_BEARER, "another-project-" + unique);
  }

  /** A project's delivery desk token as this service commissions it (qits-1596). */
  private void grantDesk(String bearer, String project) {
    String subject = "desk-of-" + project;
    bearers.grant(
        bearer,
        subject,
        Map.of(
            "context_kind",
            "desk-delivery",
            "project",
            project,
            "groups",
            List.of("qits:agent", "qits:desk", "clients/" + subject)),
        "qits:agent",
        "qits:desk");
  }

  @AfterEach
  void dropTheFixturesRows() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              if (!requestIds.isEmpty()) {
                ReleaseRequestUpstreamOverride.delete("requestId in ?1", requestIds);
              }
              ReleasePipelineRun.delete("repoId in ?1", repoIds);
              ReleasedTagPendingMerge.delete("repoId in ?1", repoIds);
              ReleaseRequest.delete("projectId = ?1", projectId);
            });
    automations.reset();
    downstream.reset();
    foldChanges.reset();
    bearers.reset();
  }

  // -----------------------------------------------------------------------------------------
  // The door
  // -----------------------------------------------------------------------------------------

  /**
   * A person overrides the one building upstream: the gate passes, its check is PASSED and names
   * them, and the held fold is announced {@code preRun=DONE} on the click. Both ids are qualified
   * ids, which the door resolves.
   */
  @Test
  public void aPersonsOverrideLiftsTheGateAndAnnouncesTheFold() {
    String fe = create(feRepo);
    qaRun(fe, feRepo, mergedShaOf(feRepo, fe), "run-fe-1", "QUEUED");
    String svc = create(svcRepo);
    String svcFold = mergedShaOf(svcRepo, svc);
    String feQid = BackingBranches.qualifiedIdOf(fe);
    assertEquals(List.of("PENDING"), preRuns(svc), "held before the override");

    JsonPath answer =
        person("wohlben")
            .body(
                "{\"upstreamRequestId\":\""
                    + feQid
                    + "\",\"note\":\"The frontend change is docs only.\"}")
            .post(base(svcRepo) + "/" + BackingBranches.qualifiedIdOf(svc) + "/upstream-overrides")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .setRootPath("request");

    assertEquals(svc, answer.getString("id"));
    assertEquals("PENDING", answer.getString("state"));
    assertEquals("PASSED", gate(answer, "state"));
    assertEquals(List.of(feQid), answer.getList(gatePath("checks.name")));
    assertEquals(List.of("PASSED"), answer.getList(gatePath("checks.state")));
    String check = answer.getString(gatePath("checks[0].detail"));
    assertTrue(check.startsWith("Overridden by wohlben"), check);
    assertEquals("Overridden: " + feQid + " (wohlben)", gate(answer, "detail"));
    assertEquals("PASSED", answer.getString("preRun.state"));
    assertEquals(
        "Waiting for a CI verdict for " + svcFold.substring(0, 10), answer.getString("detail"));

    assertEquals(List.of("PENDING", "DONE"), preRuns(svc));
    assertEquals(svcFold, qaAnnouncedShaOf(svc));
    ReleaseRequestUpstreamOverride row = override(svc, fe);
    assertEquals("wohlben", row.decidedBy);
    assertEquals("The frontend change is docs only.", row.note);
  }

  /**
   * Only a person: a service client's bearer, an agent's, and an administrator asserted through
   * headers alone are each a 403, and nothing is written.
   */
  @Test
  public void machinesAgentsAndAssertedHeadersMayNotOverride() {
    String fe = create(feRepo);
    qaRun(fe, feRepo, mergedShaOf(feRepo, fe), "run-fe-1", "QUEUED");
    String svc = create(svcRepo);
    String body = "{\"upstreamRequestId\":\"" + fe + "\"}";

    for (String bearer :
        List.of(
            SERVICE_CLIENT_BEARER,
            SERVICE_CLIENT_AS_ADMIN_BEARER,
            AGENT_BEARER,
            ADMIN_AGENT_BEARER)) {
      given()
          .contentType(ContentType.JSON)
          .header("X-Qits-User", "a-machine")
          .header("X-Qits-Roles", "qits:admin")
          .header("Authorization", bearer)
          .body(body)
          .post(base(svcRepo) + "/" + svc + "/upstream-overrides")
          .then()
          .statusCode(403);
    }
    headersOnly("mallory", "qits:admin")
        .body(body)
        .post(base(svcRepo) + "/" + svc + "/upstream-overrides")
        .then()
        .statusCode(403);
    headersOnly("an-agent", "qits:agent")
        .body(body)
        .post(base(svcRepo) + "/" + svc + "/upstream-overrides")
        .then()
        .statusCode(403);

    assertNull(override(svc, fe), "nothing was recorded");
    assertEquals(List.of("PENDING"), preRuns(svc), "and the fold is still held");
    assertEquals("PENDING", gate(request(svcRepo, svc), "state"));
  }

  /**
   * qits-1596: the project's delivery desk overrides as a person does, recorded under its token
   * subject; another project's desk is a 403 and writes nothing.
   */
  @Test
  public void theProjectsDeliveryDeskOverridesAsItself() {
    String fe = create(feRepo);
    qaRun(fe, feRepo, mergedShaOf(feRepo, fe), "run-fe-1", "QUEUED");
    String svc = create(svcRepo);
    String body = "{\"upstreamRequestId\":\"" + fe + "\",\"note\":\"docs only\"}";

    asBearer(OTHER_DESK_BEARER)
        .body(body)
        .post(base(svcRepo) + "/" + svc + "/upstream-overrides")
        .then()
        .statusCode(403);
    assertNull(override(svc, fe), "another project's desk recorded nothing");

    JsonPath answer =
        asBearer(DESK_BEARER)
            .body(body)
            .post(base(svcRepo) + "/" + svc + "/upstream-overrides")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .setRootPath("request");
    assertEquals("PASSED", gate(answer, "state"));
    String desk = "desk-of-" + projectId;
    assertTrue(answer.getString(gatePath("checks[0].detail")).startsWith("Overridden by " + desk));
    assertEquals(desk, override(svc, fe).decidedBy);
  }

  /**
   * qits-1596: the desk never opens a release request and never joins a branch to one — the one
   * commit-and-push door the coordinator holds and the desk does not.
   */
  @Test
  public void theDeliveryDeskNeverOpensOrJoinsARequest() {
    asBearer(DESK_BEARER)
        .body("{\"branch\":\"work\",\"summary\":\"a release\"}")
        .post(base(svcRepo))
        .then()
        .statusCode(403);
    String svc = create(svcRepo);
    asBearer(DESK_BEARER)
        .body("{\"branch\":\"work\"}")
        .post(base(svcRepo) + "/" + svc + "/sources")
        .then()
        .statusCode(403);
    // Withdraw is the coordinator's and stays the desk's, in its own project.
    asBearer(OTHER_DESK_BEARER)
        .body("{\"reason\":\"not ours\"}")
        .post(base(svcRepo) + "/" + svc + "/withdraw")
        .then()
        .statusCode(403);
    asBearer(DESK_BEARER)
        .body("{\"reason\":\"moot\"}")
        .post(base(svcRepo) + "/" + svc + "/withdraw")
        .then()
        .statusCode(200);
    assertEquals("WITHDRAWN", request(svcRepo, svc).getString("state"));
  }

  /** An upstream that is not building holds nothing: overriding it is a 409 saying so. */
  @Test
  public void anUpstreamThatIsNotBuildingIsMoot() {
    String fe = create(feRepo);
    qaRun(fe, feRepo, mergedShaOf(feRepo, fe), "run-fe-1", "SUCCESS");
    String svc = create(svcRepo);
    assertEquals(List.of("DONE"), preRuns(svc));
    String feQid = BackingBranches.qualifiedIdOf(fe);

    String message =
        person("wohlben")
            .body("{\"upstreamRequestId\":\"" + feQid + "\"}")
            .post(base(svcRepo) + "/" + svc + "/upstream-overrides")
            .then()
            .statusCode(409)
            .extract()
            .path("message");

    assertEquals("The override is moot: " + feQid + " is not building", message);
    assertNull(override(svc, fe));
  }

  /** The override is about the pair: a re-fold of either request keeps it. */
  @Test
  public void aRefoldOfEitherRequestKeepsTheOverride() {
    String fe = create(feRepo);
    String feFold = mergedShaOf(feRepo, fe);
    qaRun(fe, feRepo, feFold, "run-fe-1", "QUEUED");
    String svc = create(svcRepo);
    String first = mergedShaOf(svcRepo, svc);
    overrideAs("wohlben", svc, fe);
    assertEquals(List.of("PENDING", "DONE"), preRuns(svc));

    // The frontend re-folds and builds again at its new fold.
    releaseRequests.onBranchMoved(feRepo, "work", freshSha());
    String feSecond = mergedShaOf(feRepo, fe);
    assertNotEquals(feFold, feSecond);
    qaRun(fe, feRepo, feSecond, "run-fe-2", "QUEUED");
    // The service re-folds before any verdict: its new fold is not held.
    releaseRequests.onBranchMoved(svcRepo, "work", freshSha());
    String second = mergedShaOf(svcRepo, svc);
    assertNotEquals(first, second);

    List<String> announced = preRuns(svc);
    assertEquals("DONE", announced.get(announced.size() - 1), "announced: " + announced);
    assertTrue(!announced.subList(2, announced.size()).contains("PENDING"), "" + announced);
    assertEquals(second, qaAnnouncedShaOf(svc));
    JsonPath answer = request(svcRepo, svc);
    assertEquals("PASSED", gate(answer, "state"));
    assertEquals(List.of("run-fe-2"), answer.getList(gatePath("checks.runId")));
    assertTrue(answer.getString(gatePath("checks[0].detail")).startsWith("Overridden by wohlben"));
  }

  /** The upstream going inactive and active again stays overridden. */
  @Test
  public void anUpstreamThatStopsAndBuildsAgainStaysOverridden() {
    String fe = create(feRepo);
    String feFold = mergedShaOf(feRepo, fe);
    qaRun(fe, feRepo, feFold, "run-fe-1", "RUNNING");
    String svc = create(svcRepo);
    overrideAs("wohlben", svc, fe);

    // Inactive: its run finished green and it waits on nothing that builds.
    qaRun(fe, feRepo, feFold, "run-fe-1", "SUCCESS");
    assertTrue(request(svcRepo, svc).getList(gatePath("checks")).isEmpty());
    // Active again: READY.
    setState(fe, ReleaseRequest.State.READY);
    releaseRequests.onBranchMoved(svcRepo, "work", freshSha());
    String second = mergedShaOf(svcRepo, svc);

    assertEquals(second, qaAnnouncedShaOf(svc), "the new fold is not held");
    JsonPath answer = request(svcRepo, svc);
    assertEquals("PASSED", gate(answer, "state"));
    assertEquals(List.of("PASSED"), answer.getList(gatePath("checks.state")));
    assertTrue(answer.getString(gatePath("checks[0].detail")).startsWith("Overridden by wohlben"));
  }

  // -----------------------------------------------------------------------------------------
  // Driving it
  // -----------------------------------------------------------------------------------------

  /** A browser session as the edge forwards it: the headers and the cookie this service verifies. */
  private RequestSpecification person(String name) {
    return headersOnly(name, "qits:admin")
        .cookie(PersonCheck.SESSION_COOKIE, FakeSessionIntrospection.admin(name));
  }

  /** A bearer {@link ContractBearers} turns into its granted machine identity. */
  private static RequestSpecification asBearer(String bearer) {
    return given()
        .contentType(ContentType.JSON)
        .header("X-Qits-User", "a-machine")
        .header("X-Qits-Roles", "qits:agent")
        .header("Authorization", bearer);
  }

  /** An asserted caller, headers only, nothing this service could verify. */
  private static RequestSpecification headersOnly(String user, String roles) {
    return given()
        .contentType(ContentType.JSON)
        .header("X-Qits-User", user)
        .header("X-Qits-Roles", roles);
  }

  private void overrideAs(String name, String svc, String fe) {
    person(name)
        .body("{\"upstreamRequestId\":\"" + fe + "\"}")
        .post(base(svcRepo) + "/" + svc + "/upstream-overrides")
        .then()
        .statusCode(200);
  }

  private static String base(String repoId) {
    return "/projects/api/repositories/" + repoId + "/release-requests";
  }

  private void repository(String id, String name) {
    repoIds.add(id);
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Project project = Project.findById(projectId);
              Repository repository = new Repository();
              repository.id = id;
              repository.project = project;
              repository.mainBranch = "main";
              repository.archetype = RepositoryArchetype.SERVICE;
              repository.persist();
              RepositoryName alias = new RepositoryName();
              alias.project = project;
              alias.repository = repository;
              alias.name = name;
              alias.persist();
            });
  }

  private String create(String inRepo) {
    String id =
        headersOnly("ada", "qits:admin")
            .body("{\"branch\":\"work\",\"summary\":\"a release\",\"requester\":\"ada\"}")
            .post(base(inRepo))
            .then()
            .statusCode(200)
            .extract()
            .path("request.id");
    requestIds.add(id);
    return id;
  }

  private JsonPath request(String inRepo, String id) {
    return headersOnly("ada", "qits:admin")
        .get(base(inRepo) + "/" + id)
        .then()
        .statusCode(200)
        .extract()
        .jsonPath()
        .setRootPath("request");
  }

  private static String gatePath(String member) {
    return "qualityGates.find { it.kind == 'upstream-in-flight' }." + member;
  }

  private static String gate(JsonPath answer, String member) {
    return answer.getString(gatePath(member));
  }

  private String mergedShaOf(String inRepo, String id) {
    return request(inRepo, id).getString("mergedSha");
  }

  private void qaRun(String requestId, String inRepo, String fold, String runId, String status) {
    pipelineRuns.record(
        new ReleasePipelineRuns.Transition(
            runId,
            inRepo,
            requestId,
            ReleasePipelineRuns.PHASE_RELEASE_REQUEST,
            status,
            BackingBranches.of(requestId),
            fold,
            Instant.ofEpochMilli(clock.addAndGet(1000)),
            null));
  }

  private static void setState(String id, ReleaseRequest.State state) {
    QuarkusTransaction.requiringNew()
        .run(() -> ReleaseRequest.<ReleaseRequest>findById(id).state = state);
  }

  private static String freshSha() {
    return UUID.randomUUID().toString().replace("-", "");
  }

  private static ReleaseRequestUpstreamOverride override(String requestId, String upstreamId) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                ReleaseRequestUpstreamOverride.<ReleaseRequestUpstreamOverride>findById(
                    new ReleaseRequestUpstreamOverride.Key(requestId, upstreamId)));
  }

  private String qaAnnouncedShaOf(String id) {
    return QuarkusTransaction.requiringNew()
        .call(() -> ReleaseRequest.<ReleaseRequest>findById(id).qaAnnouncedSha);
  }

  /** The preRun of every announcement about one request, oldest first. */
  private List<String> preRuns(String id) {
    return announcer.announcedFor(id).stream()
        .map(RecordingReleaseRequestAnnouncer.Announced::preRun)
        .map(
            preRun ->
                preRun == null
                    ? "absent"
                    : ReleaseRequestAnnouncer.PRE_RUN_DONE.equals(preRun) ? "DONE" : preRun)
        .toList();
  }
}
