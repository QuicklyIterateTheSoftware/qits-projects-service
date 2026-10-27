package eu.wohlben.qits.projects.targethost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.agenthost.AgentRuntimeStatus;
import eu.wohlben.qits.projects.deskhost.FakeFrontDeskTokens;
import eu.wohlben.qits.projects.deskhost.FrontDeskSpecs;
import eu.wohlben.qits.projects.deskhost.FrontDeskSweep;
import eu.wohlben.qits.projects.deskhost.FrontDesks;
import eu.wohlben.qits.projects.entity.FrontDesk;
import eu.wohlben.qits.projects.entity.FrontDeskDesired;
import eu.wohlben.qits.projects.entity.FrontDeskLifecycle;
import eu.wohlben.qits.projects.entity.LegacyPlacement;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.persistence.FrontDeskRepository;
import eu.wohlben.qits.projects.persistence.LegacyPlacementRepository;
import eu.wohlben.qits.projects.persistence.ProjectRepository;
import eu.wohlben.qits.projects.targethost.SystemLeases.LeaseState;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A front desk's DESK lease (epic qits-1481, qits-1551): what each lease state does to the desk,
 * qits-system's {@code TargetLeaseChanged} and the reconcile, the reconcile race, the lease-less
 * desk, the legacy adoption and the removal's purge. qits-system is {@link FakeSystemLeases}.
 */
@QuarkusTest
class DeskLeasesTest {

  @Inject DeskLeases leases;
  @Inject TargetLeaseEvents events;
  @Inject FakeSystemLeases system;
  @Inject FakeFrontDeskTokens tokens;
  @Inject FrontDesks frontDesks;
  @Inject FrontDeskSweep sweep;
  @Inject FrontDeskSpecs specs;
  @Inject FrontDeskRepository desks;
  @Inject ProjectRepository projects;
  @Inject LegacyPlacementRepository legacy;

  private final List<String> created = new ArrayList<>();

  @BeforeEach
  void arrange() {
    specs.domainOverride("suite.example");
    system.reset();
    tokens.reset();
  }

  @AfterEach
  void tidy() {
    specs.domainOverride(null);
    leases.legacyDeskTargetOverride(null);
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              created.forEach(legacy::deleteById);
              created.forEach(desks::deleteById);
              created.forEach(projects::deleteById);
            });
    created.clear();
    system.reset();
  }

  /** An ALWAYS_ON project whose desk the sweep has leased: QUEUED. */
  private String leasedDesk() {
    String id = desk(FrontDeskLifecycle.ALWAYS_ON);
    sweep.sweep(Instant.now());
    assertNotNull(row(id).leaseId);
    return id;
  }

  private String desk(FrontDeskLifecycle lifecycle) {
    String id = UUID.randomUUID().toString();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Project project = new Project();
              project.id = id;
              project.name = "Leased desk " + id.substring(0, 8);
              project.slug = "ld-" + id.substring(0, 8);
              project.frontDeskLifecycle = lifecycle;
              projects.persist(project);
            });
    created.add(id);
    return id;
  }

  private FrontDesk row(String projectId) {
    return QuarkusTransaction.requiringNew().call(() -> desks.findById(projectId));
  }

  private AgentRuntimeStatus status(String projectId) {
    return frontDesks.status(projectId).runtimeStatus();
  }

  private TargetLeaseChanged changed(UUID leaseId, String owner, String slotType, String state) {
    return new TargetLeaseChanged(
        UUID.randomUUID(),
        leaseId.toString(),
        owner,
        "ignored",
        slotType,
        UUID.randomUUID().toString(),
        state,
        null,
        null,
        Instant.now());
  }

  // --- the mapping ---------------------------------------------------------------------------------

  /**
   * GRANTED reads PROVISIONING with its target, and stays so however long the cold pull of the
   * multi-GB image takes: nothing times a grant out; RUNNING reads RUNNING.
   */
  @Test
  void aGrantIsProvisioningForAsLongAsItTakesThenRunning() {
    String projectId = leasedDesk();
    UUID leaseId = row(projectId).leaseId;
    UUID target = UUID.randomUUID();
    system.move(leaseId, LeaseState.GRANTED, target, null);

    events.onEvent(changed(leaseId, "qits-projects", "DESK", "GRANTED"));
    assertEquals(AgentRuntimeStatus.PROVISIONING, status(projectId));
    assertNotNull(row(projectId).targetId);
    assertNull(row(projectId).queuedAt);

    // Pass after pass, the lease still GRANTED: nothing ends it, nothing asks for another.
    for (int pass = 0; pass < 3; pass++) {
      leases.reconcile(Duration.ofMinutes(2), Instant.now().plus(Duration.ofHours(pass + 1)));
    }
    assertEquals(AgentRuntimeStatus.PROVISIONING, status(projectId));
    assertEquals(leaseId, row(projectId).leaseId);
    assertEquals(target, row(projectId).targetId, "the reconcile read the target off the listing");
    assertTrue(system.ended().isEmpty());
    assertEquals(1, system.requests().size());

    leases.observe(system.move(leaseId, LeaseState.RUNNING, target, null));
    assertEquals(AgentRuntimeStatus.RUNNING, status(projectId));
    assertEquals(target, frontDesks.status(projectId).targetId());
  }

  /** Another owner's lease event, or another slot type's, moves nothing. */
  @Test
  void anotherOwnersOrAnotherSlotTypesEventMovesNothing() {
    String projectId = leasedDesk();
    UUID leaseId = row(projectId).leaseId;

    events.onEvent(changed(leaseId, "qits-workspaces", "DESK", "RUNNING"));
    events.onEvent(changed(leaseId, "qits-projects", "WORKSPACE", "RUNNING"));

    assertEquals(AgentRuntimeStatus.QUEUED, status(projectId));
  }

  /**
   * FAILED leaves the desk FAILED with the target's detail, gives the token back and ends the lease;
   * the reconcile does not ask again in a loop — the sweep does, once an interval.
   */
  @Test
  void aFailedLeaseFailsTheDeskUntilTheSweepTriesAgain() {
    String projectId = leasedDesk();
    UUID leaseId = row(projectId).leaseId;
    String tokenId = row(projectId).tokenId;

    leases.observe(system.move(leaseId, LeaseState.FAILED, UUID.randomUUID(), "image pull failed"));

    assertState(projectId, AgentRuntimeStatus.FAILED, "image pull failed");
    assertFalse(tokens.isLive(tokenId), "the lease's token goes with it");
    assertTrue(system.ended().contains(leaseId));
    assertNull(row(projectId).leaseId, "the end answered ENDED and the desk lets the lease go");

    leases.reconcile(Duration.ofMinutes(2), Instant.now());
    assertEquals(1, system.requests().size(), "a failed desk is not re-leased by the reconcile");

    sweep.sweep(Instant.now());
    assertEquals(2, system.requests().size(), "the sweep tries it again");
    assertEquals(AgentRuntimeStatus.QUEUED, status(projectId));
  }

  private void assertState(String projectId, AgentRuntimeStatus status, String detail) {
    assertEquals(status, frontDesks.status(projectId).runtimeStatus());
    assertEquals(detail, frontDesks.status(projectId).failureDetail());
  }

  /** EXITED gives the token back and ends the lease; a desk still wanted asks for its next one. */
  @Test
  void anExitedLeaseIsReplacedWhileTheDeskIsWanted() {
    String projectId = leasedDesk();
    UUID leaseId = row(projectId).leaseId;
    String tokenId = row(projectId).tokenId;

    leases.observe(system.move(leaseId, LeaseState.EXITED, UUID.randomUUID(), null));

    assertFalse(tokens.isLive(tokenId));
    assertTrue(system.ended().contains(leaseId));
    UUID next = row(projectId).leaseId;
    assertNotNull(next);
    assertFalse(leaseId.equals(next), "a new lease, on a new token");
    assertFalse(tokenId.equals(row(projectId).tokenId));
  }

  // --- the reconcile ----------------------------------------------------------------------------------

  /**
   * The reconcile race (qits-1532's fix, copied): a lease the listing does not hold is asked for
   * directly — an answer is applied as given and never read as ENDED; only a 404 is ENDED.
   */
  @Test
  void aLeaseMissingFromTheListingIsAskedForBeforeItIsEnded() {
    String projectId = leasedDesk();
    UUID leaseId = row(projectId).leaseId;
    system.hideFromListing(true);

    leases.reconcile(Duration.ofMinutes(2), Instant.now());
    assertEquals(leaseId, row(projectId).leaseId, "a lease the GET still finds is not ended");
    assertTrue(system.calls().contains("get"));

    system.forget(leaseId);
    List<String> changed = leases.reconcile(Duration.ofMinutes(2), Instant.now());
    assertTrue(changed.contains(projectId), changed.toString());
    assertFalse(leaseId.equals(row(projectId).leaseId), "a 404 is ENDED");
    assertNotNull(row(projectId).leaseId, "and the wanted desk asked for its next lease");
  }

  /** An unreadable listing must not read as "every lease is gone". */
  @Test
  void anUnreadableListingMovesNothing() {
    String projectId = leasedDesk();
    UUID leaseId = row(projectId).leaseId;
    system.unreachable(new SystemLeases.LeaseRefused(-1, null, "down", null));

    assertEquals(List.of(), leases.reconcile(Duration.ofMinutes(2), Instant.now()));
    system.unreachable(null);
    assertEquals(leaseId, row(projectId).leaseId);
    assertEquals(AgentRuntimeStatus.QUEUED, status(projectId));
  }

  /** A live lease of this service's that no desk names, older than the write window, is ended. */
  @Test
  void anOrphanedLeaseIsEndedAndAYoungOneIsNot() {
    UUID orphan = system.plant("gone", LeaseState.QUEUED, Instant.now().minus(Duration.ofMinutes(5)));
    UUID young = system.plant("racing", LeaseState.QUEUED, Instant.now());

    leases.reconcile(Duration.ofMinutes(2), Instant.now());

    assertTrue(system.ended().contains(orphan));
    assertFalse(system.ended().contains(young));
  }

  /**
   * A desk with no lease never reads RUNNING (qits-1592's lesson): whatever a runner once reported,
   * RUNNING is only ever a lease's word.
   */
  @Test
  void aDeskWithNoLeaseNeverReadsRunning() {
    String projectId = desk(FrontDeskLifecycle.ON_DEMAND);
    frontDesks.ensureRow(projectId);
    QuarkusTransaction.requiringNew()
        .run(() -> desks.update("reportedState = 'RUNNING' where projectId = ?1", projectId));

    assertEquals(AgentRuntimeStatus.STOPPED, status(projectId));
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                desks.update(
                    "desired = ?1, reportedState = 'RUNNING' where projectId = ?2",
                    FrontDeskDesired.RUNNING,
                    projectId));
    assertEquals(AgentRuntimeStatus.QUEUED, status(projectId));
  }

  // --- legacy adoption ------------------------------------------------------------------------------

  private void heldBy(String projectId, UUID oldRunner, String runnerName, String engineId) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              LegacyPlacement entry = new LegacyPlacement();
              entry.projectId = projectId;
              entry.oldRunnerId = oldRunner;
              entry.runnerName = runnerName;
              entry.engineId = engineId;
              legacy.persist(entry);
            });
  }

  /**
   * A desk a retired runner held waits, QUEUED and saying for which target, until a target on its
   * node is installed — keeping its token, since its old container still runs on it — and then its
   * first lease adopts the old container and volume, pinned to that target. RUNNING ends the legacy
   * entry; every later lease adopts the same volume.
   */
  @Test
  void aLegacyDeskWaitsForItsTargetThenAdoptsItsVolumePinned() {
    String projectId = desk(FrontDeskLifecycle.ALWAYS_ON);
    UUID oldRunner = UUID.fromString("8d45fd1d-0000-4000-8000-000000000001");
    heldBy(projectId, oldRunner, "desk-proof", null);
    frontDesks.ensureRow(projectId);
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                desks.update(
                    "tokenId = 'old', tokenValue = 'qits_tok_old', tokenSubject = 'tok-old'"
                        + " where projectId = ?1",
                    projectId));
    tokens.plant(
        new eu.wohlben.qits.projects.deskhost.FrontDeskTokens.Live(
            "old", "agent-container", projectId, Instant.now().minus(Duration.ofHours(2))));
    system.targets(List.of(new SystemLeases.Target(UUID.randomUUID(), "elsewhere", null, null, 0)));

    sweep.sweep(Instant.now());

    FrontDesk waiting = row(projectId);
    assertNull(waiting.leaseId);
    assertTrue(system.requests().isEmpty(), "never an unpinned lease that strands the volume");
    assertEquals("old", waiting.tokenId, "the old container still runs on its token");
    assertTrue(tokens.isLive("old"));
    assertEquals(AgentRuntimeStatus.QUEUED, status(projectId));
    assertEquals(DeskLeases.waitingForTarget("desk-proof"), frontDesks.status(projectId).failureDetail());

    UUID target = UUID.randomUUID();
    system.targets(List.of(new SystemLeases.Target(target, "desk-proof", null, null, 0)));
    leases.reconcile(Duration.ofMinutes(2), Instant.now());

    String name = LegacyPlacement.legacyName(projectId, oldRunner);
    assertEquals("qits-projects-desk-runner-desk-" + projectId + "-8d45fd1d", name);
    UUID leaseId = row(projectId).leaseId;
    SystemLeases.LeaseRequest first = system.requestOf(leaseId);
    assertEquals(new SystemLeases.Adopt(name, name), first.adopt());
    assertEquals(target, first.pinTargetId());
    assertFalse(tokens.isLive("old"), "the old token is given back once the new lease is asked for");

    leases.observe(system.move(leaseId, LeaseState.RUNNING, target, null));
    assertNull(QuarkusTransaction.requiringNew().call(() -> legacy.findById(projectId)));
    assertEquals(name, row(projectId).adoptedVolume);

    leases.observe(system.move(leaseId, LeaseState.ENDED, null, null));
    SystemLeases.LeaseRequest next = system.requestOf(row(projectId).leaseId);
    assertEquals(new SystemLeases.Adopt(name, name), next.adopt(), "every lease adopts the volume");
  }

  /** The engine id the runner recorded wins over its name. */
  @Test
  void theSameEngineIsPreferred() {
    String projectId = desk(FrontDeskLifecycle.ALWAYS_ON);
    heldBy(projectId, UUID.randomUUID(), "desk-proof", "ENGINE-1");
    UUID byEngine = UUID.randomUUID();
    system.targets(
        List.of(
            new SystemLeases.Target(UUID.randomUUID(), "desk-proof", null, null, 0),
            new SystemLeases.Target(byEngine, "lab", "ENGINE-1", null, 0)));

    sweep.sweep(Instant.now());

    assertEquals(byEngine, system.requestOf(row(projectId).leaseId).pinTargetId());
  }

  /**
   * qits-1551: a legacy desk whose old runner's {@code hello} reported neither an engine id nor a
   * hostname (the retired {@code platform-host} desk runner) pins to the configured target, by
   * name, when it is in the listing — even while another target reserves DESK slots.
   */
  @Test
  void theExplicitlyConfiguredTargetPinsWhenNothingElseMatches() {
    String projectId = desk(FrontDeskLifecycle.ALWAYS_ON);
    heldBy(projectId, UUID.randomUUID(), "platform-host", null);
    UUID configured = UUID.randomUUID();
    UUID reserving = UUID.randomUUID();
    system.targets(
        List.of(
            new SystemLeases.Target(reserving, "localhost", null, null, 1),
            new SystemLeases.Target(configured, "configured-target", null, null, 0)));
    leases.legacyDeskTargetOverride("configured-target");

    sweep.sweep(Instant.now());

    assertEquals(configured, system.requestOf(row(projectId).leaseId).pinTargetId());
  }

  /**
   * With no engine id, hostname or name match and no explicit pin configured, the one target that
   * reserves DESK slots is the fallback (qits-1551).
   */
  @Test
  void theSoleDeskReservingTargetPinsWhenNothingElseMatches() {
    String projectId = desk(FrontDeskLifecycle.ALWAYS_ON);
    heldBy(projectId, UUID.randomUUID(), "platform-host", null);
    UUID reserving = UUID.randomUUID();
    system.targets(
        List.of(
            new SystemLeases.Target(UUID.randomUUID(), "localhost", null, null, 0),
            new SystemLeases.Target(reserving, "lab", null, null, 1)));

    sweep.sweep(Instant.now());

    assertEquals(reserving, system.requestOf(row(projectId).leaseId).pinTargetId());
  }

  /** Two targets reserving DESK slots is a guess this service refuses to make: the desk waits. */
  @Test
  void twoDeskReservingTargetsWaitRatherThanGuess() {
    String projectId = desk(FrontDeskLifecycle.ALWAYS_ON);
    heldBy(projectId, UUID.randomUUID(), "platform-host", null);
    system.targets(
        List.of(
            new SystemLeases.Target(UUID.randomUUID(), "lab-1", null, null, 1),
            new SystemLeases.Target(UUID.randomUUID(), "lab-2", null, null, 1)));

    sweep.sweep(Instant.now());

    assertNull(row(projectId).leaseId);
    assertTrue(system.requests().isEmpty());
    assertEquals(
        DeskLeases.waitingForTarget("platform-host"), frontDesks.status(projectId).failureDetail());
  }

  /**
   * The reported-fact matches (here, the engine id) still win first, over both the explicit pin and
   * the sole-desk-reserving-target fallback.
   */
  @Test
  void anEngineMatchStillWinsOverTheExplicitPinAndTheSoleDeskReservingTarget() {
    String projectId = desk(FrontDeskLifecycle.ALWAYS_ON);
    heldBy(projectId, UUID.randomUUID(), "platform-host", "ENGINE-X");
    UUID byEngine = UUID.randomUUID();
    UUID configured = UUID.randomUUID();
    system.targets(
        List.of(
            new SystemLeases.Target(byEngine, "localhost", "ENGINE-X", null, 1),
            new SystemLeases.Target(configured, "configured-target", null, null, 0)));
    leases.legacyDeskTargetOverride("configured-target");

    sweep.sweep(Instant.now());

    assertEquals(byEngine, system.requestOf(row(projectId).leaseId).pinTargetId());
  }

  // --- removal ---------------------------------------------------------------------------------------

  /**
   * A removal whose lease is still ending leaves the volume for the reconcile, which purges it once
   * the lease is ENDED — never while a lease still uses it.
   */
  @Test
  void aRemovalStillEndingIsPurgedByALaterReconcile() {
    String projectId = desk(FrontDeskLifecycle.ON_DEMAND);
    frontDesks.ensure(projectId);
    UUID leaseId = row(projectId).leaseId;
    system.slowEnd(true);

    frontDesks.remove(projectId);

    assertNull(row(projectId));
    assertTrue(system.purged().isEmpty(), "not while the lease is ending");

    system.move(leaseId, LeaseState.ENDED, null, null);
    leases.reconcile(Duration.ofMinutes(2), Instant.now());
    assertEquals(List.of(SystemLeases.workKey(projectId)), system.purged());
  }
}
