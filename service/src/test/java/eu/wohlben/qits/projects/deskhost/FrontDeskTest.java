package eu.wohlben.qits.projects.deskhost;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.agenthost.AgentRuntimeStatus;
import eu.wohlben.qits.projects.control.DeskRunners;
import eu.wohlben.qits.projects.control.ProjectService;
import eu.wohlben.qits.projects.dto.DeskRunnerDto;
import eu.wohlben.qits.projects.entity.DeskRunner;
import eu.wohlben.qits.projects.entity.FrontDesk;
import eu.wohlben.qits.projects.entity.FrontDeskDesired;
import eu.wohlben.qits.projects.entity.FrontDeskLifecycle;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.persistence.DeskRunnerRepository;
import eu.wohlben.qits.projects.persistence.FrontDeskRepository;
import eu.wohlben.qits.projects.persistence.ProjectRepository;
import eu.wohlben.qits.projects.security.MockIdpTenant;
import eu.wohlben.qits.projectsdeskrunner.protocol.DeskRunnerBinary;
import eu.wohlben.qits.projectsdeskrunner.protocol.DeskRunnerProtocol;
import eu.wohlben.qits.projectsdeskrunner.protocol.DeskState;
import eu.wohlben.qits.projectsdeskrunner.protocol.DesiredState;
import eu.wohlben.qits.projectsdeskrunner.protocol.Estate;
import eu.wohlben.qits.projectsdeskrunner.protocol.HeldDesk;
import eu.wohlben.qits.projectsdeskrunner.protocol.Inventory;
import eu.wohlben.qits.projectsdeskrunner.protocol.LaunchFailed;
import eu.wohlben.qits.projectsdeskrunner.protocol.Remove;
import eu.wohlben.qits.projectsdeskrunner.protocol.Removed;
import eu.wohlben.qits.projectsdeskrunner.protocol.Take;
import eu.wohlben.qits.runner.protocol.Ack;
import eu.wohlben.qits.runner.protocol.Nothing;
import eu.wohlben.qits.runner.protocol.Reserve;
import eu.wohlben.qits.servicemock.idp.MockIdp;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import io.vertx.core.Vertx;
import jakarta.inject.Inject;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The project front desks (qits-767): demand, placement, the token, the estate, the inventory, the
 * doors. Runners are scripted {@link FakeDeskRunner}s on the real socket, so it runs under the
 * machine gate ({@link MockIdpTenant}) exactly as {@link DeskRunnerSocketTest} does — the same
 * resource set, so the same application — and the doors are called with the forwarded headers a
 * person's session arrives with.
 */
@QuarkusTest
@WithTestResource(DeskRunnerMockIdpTenant.class)
class FrontDeskTest {

  private static final String DOMAIN = DeskRunnerAddressesFixture.DOMAIN;

  @TestHTTPResource(DeskRunnerProtocol.SOCKET_PATH)
  URI endpoint;

  @Inject Vertx vertx;

  @Inject FrontDesks frontDesks;

  @Inject FrontDeskSweep sweep;

  @Inject FrontDeskSpecRoll specRoll;

  @Inject FrontDeskTokenReconcile tokenReconcile;

  @Inject FrontDeskDemand demand;

  @Inject FrontDeskHooks hooks;

  @Inject FrontDeskRunnerDesks runnerDesks;

  @Inject FrontDeskSpecs specs;

  @Inject FakeFrontDeskTokens tokens;

  @Inject DeskRunnerRegistry registry;

  @Inject DeskRunners runners;

  @Inject DeskRunnerRepository runnerRows;

  @Inject FrontDeskRepository desks;

  @Inject ProjectRepository projectRows;

  @Inject ProjectService projectService;

  private final List<FakeDeskRunner> dialled = new ArrayList<>();

  private final List<UUID> createdRunners = new ArrayList<>();

  private final List<String> createdProjects = new ArrayList<>();

  @BeforeEach
  void arrange() {
    QuarkusMock.installMockForType(
        DeskRunnerAddressesFixture.withDomain(DOMAIN), DeskRunnerAddresses.class);
    specs.domainOverride(DOMAIN);
    tokens.reset();
    demand.forgetEvidence();
  }

  @AfterEach
  void tidy() {
    dialled.forEach(FakeDeskRunner::close);
    dialled.clear();
    registry.reconnectGrace(null);
    specs.domainOverride(null);
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              createdProjects.forEach(desks::deleteById);
              createdProjects.forEach(projectRows::deleteById);
              createdRunners.forEach(runnerRows::deleteById);
            });
    createdProjects.clear();
    createdRunners.clear();
  }

  // --- fixtures ----------------------------------------------------------------------------------

  private String project(FrontDeskLifecycle lifecycle) {
    String id = UUID.randomUUID().toString();
    String slug = "fd-" + id.substring(0, 8);
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Project project = new Project();
              project.id = id;
              project.name = "Front desk " + slug;
              project.slug = slug;
              project.frontDeskLifecycle = lifecycle;
              projectRows.persist(project);
            });
    createdProjects.add(id);
    return id;
  }

  private void setLifecycle(String projectId, FrontDeskLifecycle lifecycle) {
    QuarkusTransaction.requiringNew()
        .run(() -> projectRows.findById(projectId).frontDeskLifecycle = lifecycle);
  }

  private FrontDesk row(String projectId) {
    return QuarkusTransaction.requiringNew().call(() -> desks.findById(projectId));
  }

  /** A registered runner in service, holding {@code slots}. */
  private DeskRunner runner(String clientId, int slots) {
    UUID id = UUID.randomUUID();
    runners.create(id, "fd-" + id.toString().substring(0, 8), null, slots, "tok-" + id, "s-" + id);
    createdRunners.add(id);
    runners.markRegistered(id, clientId, null);
    return runners.greenlight(id);
  }

  /** Dial as {@code clientId}, say hello at the pin, read the ack and the greeting's estate. */
  private FakeDeskRunner greeted(String clientId) throws Exception {
    String bearer =
        MockIdp.attach()
            .token()
            .subject(clientId)
            .audience("qits-platform")
            .groups(DeskRunnerSocket.RUNNER_ROLE)
            .mint();
    FakeDeskRunner runner = FakeDeskRunner.connect(vertx, endpoint, bearer);
    dialled.add(runner);
    runner.send(FakeDeskRunner.hello(DeskRunnerBinary.VERSION, List.of()));
    runner.expect(Ack.class);
    runner.await(Estate.class);
    return runner;
  }

  private static RequestSpecification person() {
    return given().header("X-Qits-User", "someone").header("X-Qits-Roles", "qits:admin");
  }

  private static String base(String projectId) {
    return "/projects/api/projects/" + projectId + "/agent-container";
  }

  private static void await(BooleanSupplier condition, String what) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
      Thread.sleep(50);
    }
    assertTrue(condition.getAsBoolean(), what);
  }

  private AgentRuntimeStatus status(String projectId) {
    return frontDesks.status(projectId).runtimeStatus();
  }

  // --- demand ------------------------------------------------------------------------------------

  /** An ALWAYS_ON project gets its desk from the sweep, with no ensure: wanted, tokened, QUEUED. */
  @Test
  void anAlwaysOnDeskExistsWithoutAnEnsure() {
    String projectId = project(FrontDeskLifecycle.ALWAYS_ON);

    sweep.sweep(Instant.now());

    FrontDesk row = row(projectId);
    assertNotNull(row);
    assertEquals(FrontDeskDesired.RUNNING, row.desired);
    assertNotNull(row.tokenId);
    assertTrue(row.tokenSubject.startsWith("tok-"));
    assertNotNull(row.queuedAt);
    assertEquals(AgentRuntimeStatus.QUEUED, status(projectId));
    assertEquals(1, tokens.mintsFor(projectId));
  }

  /** A flip to ALWAYS_ON creates the desk at once, through the project.yml hook. */
  @Test
  void aLifecycleFlipCreatesTheDesk() {
    String projectId = project(FrontDeskLifecycle.ON_DEMAND);
    setLifecycle(projectId, FrontDeskLifecycle.ALWAYS_ON);

    hooks.onChanged(projectId, FrontDeskLifecycle.ALWAYS_ON);

    assertEquals(FrontDeskDesired.RUNNING, row(projectId).desired);
    assertEquals(AgentRuntimeStatus.QUEUED, status(projectId));
  }

  /** ON_DEMAND: ensure wants it; the idle window closing stops it; stop stops it at once. */
  @Test
  void anOnDemandDeskIsWantedForTheIdleWindowAndUntilStopped() {
    String projectId = project(FrontDeskLifecycle.ON_DEMAND);

    person()
        .post(base(projectId) + "/ensure")
        .then()
        .statusCode(200)
        .body("container.runtimeStatus", is("QUEUED"))
        .body("container.lifecycle", is("ON_DEMAND"))
        .body("container.queuedAt", notNullValue())
        .body("container.runnerId", nullValue());
    assertEquals(FrontDeskDesired.RUNNING, row(projectId).desired);

    // Five hours later the PT4H window has closed.
    assertNull(frontDesks.reconcile(projectId, Instant.now().plus(Duration.ofHours(5))));
    assertEquals(FrontDeskDesired.STOPPED, row(projectId).desired);
    assertNull(row(projectId).queuedAt);

    person().post(base(projectId) + "/ensure").then().statusCode(200);
    person()
        .post(base(projectId) + "/stop")
        .then()
        .statusCode(200)
        .body("container.runtimeStatus", is("STOPPED"));
    assertNull(row(projectId).lastDemandAt);
    assertEquals(FrontDeskDesired.STOPPED, row(projectId).desired);
    assertTrue(tokens.revoked().isEmpty(), "a stop never revokes the token");
  }

  /** The daemon's evidence of use is demand, at most once a minute, and only on a wanted desk. */
  @Test
  void theDaemonsEvidenceOfUseIsThrottledDemand() throws Exception {
    String projectId = project(FrontDeskLifecycle.ON_DEMAND);
    person().post(base(projectId) + "/ensure").then().statusCode(200);
    Instant stamped = row(projectId).lastDemandAt;

    Instant later = Instant.now().plusSeconds(1).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    demand.evidenced(projectId, later);
    await(() -> later.equals(row(projectId).lastDemandAt), "evidence did not stamp demand");

    demand.evidenced(projectId, later.plusSeconds(30));
    Thread.sleep(300);
    assertEquals(later, row(projectId).lastDemandAt, "a second stamp inside the minute");
    assertTrue(stamped.isBefore(later));

    person().post(base(projectId) + "/stop").then().statusCode(200);
    demand.evidenced(projectId, later.plusSeconds(120));
    Thread.sleep(300);
    assertNull(row(projectId).lastDemandAt, "a stopped desk was woken by its daemon's goodbye");
  }

  // --- placement ---------------------------------------------------------------------------------

  /** Two runners reserve at once for one queued desk: exactly one takes it. */
  @Test
  void theCompareAndSwapPlacesADeskOnce() throws Exception {
    String projectId = project(FrontDeskLifecycle.ALWAYS_ON);
    sweep.sweep(Instant.now());
    DeskRunner a = runner("fd-cas-a", 4);
    DeskRunner b = runner("fd-cas-b", 4);
    CountDownLatch start = new CountDownLatch(1);
    CompletableFuture<Take> takeA =
        CompletableFuture.supplyAsync(() -> awaitThen(start, () -> frontDesks.take(a.id)));
    CompletableFuture<Take> takeB =
        CompletableFuture.supplyAsync(() -> awaitThen(start, () -> frontDesks.take(b.id)));

    start.countDown();
    Take first = takeA.get(10, TimeUnit.SECONDS);
    Take second = takeB.get(10, TimeUnit.SECONDS);

    assertTrue((first == null) != (second == null), "one take, exactly: " + first + " / " + second);
    Take won = first != null ? first : second;
    assertEquals(projectId, won.projectId());
    assertEquals(DesiredState.RUNNING, won.desired());
    FrontDesk row = row(projectId);
    assertEquals(first != null ? a.id : b.id, row.runnerId);
    assertEquals(won.specHash(), row.specHash);
    assertNotNull(row.placedAt);
    assertEquals(AgentRuntimeStatus.PROVISIONING, status(projectId), "inside the boot's grace");
    registry.reconnectGrace(Duration.ofMillis(1));
    assertEquals(AgentRuntimeStatus.UNAVAILABLE, status(projectId), "its runner never connected");
  }

  private static <T> T awaitThen(CountDownLatch latch, java.util.function.Supplier<T> action) {
    try {
      latch.await(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    return action.get();
  }

  /**
   * Over the socket: a reserve takes the queued desk; the server's slot count then answers the next
   * reserve nothing; an owned desk desired RUNNING again is started whatever the count — its estate
   * says so.
   */
  @Test
  void slotsGateOnlyNewPlacement() throws Exception {
    DeskRunner one = runner("fd-slots", 1);
    FakeDeskRunner runner = greeted("fd-slots");
    String first = project(FrontDeskLifecycle.ON_DEMAND);
    String second = project(FrontDeskLifecycle.ON_DEMAND);
    person().post(base(first) + "/ensure").then().statusCode(200);

    runner.send(new Reserve());
    Take take = runner.expect(Take.class);
    assertEquals(first, take.projectId());
    assertEquals(
        "registry.qits." + DOMAIN + "/qits/project-agent:" + specs.imageVersion(),
        take.spec().image());
    assertEquals("PROVISIONING", person().get(base(first)).then().extract().path("container.runtimeStatus"));
    person()
        .get(base(first))
        .then()
        .body("container.runnerId", is(one.id.toString()))
        .body("container.runnerName", is(one.name));

    person().post(base(second) + "/ensure").then().statusCode(200);
    runner.send(new Reserve());
    runner.expect(Nothing.class);
    assertEquals(AgentRuntimeStatus.QUEUED, status(second));

    // The owned desk is stopped, freeing the slot by the server's count…
    person().post(base(first) + "/stop").then().statusCode(200);
    Estate stopped = runner.expect(Estate.class);
    assertEquals(DesiredState.STOPPED, stopped.desks().get(0).desired());
    // …and wanted again while the second takes the slot: it is started all the same.
    runner.send(new Reserve());
    assertEquals(second, runner.expect(Take.class).projectId());
    person().post(base(first) + "/ensure").then().statusCode(200);
    Estate again = runner.expect(Estate.class);
    assertEquals(2, again.desks().size());
    assertTrue(again.desks().stream().allMatch(d -> d.desired() == DesiredState.RUNNING));
  }

  // --- estate and inventory ----------------------------------------------------------------------

  /** The inventory lands on the row and the status follows it; launchFailed reads FAILED. */
  @Test
  void theInventoryDecidesTheStatus() throws Exception {
    DeskRunner held = runner("fd-inventory", 2);
    FakeDeskRunner runner = greeted("fd-inventory");
    String projectId = project(FrontDeskLifecycle.ON_DEMAND);
    person().post(base(projectId) + "/ensure").then().statusCode(200);
    runner.send(new Reserve());
    Take take = runner.expect(Take.class);

    runner.send(
        new Inventory(
            List.of(new HeldDesk(projectId, DeskState.RUNNING, take.specHash(), null))));
    await(() -> status(projectId) == AgentRuntimeStatus.RUNNING, "inventory RUNNING not RUNNING");
    assertEquals(take.specHash(), row(projectId).reportedSpecHash);

    runner.send(new LaunchFailed(projectId, "docker: image not found"));
    await(() -> status(projectId) == AgentRuntimeStatus.FAILED, "launchFailed not FAILED");
    assertEquals("docker: image not found", frontDesks.status(projectId).failureDetail());

    runner.send(
        new Inventory(
            List.of(new HeldDesk(projectId, DeskState.RUNNING, take.specHash(), null))));
    await(() -> status(projectId) == AgentRuntimeStatus.RUNNING, "a running desk stays FAILED");

    runner.send(new Inventory(List.of()));
    await(() -> "ABSENT".equals(row(projectId).reportedState), "unnamed desk not ABSENT");
    assertEquals(AgentRuntimeStatus.PROVISIONING, status(projectId));
    assertEquals(held.id, row(projectId).runnerId);
  }

  /** Past the reconnect grace a gone runner's desks read UNAVAILABLE, and RUNNING when it is back. */
  @Test
  void aGoneRunnersDesksReadUnavailableAfterTheGrace() throws Exception {
    registry.reconnectGrace(Duration.ofSeconds(1));
    runner("fd-gone", 1);
    FakeDeskRunner runner = greeted("fd-gone");
    String projectId = project(FrontDeskLifecycle.ON_DEMAND);
    person().post(base(projectId) + "/ensure").then().statusCode(200);
    runner.send(new Reserve());
    Take take = runner.expect(Take.class);
    runner.send(
        new Inventory(
            List.of(new HeldDesk(projectId, DeskState.RUNNING, take.specHash(), null))));
    await(() -> status(projectId) == AgentRuntimeStatus.RUNNING, "not RUNNING");

    runner.close();
    assertEquals(AgentRuntimeStatus.RUNNING, status(projectId), "inside the grace");
    await(() -> status(projectId) == AgentRuntimeStatus.UNAVAILABLE, "not UNAVAILABLE");
    person().get(base(projectId)).then().body("container.runtimeStatus", is("UNAVAILABLE"));
    assertEquals("RUNNING", row(projectId).reportedState, "UNAVAILABLE is never stored");

    FakeDeskRunner back = greeted("fd-gone");
    assertNotNull(back);
    assertEquals(AgentRuntimeStatus.RUNNING, status(projectId));
  }

  /** A greeting carries the estate of the runner's desks; a held desk on it is adopted. */
  @Test
  void aGreetingCarriesTheEstateAndAdoptsHeldDesks() throws Exception {
    DeskRunner held = runner("fd-greet", 2);
    String projectId = project(FrontDeskLifecycle.ALWAYS_ON);
    sweep.sweep(Instant.now());
    assertNotNull(frontDesks.take(held.id));

    String bearer =
        MockIdp.attach()
            .token()
            .subject("fd-greet")
            .audience("qits-platform")
            .groups(DeskRunnerSocket.RUNNER_ROLE)
            .mint();
    FakeDeskRunner runner = FakeDeskRunner.connect(vertx, endpoint, bearer);
    dialled.add(runner);
    runner.send(FakeDeskRunner.hello(DeskRunnerBinary.VERSION, List.of(projectId, "stranger")));
    assertEquals(List.of(projectId), runner.expect(Ack.class).adopted());
    Estate estate = runner.expect(Estate.class);
    assertEquals(1, estate.desks().size());
    assertEquals(projectId, estate.desks().get(0).projectId());
    assertEquals(row(projectId).specHash, estate.desks().get(0).specHash());
    assertEquals("qits_tok_" + row(projectId).tokenId, estate.desks().get(0).spec().env().get("QITS_TOKEN"));
    assertEquals(
        "registry.qits." + DOMAIN + "/qits/project-agent:" + specs.imageVersion(),
        estate.deskImage());
  }

  // --- the spec roll -----------------------------------------------------------------------------

  /** A new spec is applied to a desk not running at once; a running busy desk waits until quiet. */
  @Test
  void aNewSpecRollsOnlyWhenNotRunningOrQuiet() throws Exception {
    runner("fd-roll", 2);
    FakeDeskRunner runner = greeted("fd-roll");
    String projectId = project(FrontDeskLifecycle.ON_DEMAND);
    person().post(base(projectId) + "/ensure").then().statusCode(200);
    runner.send(new Reserve());
    Take take = runner.expect(Take.class);
    runner.send(
        new Inventory(
            List.of(new HeldDesk(projectId, DeskState.RUNNING, take.specHash(), null))));
    await(() -> status(projectId) == AgentRuntimeStatus.RUNNING, "not RUNNING");

    // The lifecycle is in the spec, so a flip is a new spec. Busy a moment ago: it waits.
    setLifecycle(projectId, FrontDeskLifecycle.ALWAYS_ON);
    assertEquals(0, rollWhileBusy(projectId));
    assertEquals(take.specHash(), row(projectId).specHash);

    // Reported stopped: it rolls, and the runner is sent the new spec.
    runner.send(
        new Inventory(
            List.of(new HeldDesk(projectId, DeskState.STOPPED, take.specHash(), null))));
    await(() -> "STOPPED".equals(row(projectId).reportedState), "not STOPPED");
    assertEquals(1, specRoll.roll(Instant.now()));
    Estate rolled = runner.await(Estate.class);
    assertFalse(take.specHash().equals(rolled.desks().get(0).specHash()));
    assertEquals("ALWAYS_ON", rolled.desks().get(0).spec().env().get("QITS_PROJECTS_DAEMON_LIFECYCLE"));
    assertEquals(rolled.desks().get(0).specHash(), row(projectId).specHash);
  }

  /** A roll while the desk's agent was evidenced in use just now. */
  private int rollWhileBusy(String projectId) {
    eu.wohlben.qits.projects.agenthost.AgentDaemonRegistry daemons =
        io.quarkus.arc.Arc.container()
            .instance(eu.wohlben.qits.projects.agenthost.AgentDaemonRegistry.class)
            .get();
    daemons.touchAgentActivity(projectId, Instant.now());
    try {
      assertFalse(specRoll.quiet(projectId, Instant.now()));
      return specRoll.roll(Instant.now());
    } finally {
      daemons.forget(projectId);
    }
  }

  // --- the token ---------------------------------------------------------------------------------

  /** Minted once at creation; kept by stop; revoked by DELETE, which also tells the runner. */
  @Test
  void theTokenLivesAsLongAsTheDesk() throws Exception {
    DeskRunner held = runner("fd-token", 2);
    FakeDeskRunner runner = greeted("fd-token");
    String projectId = project(FrontDeskLifecycle.ON_DEMAND);
    person().post(base(projectId) + "/ensure").then().statusCode(200);
    person().post(base(projectId) + "/ensure").then().statusCode(200);
    assertEquals(1, tokens.mintsFor(projectId));
    String tokenId = row(projectId).tokenId;
    String subject = row(projectId).tokenSubject;
    assertEquals(subject, frontDesks.tokenSubject(projectId));
    runner.send(new Reserve());
    runner.expect(Take.class);

    person().post(base(projectId) + "/stop").then().statusCode(200);
    runner.expect(Estate.class);
    assertTrue(tokens.isLive(tokenId), "stop revoked the token");

    person().delete(base(projectId)).then().statusCode(204);

    assertEquals(projectId, runner.expect(Remove.class).projectId());
    assertTrue(runner.expect(Estate.class).desks().isEmpty());
    assertNull(row(projectId));
    assertFalse(tokens.isLive(tokenId));
    assertNull(frontDesks.tokenSubject(projectId));
    person().get(base(projectId)).then().body("container.runtimeStatus", is("ABSENT"));
    person().delete(base(projectId)).then().statusCode(204);
    assertEquals(held.id, runnerRows.findByClientId("fd-token").orElseThrow().id);
  }

  /** A wanted desk a DELETE removed comes back fresh, with a new token, at the next sweep. */
  @Test
  void aDeletedAlwaysOnDeskComesBackFresh() {
    String projectId = project(FrontDeskLifecycle.ALWAYS_ON);
    sweep.sweep(Instant.now());
    String first = row(projectId).tokenId;

    person().delete(base(projectId)).then().statusCode(204);
    sweep.sweep(Instant.now());

    assertNotNull(row(projectId));
    assertFalse(first.equals(row(projectId).tokenId));
    assertEquals(AgentRuntimeStatus.QUEUED, status(projectId));
  }

  /** Deleting the project takes its desk: the runner is told, the token revoked, the row gone. */
  @Test
  void deletingTheProjectRemovesItsDesk() throws Exception {
    runner("fd-project-gone", 2);
    FakeDeskRunner runner = greeted("fd-project-gone");
    String projectId = project(FrontDeskLifecycle.ON_DEMAND);
    person().post(base(projectId) + "/ensure").then().statusCode(200);
    runner.send(new Reserve());
    runner.expect(Take.class);
    String tokenId = row(projectId).tokenId;

    projectService.delete(projectId);

    assertEquals(projectId, runner.await(Remove.class).projectId());
    assertNull(row(projectId));
    assertFalse(tokens.isLive(tokenId));
  }

  /** A mint that fails leaves the desk FAILED and unqueued; the sweep mints it later. */
  @Test
  void aFailedMintIsRetriedBySweep() {
    String projectId = project(FrontDeskLifecycle.ON_DEMAND);
    tokens.failMint(true);

    person()
        .post(base(projectId) + "/ensure")
        .then()
        .statusCode(200)
        .body("container.runtimeStatus", is("FAILED"));
    assertTrue(row(projectId).failureDetail.startsWith(FrontDesks.TOKEN_UNAVAILABLE));
    assertNull(row(projectId).queuedAt);

    tokens.failMint(false);
    sweep.sweep(Instant.now());

    assertNotNull(row(projectId).tokenId);
    assertNull(row(projectId).failureDetail);
    assertEquals(AgentRuntimeStatus.QUEUED, status(projectId));
  }

  /** The reconcile reaps unclaimed desk tokens, spares claimed and young ones, and other kinds. */
  @Test
  void theTokenReconcileReapsOnlyTheUnclaimed() {
    String projectId = project(FrontDeskLifecycle.ALWAYS_ON);
    sweep.sweep(Instant.now());
    String claimed = row(projectId).tokenId;
    Instant old = Instant.now().minus(Duration.ofHours(2));
    tokens.plant(new FrontDeskTokens.Live("orphan", FrontDeskTokens.CONTEXT_KIND, "gone", old));
    tokens.plant(
        new FrontDeskTokens.Live("young", FrontDeskTokens.CONTEXT_KIND, "gone", Instant.now()));
    tokens.plant(new FrontDeskTokens.Live("runner", "desk-runner-registration", "r", old));

    tokens.failList(true);
    assertEquals(0, tokenReconcile.reconcile(Instant.now()));
    assertTrue(tokens.isLive("orphan"), "a failed listing reaped");
    tokens.failList(false);

    assertEquals(1, tokenReconcile.reconcile(Instant.now()));
    assertFalse(tokens.isLive("orphan"));
    assertTrue(tokens.isLive(claimed));
    assertTrue(tokens.isLive("young"));
    assertTrue(tokens.isLive("runner"));
  }

  /** {@code removed} for an unplaced, once-placed row completes it: row dropped, token revoked. */
  @Test
  void removedCompletesAnUnplacedRow() {
    DeskRunner held = runner("fd-removed", 1);
    String projectId = project(FrontDeskLifecycle.ON_DEMAND);
    frontDesks.ensureRow(projectId);
    String tokenId = row(projectId).tokenId;

    frontDesks.removed(held.id, projectId);
    assertNotNull(row(projectId), "a never-placed row is not touched");

    QuarkusTransaction.requiringNew()
        .run(() -> desks.update("placedAt = ?1 where projectId = ?2", Instant.now(), projectId));
    frontDesks.removed(held.id, projectId);
    assertNull(row(projectId));
    assertFalse(tokens.isLive(tokenId));
  }

  // --- refusals ----------------------------------------------------------------------------------

  /** An ALWAYS_ON desk cannot be stopped: 409 FRONT_DESK_ALWAYS_ON, and nothing moves. */
  @Test
  void stoppingAnAlwaysOnDeskIs409() {
    String projectId = project(FrontDeskLifecycle.ALWAYS_ON);
    sweep.sweep(Instant.now());

    person()
        .post(base(projectId) + "/stop")
        .then()
        .statusCode(409)
        .body("error", is(FrontDesks.FRONT_DESK_ALWAYS_ON));
    assertEquals(FrontDeskDesired.RUNNING, row(projectId).desired);
  }

  /** The runner listing names its desks, which is what refuses its delete RUNNER_OWNS_DESKS. */
  @Test
  void aRunnerOwningADeskListsIt() {
    DeskRunner held = runner("fd-owns", 1);
    String projectId = project(FrontDeskLifecycle.ALWAYS_ON);
    sweep.sweep(Instant.now());
    assertNotNull(frontDesks.take(held.id));

    List<DeskRunnerDto.DeskRunnerDesk> owned =
        QuarkusTransaction.requiringNew().call(() -> runnerDesks.desksOn(held.id));

    assertEquals(1, owned.size());
    assertEquals(projectId, owned.get(0).projectId());
    assertEquals("fd-" + projectId.substring(0, 8), owned.get(0).slug());
    assertNotNull(owned.get(0).state());
  }

  /** An unknown project is a 404 on every door. */
  @Test
  void anUnknownProjectIs404OnEveryDoor() {
    String unknown = base(UUID.randomUUID().toString());
    person().get(unknown).then().statusCode(404);
    person().post(unknown + "/ensure").then().statusCode(404);
    person().post(unknown + "/stop").then().statusCode(404);
    person().delete(unknown).then().statusCode(404);
  }

  /** With no public domain the desk is FAILED EDGE_PLANE_UNCONFIGURED, and is not placed. */
  @Test
  void anUnconfiguredEdgeIsFailed() {
    DeskRunner held = runner("fd-edge", 1);
    specs.domainOverride("localhost");
    String projectId = project(FrontDeskLifecycle.ON_DEMAND);

    person()
        .post(base(projectId) + "/ensure")
        .then()
        .statusCode(200)
        .body("container.runtimeStatus", is("FAILED"))
        .body("container.failureDetail", is(FrontDeskSpecs.EDGE_PLANE_UNCONFIGURED));
    assertNull(frontDesks.take(held.id));

    specs.domainOverride(DOMAIN);
    assertNotNull(frontDesks.take(held.id));
    assertNull(row(projectId).failureDetail);
  }

  /** The bound subject is what the socket checks read. */
  @Test
  void theBoundSubjectIsTheDesksTokenSubject() {
    String projectId = project(FrontDeskLifecycle.ALWAYS_ON);
    sweep.sweep(Instant.now());
    assertEquals(row(projectId).tokenSubject, frontDesks.tokenSubject(projectId));
    assertNull(frontDesks.tokenSubject(UUID.randomUUID().toString()));
  }
}
