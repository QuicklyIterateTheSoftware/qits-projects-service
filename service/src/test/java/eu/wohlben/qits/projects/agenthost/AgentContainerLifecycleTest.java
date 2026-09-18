package eu.wohlben.qits.projects.agenthost;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.api.ProjectController;
import eu.wohlben.qits.projects.api.ProjectRequests;
import eu.wohlben.qits.projectsdaemon.protocol.DaemonProtocol;
import eu.wohlben.qits.projectsdaemon.protocol.Hello;
import eu.wohlben.qits.projectsdaemon.protocol.ProvisionFailed;
import eu.wohlben.qits.projectsdaemon.protocol.Provisioned;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.websockets.next.WebSocketConnection;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The agent-container lifecycle, over the REST surface an SPA panel drives it from.
 *
 * <p>Two things are under test and they are not the same thing. The <b>ladder</b> is which verb runs
 * — a stamp for a running container, a restart for one that is not, a provision for an absent one —
 * and it is asserted against {@link FakeContainerRuntime#calls()}, because a test that only read the
 * answering status would pass just as happily for a ladder that provisioned a place it should have
 * brought back.
 *
 * <p>The <b>response shape</b> is a published contract consumed by a client written in another
 * repository, so it is asserted field by field: {@code container.runtimeStatus}, {@code
 * container.daemonConnected}, {@code container.daemonVersion}, identically from all three routes.
 */
@QuarkusTest
class AgentContainerLifecycleTest {

  @Inject FakeContainerRuntime runtime;

  @Inject AgentContainers agentContainers;

  @Inject AgentDaemonRegistry registry;

  /** Where the image pin the read reports is resolved from — one accessor, one answer. */
  @Inject AgentContainerFactory factory;

  /** Driven directly: its scheduled entry point returns early outside a packaged run. */
  @Inject AgentStaleImageSweep staleImageSweep;

  private String projectId;
  private String slug;
  private String containerName;

  @BeforeEach
  void setUp() {
    runtime.reset();
    String name = "Agent Ladder " + UUID.randomUUID();
    io.restassured.path.json.JsonPath created =
        given()
            .contentType(ContentType.JSON)
            .body(
                new ProjectController.CreateProjectRequest(
                    name, null, null, null, ProjectRequests.DNS))
            .when()
            .post("/projects/api/projects")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
    projectId = created.getString("project.id");
    slug = created.getString("project.slug");
    containerName = "qits-proj-" + slug;
  }

  private String base() {
    return "/projects/api/projects/" + projectId + "/agent-container";
  }

  @Test
  void absentProvisions() {
    given()
        .when()
        .post(base() + "/ensure")
        .then()
        .statusCode(200)
        .body("container.runtimeStatus", org.hamcrest.Matchers.is("RUNNING"))
        .body("container.daemonConnected", org.hamcrest.Matchers.is(false))
        .body("container.daemonVersion", org.hamcrest.Matchers.nullValue());

    assertTrue(
        runtime.calls().stream().anyMatch(call -> call.startsWith("run:" + containerName + ":")),
        "an absent container is provisioned, not started");
    assertTrue(
        runtime.volumes().contains("qits_project_" + projectId),
        "the labelled checkout volume is created before the container mounts it");
  }

  /**
   * A running container is not restarted and not re-provisioned. The one call it does make is a
   * stamp: the orchestrator runs the same idle sweep from its side, and its clock is only ever
   * written when a row is, so without this it would stop a container somebody is working in.
   */
  @Test
  void runningIsStampedAndOtherwiseLeftAlone() {
    runtime.given(projectId, slug, true);

    agentContainers.ensure(projectId);

    assertEquals(java.util.List.of("touch:" + projectId), runtime.calls());
  }

  /**
   * A stopped container is started where it stands — one verb, and the same container afterwards.
   * Provisioning instead would mint a second one, and the state a stop deliberately preserves (the
   * writable layer, and the checkout if the volume were ever missed) would go with the first.
   */
  @Test
  void aContainerThatIsNotRunningIsStartedAgainRatherThanProvisioned() {
    runtime.given(projectId, slug, false);
    String before = runtime.dockerId(projectId);

    agentContainers.ensure(projectId);

    assertEquals(java.util.List.of("restart:" + projectId), runtime.calls());
    assertEquals(before, runtime.dockerId(projectId), "the same container, started again");
  }

  @Test
  void aFailedProvisionAnswersFailed() {
    runtime.failNextRun(new IllegalStateException("no such image"));

    given()
        .when()
        .post(base() + "/ensure")
        .then()
        .statusCode(200)
        .body("container.runtimeStatus", org.hamcrest.Matchers.is("FAILED"))
        .body("container.failureDetail", org.hamcrest.Matchers.is("no such image"));
  }

  /**
   * The container is up, docker calls it healthy, and its {@code /workspace} is empty — the state
   * this read used to describe as {@code RUNNING}, which sent the panel to open a terminal onto
   * nothing. The daemon's word outranks docker's until it takes it back.
   *
   * <p>The frame is delivered with a null connection: the {@code ProvisionFailed} branch reads the
   * message and nothing else, so a socket here would be fixture with no assertion behind it.
   */
  @Test
  void aFailedProvisionMakesARunningContainerReadFailed() {
    runtime.given(projectId, slug, true);
    registry.onMessage(
        projectId, null, new ProvisionFailed(projectId, "clone refused: no such remote"));

    given()
        .when()
        .get(base())
        .then()
        .statusCode(200)
        .body("container.runtimeStatus", org.hamcrest.Matchers.is("FAILED"))
        .body("container.failureDetail", org.hamcrest.Matchers.is("clone refused: no such remote"));

    // A retry that works takes it back, and the panel is usable again with no restart anywhere.
    registry.onMessage(projectId, null, new Provisioned(projectId, "abc123"));

    given()
        .when()
        .get(base())
        .then()
        .statusCode(200)
        .body("container.runtimeStatus", org.hamcrest.Matchers.is("RUNNING"))
        .body("container.failureDetail", org.hamcrest.Matchers.nullValue());
  }

  @Test
  void stopIsGracefulAndIdempotent() {
    runtime.given(projectId, slug, true);

    given()
        .when()
        .post(base() + "/stop")
        .then()
        .statusCode(200)
        .body("container.runtimeStatus", org.hamcrest.Matchers.is("STOPPED"));

    assertEquals(
        java.util.List.of("stop:" + projectId),
        runtime.calls(),
        "stop, never remove: the container and its /workspace volume survive");

    // A second stop finds it already down and says so rather than failing.
    given()
        .when()
        .post(base() + "/stop")
        .then()
        .statusCode(200)
        .body("container.runtimeStatus", org.hamcrest.Matchers.is("STOPPED"));
  }

  /**
   * The round trip the stop policy exists for, end to end over the routes: stop, then ensure, and
   * the same container is running again. This is what "stop, never remove" buys and what a
   * provision-on-wake would silently take away.
   */
  @Test
  void aStoppedAgentComesBackAsTheSameContainer() {
    runtime.given(projectId, slug, true);
    String before = runtime.dockerId(projectId);

    given().when().post(base() + "/stop").then().statusCode(200);
    given()
        .when()
        .post(base() + "/ensure")
        .then()
        .statusCode(200)
        .body("container.runtimeStatus", org.hamcrest.Matchers.is("RUNNING"));

    assertEquals(
        java.util.List.of("stop:" + projectId, "restart:" + projectId),
        runtime.calls(),
        "one stop and one wake — no provision anywhere in a stop/start cycle");
    assertEquals(before, runtime.dockerId(projectId));
  }

  @Test
  void readAnswersAbsentWithNoContainer() {
    given()
        .when()
        .get(base())
        .then()
        .statusCode(200)
        .body("container.runtimeStatus", org.hamcrest.Matchers.is("ABSENT"))
        .body("container.daemonConnected", org.hamcrest.Matchers.is(false));
  }

  @Test
  void readAnswersTheSameShapeAsEnsure() {
    runtime.given(projectId, slug, false);

    given()
        .when()
        .get(base())
        .then()
        .statusCode(200)
        .body("container.runtimeStatus", org.hamcrest.Matchers.is("STOPPED"))
        .body("container.daemonConnected", org.hamcrest.Matchers.is(false))
        .body("container.daemonVersion", org.hamcrest.Matchers.nullValue())
        .body("container.pinnedDaemonVersion", org.hamcrest.Matchers.is(factory.imageVersion()))
        // With no daemon connected nothing has said what the container runs, and that is not a
        // verdict — "not stale" here is the absence of a claim, which is what false has to mean.
        .body("container.daemonVersionStale", org.hamcrest.Matchers.is(false))
        .body("container.failureDetail", org.hamcrest.Matchers.nullValue());
  }

  /**
   * The condition the stale-image sweep acts on, made visible on the read a person already has.
   *
   * <p>The container is {@code RUNNING} and entirely usable — there is no sixth status and there must
   * not be one — and the two extra fields say which daemon it is on and which one a restart would
   * move it to. Seeing that is what lets somebody press the Stop below themselves rather than wait
   * for a sweep.
   */
  @Test
  void aRunningContainerOnAnOlderDaemonReadsStale() {
    runtime.given(projectId, slug, true);
    WebSocketConnection connection = daemonConnection();
    registry.register(projectId, connection);
    registry.onMessage(
        projectId,
        connection,
        new Hello(projectId, "demo-demo", DaemonProtocol.CAPABILITY_VERSION, "2026.101.1", null));
    try {
      given()
          .when()
          .get(base())
          .then()
          .statusCode(200)
          .body("container.runtimeStatus", org.hamcrest.Matchers.is("RUNNING"))
          .body("container.daemonConnected", org.hamcrest.Matchers.is(true))
          .body("container.daemonVersion", org.hamcrest.Matchers.is("2026.101.1"))
          .body("container.pinnedDaemonVersion", org.hamcrest.Matchers.is(factory.imageVersion()))
          .body("container.daemonVersionStale", org.hamcrest.Matchers.is(true));
    } finally {
      registry.unregister(projectId, connection);
      registry.forget(projectId);
    }
  }

  /**
   * The whole of what the stale-image sweep does to a container, and the whole of what it costs.
   *
   * <p>The sweep's action is one stop — never a remove — so the per-project {@code /workspace} volume
   * is not created, claimed or discarded by it, and the container's commissioned credential is not
   * handed back: both belong to a container that is still there, stopped. The next {@code ensure}
   * then takes the wake arm, which is the one ask that carries {@code Recreate.ifChanged} and the
   * only door in this harness through which a new image pin is ever applied. So the round trip is
   * stop, wake, and no provision anywhere — exactly the cycle a person pressing Stop would get.
   */
  @Test
  void aStopTakenForStalenessKeepsTheCheckoutAndWakesRatherThanProvisions() {
    runtime.given(projectId, slug, true);
    WebSocketConnection connection = daemonConnection();
    registry.register(projectId, connection);
    registry.onMessage(
        projectId,
        connection,
        new Hello(projectId, "demo-demo", DaemonProtocol.CAPABILITY_VERSION, "2026.101.1", null));
    // Nothing has happened in it since before the quiet window — the Hello above stamped it.
    registry.touchAgentActivity(projectId, Instant.now().minus(Duration.ofDays(1)));
    String before = runtime.dockerId(projectId);

    try {
      assertEquals(1, staleImageSweep.sweep(Instant.now()));
    } finally {
      registry.unregister(projectId, connection);
    }

    assertEquals(
        java.util.List.of("stop:" + projectId),
        runtime.calls(),
        "one stop and nothing else — no remove, and no stamp on the idle sweep's clock");
    assertEquals(
        java.util.List.of(),
        runtime.volumes(),
        "the checkout volume is neither re-created nor discarded: it is where uncommitted work is");

    given()
        .when()
        .post(base() + "/ensure")
        .then()
        .statusCode(200)
        .body("container.runtimeStatus", org.hamcrest.Matchers.is("RUNNING"));

    assertEquals(
        java.util.List.of("stop:" + projectId, "restart:" + projectId),
        runtime.calls(),
        "the wake arm, which is where Recreate.ifChanged applies the pin — never a provision");
    assertEquals(before, runtime.dockerId(projectId));
  }

  /** A {@code WebSocketConnection} answering only what the registry calls on one. */
  private WebSocketConnection daemonConnection() {
    return (WebSocketConnection)
        java.lang.reflect.Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] {WebSocketConnection.class},
            (proxy, method, args) ->
                switch (method.getName()) {
                  case "id" -> "lifecycle-connection";
                  case "isOpen" -> Boolean.TRUE;
                  case "sendTextAndAwait" -> null;
                  case "equals" -> proxy == args[0];
                  case "hashCode" -> System.identityHashCode(proxy);
                  case "toString" -> "connection lifecycle-connection";
                  default -> null;
                });
  }

  @Test
  void anUnknownProjectIs404OnEveryRoute() {
    String unknown = "/projects/api/projects/" + UUID.randomUUID() + "/agent-container";
    given().when().get(unknown).then().statusCode(404);
    given().when().post(unknown + "/ensure").then().statusCode(404);
    given().when().post(unknown + "/stop").then().statusCode(404);
  }

  /**
   * A refusal is an answer, not a failure to report as {@code FAILED}.
   *
   * <p>The refusal itself belongs to the runtime now — a container name held by a deleted project's
   * place is a 409 raised where the orchestrator's rows can be read, and
   * {@code containershost/ContainersAgentRuntimeTest} is where that is proved. What is this class's
   * is the other half: the ladder lets it through with its status intact, instead of catching it
   * with every other runtime failure and answering 200 {@code FAILED}. A panel told "the runtime
   * broke" would offer a retry; a panel told 409 shows the sentence naming the way out.
   */
  @Test
  void aRefusedProvisionKeepsItsStatus() {
    runtime.failNextRun(
        new eu.wohlben.qits.projects.error.DomainException(
            409, "The container name '" + containerName + "' is already taken"));

    given().when().post(base() + "/ensure").then().statusCode(409);
  }
}
