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

import eu.wohlben.qits.projects.agenthost.AgentDaemonRegistry;
import eu.wohlben.qits.projects.agenthost.AgentRuntimeStatus;
import eu.wohlben.qits.projects.control.ProjectConfig;
import eu.wohlben.qits.projects.control.ProjectService;
import eu.wohlben.qits.projects.entity.FrontDesk;
import eu.wohlben.qits.projects.entity.FrontDeskDesired;
import eu.wohlben.qits.projects.entity.FrontDeskLifecycle;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.persistence.FrontDeskRepository;
import eu.wohlben.qits.projects.persistence.ProjectRepository;
import eu.wohlben.qits.projects.targethost.DeskLeases;
import eu.wohlben.qits.projects.targethost.FakeSystemLeases;
import eu.wohlben.qits.projects.targethost.SystemLeases;
import eu.wohlben.qits.projectsdaemon.protocol.AgentActivity;
import eu.wohlben.qits.projectsdaemon.protocol.DaemonProtocol.AgentState;
import io.quarkus.arc.Arc;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The project front desks (qits-767), run as DESK leases on execution targets (epic qits-1481,
 * qits-1551): demand, the lease a wanted desk asks for and an unwanted one ends, the per-lease token,
 * the roll, the removal, the doors. qits-system is {@link FakeSystemLeases}; the lease's own
 * mapping and the reconcile are {@code targethost/DeskLeasesTest}'s.
 */
@QuarkusTest
class FrontDeskTest {

  private static final String DOMAIN = "suite.example";

  @Inject FrontDesks frontDesks;

  @Inject FrontDeskSweep sweep;

  @Inject FrontDeskSpecRoll specRoll;

  @Inject FrontDeskTokenReconcile tokenReconcile;

  @Inject FrontDeskDemand demand;

  @Inject FrontDeskHooks hooks;

  @Inject FrontDeskSpecs specs;

  @Inject FakeFrontDeskTokens tokens;

  @Inject FakeSystemLeases system;

  @Inject DeskLeases leases;

  @Inject FrontDeskRepository desks;

  @Inject ProjectRepository projectRows;

  @Inject ProjectService projectService;

  private final List<String> createdProjects = new ArrayList<>();

  @BeforeEach
  void arrange() {
    specs.domainOverride(DOMAIN);
    tokens.reset();
    system.reset();
    demand.forgetEvidence();
  }

  @AfterEach
  void tidy() {
    specs.domainOverride(null);
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              createdProjects.forEach(desks::deleteById);
              createdProjects.forEach(projectRows::deleteById);
            });
    createdProjects.forEach(id -> daemons().forget(id));
    createdProjects.clear();
    system.reset();
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

  private void setDelivery(String projectId, boolean enabled) {
    QuarkusTransaction.requiringNew()
        .run(() -> projectRows.findById(projectId).deliveryEnabled = enabled);
  }

  private static ProjectConfig.DeliverySettings delivery(boolean enabled) {
    return new ProjectConfig.DeliverySettings(enabled, 2, false, java.util.Set.of());
  }

  private FrontDesk row(String projectId) {
    return QuarkusTransaction.requiringNew().call(() -> desks.findById(projectId));
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

  private static AgentDaemonRegistry daemons() {
    return Arc.container().instance(AgentDaemonRegistry.class).get();
  }

  /** The desk's lease, as qits-system reports it RUNNING on a target. */
  private UUID running(String projectId) {
    UUID leaseId = row(projectId).leaseId;
    assertNotNull(leaseId, "the desk holds a lease");
    leases.observe(
        system.move(leaseId, SystemLeases.LeaseState.RUNNING, UUID.randomUUID(), null));
    assertEquals(AgentRuntimeStatus.RUNNING, status(projectId));
    return leaseId;
  }

  private static String env(FrontDesk row, String name) {
    return FrontDeskSpecs.parse(row.specJson).env().get(name);
  }

  // --- demand ------------------------------------------------------------------------------------

  /**
   * An ALWAYS_ON project gets its desk from the sweep, with no ensure: wanted, and its DESK lease
   * asked for with a token minted for it — QUEUED, saying why it waits.
   */
  @Test
  void anAlwaysOnDeskIsLeasedWithoutAnEnsure() {
    String projectId = project(FrontDeskLifecycle.ALWAYS_ON);

    sweep.sweep(Instant.now());

    FrontDesk row = row(projectId);
    assertNotNull(row);
    assertEquals(FrontDeskDesired.RUNNING, row.desired);
    assertNotNull(row.leaseId);
    assertEquals("QUEUED", row.reportedState);
    assertTrue(row.tokenSubject.startsWith("tok-"));
    assertNotNull(row.queuedAt);
    FrontDeskState state = frontDesks.status(projectId);
    assertEquals(AgentRuntimeStatus.QUEUED, state.runtimeStatus());
    assertEquals("no target has a free DESK slot", state.failureDetail(), "the lease's reason");
    assertEquals(1, tokens.mintsFor(projectId));
    assertEquals(1, tokens.sessionMintsFor(projectId));

    SystemLeases.LeaseRequest asked = system.requestOf(row.leaseId);
    assertEquals(projectId, asked.projectId());
    assertEquals(row.tokenValue, asked.spec().env().get("QITS_TOKEN"));
    assertEquals(row.tokenSubject, asked.spec().env().get("QITS_TOKEN_SUBJECT"));
    assertNull(asked.adopt());
    assertNull(asked.pinTargetId());
    for (String owned : FrontDeskSpecs.POLICY_OWNED_ENV) {
      assertFalse(asked.spec().env().containsKey(owned), owned + " is the slot policy's");
    }

    sweep.sweep(Instant.now());
    assertEquals(1, system.requests().size(), "a desk with a lease asks for no second one");
  }

  /** A flip to ALWAYS_ON creates the desk and leases it at once, through the project.yml hook. */
  @Test
  void aLifecycleFlipCreatesTheDesk() {
    String projectId = project(FrontDeskLifecycle.ON_DEMAND);
    setLifecycle(projectId, FrontDeskLifecycle.ALWAYS_ON);

    hooks.onChanged(projectId, FrontDeskLifecycle.ALWAYS_ON);

    assertEquals(FrontDeskDesired.RUNNING, row(projectId).desired);
    assertNotNull(row(projectId).leaseId);
    assertEquals(AgentRuntimeStatus.QUEUED, status(projectId));
  }

  /**
   * ON_DEMAND: ensure wants it and asks for its lease; the idle window closing ends the lease and
   * gives its token back; stop ends it at once. The work volume stays on the target.
   */
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
        .body("container.targetId", nullValue());
    UUID first = row(projectId).leaseId;
    String firstToken = row(projectId).tokenId;
    assertNotNull(first);

    // Five hours later the PT4H window has closed.
    frontDesks.reconcileAndApply(projectId, Instant.now().plus(Duration.ofHours(5)));
    FrontDesk stopped = row(projectId);
    assertEquals(FrontDeskDesired.STOPPED, stopped.desired);
    assertNull(stopped.leaseId, "the lease ended");
    assertNull(stopped.tokenId, "and its token went with it");
    assertFalse(tokens.isLive(firstToken));
    assertTrue(system.ended().contains(first));
    assertTrue(system.purged().isEmpty(), "a stop keeps the work volume");
    assertEquals(AgentRuntimeStatus.STOPPED, status(projectId));

    person().post(base(projectId) + "/ensure").then().statusCode(200);
    UUID second = row(projectId).leaseId;
    assertNotNull(second);
    assertFalse(first.equals(second));
    assertEquals(2, tokens.mintsFor(projectId), "one token per lease");

    person()
        .post(base(projectId) + "/stop")
        .then()
        .statusCode(200)
        .body("container.runtimeStatus", is("STOPPED"));
    assertNull(row(projectId).lastDemandAt);
    assertEquals(FrontDeskDesired.STOPPED, row(projectId).desired);
    assertTrue(system.ended().contains(second));
  }

  /** The daemon's evidence of use is demand, at most once a minute, and only on a wanted desk. */
  @Test
  void theDaemonsEvidenceOfUseIsThrottledDemand() throws Exception {
    String projectId = project(FrontDeskLifecycle.ON_DEMAND);
    person().post(base(projectId) + "/ensure").then().statusCode(200);
    Instant stamped = row(projectId).lastDemandAt;

    Instant later = Instant.now().plusSeconds(1).truncatedTo(ChronoUnit.MICROS);
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

  // --- the spec roll: PUT /system/api/leases/{id}/spec -------------------------------------------

  /** A new spec is rolled onto a lease that is not running at once; a running busy desk waits. */
  @Test
  void aNewSpecRollsOnlyWhenNotRunningOrQuiet() {
    String projectId = project(FrontDeskLifecycle.ON_DEMAND);
    person().post(base(projectId) + "/ensure").then().statusCode(200);
    UUID leaseId = running(projectId);
    String applied = row(projectId).specHash;

    // The lifecycle is in the spec, so a flip is a new spec. Busy a moment ago: it waits.
    setLifecycle(projectId, FrontDeskLifecycle.ALWAYS_ON);
    daemons().touchAgentActivity(projectId, Instant.now());
    assertFalse(specRoll.quiet(projectId, Instant.now()));
    assertEquals(0, specRoll.roll(Instant.now()));
    assertEquals(applied, row(projectId).specHash);
    assertTrue(system.rolled().isEmpty());
    daemons().forget(projectId);

    // The lease is not running any more: it rolls, as a PUT of the lease's spec.
    leases.observe(system.move(leaseId, SystemLeases.LeaseState.GRANTED, UUID.randomUUID(), null));
    assertEquals(1, specRoll.roll(Instant.now()));
    assertEquals(List.of(leaseId), system.rolled());
    FrontDesk row = row(projectId);
    assertFalse(applied.equals(row.specHash));
    assertEquals("ALWAYS_ON", env(row, "QITS_PROJECTS_DAEMON_LIFECYCLE"));
    assertEquals(leaseId, row.leaseId, "a roll keeps the lease");
  }

  /** The desk's agent was in use a minute ago and its rollup now says {@code state}. */
  private void usedRecentlyAndNow(String projectId, String state) {
    daemons()
        .onMessage(
            projectId, null, new AgentActivity("cmd-1", "session-1", state, "Stop", null, null, 0L));
    daemons().touchAgentActivity(projectId, Instant.now().minus(Duration.ofMinutes(1)));
    assertFalse(specRoll.quiet(projectId, Instant.now()), "inside the quiet window");
  }

  /** A delivery flip on a desk used a minute ago but idle now rolls its lease at once. */
  @Test
  void aDeliveryFlipRollsARecentlyUsedIdleDeskAtOnce() {
    String projectId = project(FrontDeskLifecycle.ON_DEMAND);
    person().post(base(projectId) + "/ensure").then().statusCode(200);
    UUID leaseId = running(projectId);
    String applied = row(projectId).specHash;
    usedRecentlyAndNow(projectId, AgentState.IDLE);
    setDelivery(projectId, true);

    hooks.onDeliveryChanged(projectId, delivery(true));

    FrontDesk row = row(projectId);
    assertFalse(applied.equals(row.specHash), "rolled");
    assertEquals(List.of(leaseId), system.rolled());
    assertEquals("on", env(row, "QITS_PROJECTS_DAEMON_DELIVERY"));
    assertNotNull(row.deliveryTokenId, "the loop's token is minted");
    assertNull(env(row, "QITS_DELIVERY_TOKEN"), "it rides the control socket, never the env");
    assertNull(row.rollRequestedAt);
  }

  /** Busy when the flip lands, it waits for the turn — not for the quiet window. */
  @Test
  void aDeliveryFlipOnABusyDeskRollsWhenTheTurnEnds() {
    String projectId = project(FrontDeskLifecycle.ON_DEMAND);
    person().post(base(projectId) + "/ensure").then().statusCode(200);
    running(projectId);
    String applied = row(projectId).specHash;
    usedRecentlyAndNow(projectId, AgentState.BUSY);
    setDelivery(projectId, true);

    hooks.onDeliveryChanged(projectId, delivery(true));

    assertEquals(applied, row(projectId).specHash, "a busy desk is not rolled");
    assertNotNull(row(projectId).rollRequestedAt, "the request waits");

    usedRecentlyAndNow(projectId, AgentState.IDLE);
    assertEquals(1, specRoll.roll(Instant.now()));
    assertEquals("on", env(row(projectId), "QITS_PROJECTS_DAEMON_DELIVERY"));
    assertNull(row(projectId).rollRequestedAt);
  }

  /** A lifecycle flip with the rollup WAITING rolls at once: WAITING is not a running turn. */
  @Test
  void aLifecycleFlipOnAWaitingDeskRollsAtOnce() {
    String projectId = project(FrontDeskLifecycle.ON_DEMAND);
    person().post(base(projectId) + "/ensure").then().statusCode(200);
    running(projectId);
    String applied = row(projectId).specHash;
    usedRecentlyAndNow(projectId, AgentState.WAITING);
    setLifecycle(projectId, FrontDeskLifecycle.ALWAYS_ON);

    hooks.onChanged(projectId, FrontDeskLifecycle.ALWAYS_ON);

    FrontDesk row = row(projectId);
    assertFalse(applied.equals(row.specHash), "WAITING is not a running turn; it rolls at once");
    assertEquals("ALWAYS_ON", env(row, "QITS_PROJECTS_DAEMON_LIFECYCLE"));
    assertNull(row.rollRequestedAt);
  }

  /** A spec change nobody requested — a pin bump, say — still waits for quiet. */
  @Test
  void anUnrequestedSpecChangeStillWaitsForQuiet() {
    String projectId = project(FrontDeskLifecycle.ON_DEMAND);
    person().post(base(projectId) + "/ensure").then().statusCode(200);
    running(projectId);
    String applied = row(projectId).specHash;
    usedRecentlyAndNow(projectId, AgentState.IDLE);
    setDelivery(projectId, true);

    assertEquals(0, specRoll.roll(Instant.now()));
    assertEquals(0, specRoll.roll(projectId, Instant.now()));
    assertEquals(applied, row(projectId).specHash);
    assertTrue(system.rolled().isEmpty());
  }

  /** The hook fired with unchanged settings rolls nothing and leaves no request behind. */
  @Test
  void anUnchangedSettingsHookClearsTheRequestAndKeepsTheSpec() {
    String projectId = project(FrontDeskLifecycle.ON_DEMAND);
    person().post(base(projectId) + "/ensure").then().statusCode(200);
    running(projectId);
    String applied = row(projectId).specHash;
    usedRecentlyAndNow(projectId, AgentState.BUSY);

    hooks.onDeliveryChanged(projectId, delivery(false));
    hooks.onChanged(projectId, FrontDeskLifecycle.ON_DEMAND);

    assertEquals(applied, row(projectId).specHash);
    assertNull(row(projectId).rollRequestedAt);
    assertTrue(system.rolled().isEmpty());
  }

  /** The request is stored on the row, and a desk with no lease is never rolled for it. */
  @Test
  void aRollRequestPersistsOnTheRow() {
    String projectId = project(FrontDeskLifecycle.ON_DEMAND);
    frontDesks.ensureRow(projectId);
    Instant at = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    assertEquals(1, frontDesks.requestRoll(projectId, at));
    assertEquals(at, row(projectId).rollRequestedAt);
    assertEquals(0, specRoll.roll(projectId, Instant.now()), "a desk with no lease is not rolled");
    assertEquals(at, row(projectId).rollRequestedAt);

    assertEquals(1, frontDesks.clearRollRequest(projectId));
    assertNull(row(projectId).rollRequestedAt);
    assertEquals(0, frontDesks.requestRoll("no-such-project", at), "no desk, no row touched");
  }

  // --- removal -----------------------------------------------------------------------------------

  /**
   * DELETE ends the lease, purges the work volume once the lease is ENDED, revokes every token and
   * drops the row; it is idempotent.
   */
  @Test
  void deletingTheDeskEndsItsLeaseAndPurgesItsVolume() {
    String projectId = project(FrontDeskLifecycle.ON_DEMAND);
    person().post(base(projectId) + "/ensure").then().statusCode(200);
    UUID leaseId = running(projectId);
    String tokenId = row(projectId).tokenId;
    String sessionTokenId = row(projectId).sessionTokenId;
    assertEquals(row(projectId).tokenSubject, frontDesks.tokenSubject(projectId));

    person().delete(base(projectId)).then().statusCode(204);

    assertNull(row(projectId));
    assertTrue(system.ended().contains(leaseId));
    assertEquals(List.of(SystemLeases.workKey(projectId)), system.purged());
    assertFalse(tokens.isLive(tokenId));
    assertFalse(tokens.isLive(sessionTokenId));
    assertNull(frontDesks.tokenSubject(projectId));
    person().get(base(projectId)).then().body("container.runtimeStatus", is("ABSENT"));
    person().delete(base(projectId)).then().statusCode(204);
  }

  /** A wanted desk a DELETE removed comes back fresh, on a new lease and token, at the next sweep. */
  @Test
  void aDeletedAlwaysOnDeskComesBackFresh() {
    String projectId = project(FrontDeskLifecycle.ALWAYS_ON);
    sweep.sweep(Instant.now());
    String first = row(projectId).tokenId;
    UUID firstLease = row(projectId).leaseId;

    person().delete(base(projectId)).then().statusCode(204);
    sweep.sweep(Instant.now());

    assertNotNull(row(projectId));
    assertFalse(first.equals(row(projectId).tokenId));
    assertFalse(firstLease.equals(row(projectId).leaseId));
    assertEquals(AgentRuntimeStatus.QUEUED, status(projectId));
  }

  /** Deleting the project takes its desk: lease ended, volume purged, token revoked, row gone. */
  @Test
  void deletingTheProjectRemovesItsDesk() {
    String projectId = project(FrontDeskLifecycle.ON_DEMAND);
    person().post(base(projectId) + "/ensure").then().statusCode(200);
    UUID leaseId = row(projectId).leaseId;
    String tokenId = row(projectId).tokenId;

    projectService.delete(projectId);

    assertNull(row(projectId));
    assertTrue(system.ended().contains(leaseId));
    assertTrue(system.purged().contains(SystemLeases.workKey(projectId)));
    assertFalse(tokens.isLive(tokenId));
  }

  // --- tokens ------------------------------------------------------------------------------------

  /** A mint that fails leaves the desk FAILED and asks for no lease; the sweep mints it later. */
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
    assertNull(row(projectId).leaseId);
    assertTrue(system.requests().isEmpty(), "no lease without its token");

    tokens.failMint(false);
    sweep.sweep(Instant.now());

    assertNotNull(row(projectId).tokenId);
    assertNotNull(row(projectId).leaseId);
    assertNull(row(projectId).failureDetail);
    assertEquals(AgentRuntimeStatus.QUEUED, status(projectId));
  }

  /**
   * The reconcile reaps unclaimed desk tokens, spares claimed and young ones and other kinds — and a
   * desk token on a desk with no lease is no claim: it is cleared off the desk and revoked.
   */
  @Test
  void theTokenReconcileReapsTheUnclaimedAndTheLeaseless() {
    String projectId = project(FrontDeskLifecycle.ALWAYS_ON);
    sweep.sweep(Instant.now());
    String claimed = row(projectId).tokenId;
    Instant old = Instant.now().minus(Duration.ofHours(2));
    tokens.plant(new FrontDeskTokens.Live("orphan", FrontDeskTokens.CONTEXT_KIND, "gone", old));
    tokens.plant(
        new FrontDeskTokens.Live("young", FrontDeskTokens.CONTEXT_KIND, "gone", Instant.now()));
    tokens.plant(new FrontDeskTokens.Live("runner", "desk-runner-registration", "r", old));

    // A stopped desk still holding a token from before the cutover.
    String stoppedProject = project(FrontDeskLifecycle.ON_DEMAND);
    frontDesks.ensureRow(stoppedProject);
    tokens.plant(new FrontDeskTokens.Live("kept", FrontDeskTokens.CONTEXT_KIND, stoppedProject, old));
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                desks.update(
                    "tokenId = 'kept', tokenValue = 'qits_tok_kept', tokenSubject = 'tok-kept'"
                        + " where projectId = ?1",
                    stoppedProject));

    tokens.failList(true);
    assertEquals(0, tokenReconcile.reconcile(Instant.now()));
    assertTrue(tokens.isLive("orphan"), "a failed listing reaped");
    tokens.failList(false);

    assertEquals(2, tokenReconcile.reconcile(Instant.now()));
    assertFalse(tokens.isLive("orphan"));
    assertFalse(tokens.isLive("kept"));
    assertNull(row(stoppedProject).tokenId, "cleared off the lease-less desk");
    assertTrue(tokens.isLive(claimed), "a leased desk's token is claimed");
    assertTrue(tokens.isLive("young"));
    assertTrue(tokens.isLive("runner"), "a runner token is LegacyRunnerClients', not this reaper's");
  }

  // --- the delivery loop (epic qits-1440) ---------------------------------------------------------

  /** {@link DeliveryDemand} wants an ON_DEMAND desk while the loop is on, and only then. */
  @Test
  void theDeliveryLoopWantsAnOnDemandDesk() {
    DeliveryDemand source = new DeliveryDemand();
    assertEquals("delivery", source.name());
    Project project = new Project();
    project.frontDeskLifecycle = FrontDeskLifecycle.ON_DEMAND;
    assertFalse(source.wants(project, null, Instant.now()));
    project.deliveryEnabled = true;
    assertTrue(source.wants(project, null, Instant.now()));
    assertFalse(source.wants(null, null, Instant.now()));

    String projectId = project(FrontDeskLifecycle.ON_DEMAND);
    setDelivery(projectId, true);
    sweep.sweep(Instant.now());
    assertEquals(FrontDeskDesired.RUNNING, row(projectId).desired, "the sweep creates a wanted desk");
    assertEquals(AgentRuntimeStatus.QUEUED, status(projectId));
  }

  /**
   * Enabling the loop mints exactly one delivery token beside the lease's own, however often the
   * hook and the sweep run; disabling it revokes and clears it — and, nothing else wanting the desk,
   * ends its lease and gives the lease's token back too.
   */
  @Test
  void enablingMintsOneDeliveryTokenAndDisablingRevokesIt() {
    String projectId = project(FrontDeskLifecycle.ON_DEMAND);
    setDelivery(projectId, true);

    hooks.onDeliveryChanged(projectId, delivery(true));
    hooks.onDeliveryChanged(projectId, delivery(true));
    sweep.sweep(Instant.now());

    FrontDesk row = row(projectId);
    assertNotNull(row.deliveryTokenId);
    assertTrue(row.deliveryTokenSubject.startsWith("tok-"));
    assertEquals(1, tokens.deliveryMintsFor(projectId));
    assertEquals(1, tokens.mintsFor(projectId), "one lease, one desk token");
    assertEquals("desk-delivery", tokens.kindOf(row.deliveryTokenId));
    assertEquals(FrontDeskTokens.CONTEXT_KIND, tokens.kindOf(row.tokenId));
    String deliveryToken = row.deliveryTokenId;
    String deskToken = row.tokenId;

    setDelivery(projectId, false);
    hooks.onDeliveryChanged(projectId, delivery(false));

    FrontDesk after = row(projectId);
    assertNull(after.deliveryTokenId);
    assertFalse(tokens.isLive(deliveryToken));
    assertEquals(FrontDeskDesired.STOPPED, after.desired, "nothing else wants the desk");
    assertNull(after.leaseId);
    assertFalse(tokens.isLive(deskToken), "the lease's token goes with the lease");

    sweep.sweep(Instant.now());
    assertEquals(1, tokens.deliveryMintsFor(projectId), "an off loop is never minted for");
  }

  /** A failed delivery mint leaves the desk usable; the sweep mints it later. */
  @Test
  void aFailedDeliveryMintIsRetriedBySweep() {
    String projectId = project(FrontDeskLifecycle.ON_DEMAND);
    frontDesks.ensureRow(projectId);
    setDelivery(projectId, true);
    tokens.failMint(true);

    hooks.onDeliveryChanged(projectId, delivery(true));
    assertNull(row(projectId).deliveryTokenId);

    tokens.failMint(false);
    sweep.sweep(Instant.now());
    assertNotNull(row(projectId).deliveryTokenId);
    assertNull(row(projectId).failureDetail);
  }

  /** The delivery token goes with the desk, and the reconcile reaps an unclaimed one. */
  @Test
  void theDeliveryTokenGoesWithTheDeskAndTheReconcileReapsItsOrphans() {
    String projectId = project(FrontDeskLifecycle.ON_DEMAND);
    setDelivery(projectId, true);
    hooks.onDeliveryChanged(projectId, delivery(true));
    String claimed = row(projectId).deliveryTokenId;
    Instant old = Instant.now().minus(Duration.ofHours(2));
    tokens.plant(
        new FrontDeskTokens.Live(
            "delivery-orphan", FrontDeskTokens.CONTEXT_KIND_DELIVERY, "gone", old));

    assertEquals(1, tokenReconcile.reconcile(Instant.now()));
    assertFalse(tokens.isLive("delivery-orphan"));
    assertTrue(tokens.isLive(claimed));

    person().delete(base(projectId)).then().statusCode(204);
    assertFalse(tokens.isLive(claimed));
  }

  // --- the session token (qits-1482) --------------------------------------------------------------

  /**
   * The desk's session token: minted once, kind {@code desk-session}, held on the row and offered by
   * {@link FrontDesks#sessionToken}, carried in no lease spec, and revoked with the desk.
   */
  @Test
  void theDeskHoldsASessionTokenThatGoesWithIt() {
    String projectId = project(FrontDeskLifecycle.ON_DEMAND);
    person().post(base(projectId) + "/ensure").then().statusCode(200);
    person().post(base(projectId) + "/ensure").then().statusCode(200);
    sweep.sweep(Instant.now());

    FrontDesk row = row(projectId);
    assertEquals(1, tokens.sessionMintsFor(projectId));
    assertEquals(FrontDeskTokens.CONTEXT_KIND_SESSION, tokens.kindOf(row.sessionTokenId));
    assertEquals(projectId, tokens.contextOf(row.sessionTokenId));
    assertFalse(row.sessionTokenSubject.equals(row.tokenSubject), "a principal of its own");
    FrontDeskTokens.Minted session = frontDesks.sessionToken(projectId).orElseThrow();
    assertEquals(row.sessionTokenId, session.tokenId());

    system
        .requestOf(row.leaseId)
        .spec()
        .env()
        .forEach(
            (name, value) ->
                assertFalse(
                    value.contains(row.sessionTokenValue) || value.equals(row.sessionTokenSubject),
                    name + " carries the session token"));

    person().delete(base(projectId)).then().statusCode(204);
    assertFalse(tokens.isLive(row.sessionTokenId));
    assertTrue(frontDesks.sessionToken(projectId).isEmpty());
  }

  /** An idp that will not mint a session token leaves the desk usable; the sweep mints it later. */
  @Test
  void aRefusedSessionMintLeavesTheDeskUsable() {
    String projectId = project(FrontDeskLifecycle.ON_DEMAND);
    tokens.failSession(true);

    person()
        .post(base(projectId) + "/ensure")
        .then()
        .statusCode(200)
        .body("container.runtimeStatus", is("QUEUED"));
    assertNull(row(projectId).sessionTokenId);
    assertNull(row(projectId).failureDetail);

    tokens.failSession(false);
    sweep.sweep(Instant.now());
    assertNotNull(row(projectId).sessionTokenId);
  }

  /** The reconcile reaps a session token no row names, and spares the one its desk holds. */
  @Test
  void theReconcileReapsUnclaimedSessionTokens() {
    String projectId = project(FrontDeskLifecycle.ALWAYS_ON);
    sweep.sweep(Instant.now());
    String claimed = row(projectId).sessionTokenId;
    tokens.plant(
        new FrontDeskTokens.Live(
            "session-orphan",
            FrontDeskTokens.CONTEXT_KIND_SESSION,
            "gone",
            Instant.now().minus(Duration.ofHours(2))));

    assertEquals(1, tokenReconcile.reconcile(Instant.now()));
    assertFalse(tokens.isLive("session-orphan"));
    assertTrue(tokens.isLive(claimed));
  }

  // --- refusals ----------------------------------------------------------------------------------

  /** An ALWAYS_ON desk cannot be stopped: 409 FRONT_DESK_ALWAYS_ON, and nothing moves. */
  @Test
  void stoppingAnAlwaysOnDeskIs409() {
    String projectId = project(FrontDeskLifecycle.ALWAYS_ON);
    sweep.sweep(Instant.now());
    UUID leaseId = row(projectId).leaseId;

    person()
        .post(base(projectId) + "/stop")
        .then()
        .statusCode(409)
        .body("error", is(FrontDesks.FRONT_DESK_ALWAYS_ON));
    assertEquals(FrontDeskDesired.RUNNING, row(projectId).desired);
    assertEquals(leaseId, row(projectId).leaseId);
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

  /** With no public domain the desk is FAILED EDGE_PLANE_UNCONFIGURED, and asks for no lease. */
  @Test
  void anUnconfiguredEdgeIsFailed() {
    specs.domainOverride("localhost");
    String projectId = project(FrontDeskLifecycle.ON_DEMAND);

    person()
        .post(base(projectId) + "/ensure")
        .then()
        .statusCode(200)
        .body("container.runtimeStatus", is("FAILED"))
        .body("container.failureDetail", is(FrontDeskSpecs.EDGE_PLANE_UNCONFIGURED));
    assertTrue(system.requests().isEmpty());
    assertEquals(0, tokens.mintsFor(projectId), "no token for a desk that cannot be leased");

    specs.domainOverride(DOMAIN);
    person()
        .post(base(projectId) + "/ensure")
        .then()
        .statusCode(200)
        .body("container.runtimeStatus", is("QUEUED"));
    assertNull(row(projectId).failureDetail);
    assertNotNull(row(projectId).leaseId);
  }

  /** The bound subject is what the socket checks read: the current lease's token's. */
  @Test
  void theBoundSubjectIsTheLeasesTokenSubject() {
    String projectId = project(FrontDeskLifecycle.ALWAYS_ON);
    sweep.sweep(Instant.now());
    assertEquals(row(projectId).tokenSubject, frontDesks.tokenSubject(projectId));
    assertNull(frontDesks.tokenSubject(UUID.randomUUID().toString()));
  }
}
