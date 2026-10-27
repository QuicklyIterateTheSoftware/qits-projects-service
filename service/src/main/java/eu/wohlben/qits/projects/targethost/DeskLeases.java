package eu.wohlben.qits.projects.targethost;

import eu.wohlben.qits.projects.deskhost.FrontDeskSpecs;
import eu.wohlben.qits.projects.deskhost.FrontDeskTokens;
import eu.wohlben.qits.projects.deskhost.FrontDesks;
import eu.wohlben.qits.projects.entity.FrontDesk;
import eu.wohlben.qits.projects.entity.LegacyPlacement;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.persistence.FrontDeskRepository;
import eu.wohlben.qits.projects.persistence.LegacyPlacementRepository;
import eu.wohlben.qits.projects.persistence.ProjectRepository;
import eu.wohlben.qits.projects.targethost.SystemLeases.Lease;
import eu.wohlben.qits.projects.targethost.SystemLeases.LeaseRefused;
import eu.wohlben.qits.projects.targethost.SystemLeases.LeaseState;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * A project's front desk as a DESK lease on an execution target (epic qits-1481, qits-1551): it
 * replaces the front-desk runners' pull placement ({@code FrontDesks.take}) and their socket's
 * desk half ({@code FrontDeskWork implements DeskRunnerWork}).
 *
 * <ul>
 *   <li><b>Start</b> ({@link #apply}): a desk desired RUNNING with no lease gets one. Its {@code
 *       agent-container} token is minted through {@link FrontDeskTokens} immediately before the
 *       lease is asked for and stored in {@code token_*} (the token the previous lease ran with is
 *       given back first); then {@code POST /system/api/leases} with {@code slotType DESK}, {@code
 *       ownerRef <projectId>}, {@code workKey desk-<projectId>} and the spec {@link
 *       FrontDeskSpecs#compose} made.
 *   <li><b>Desired false</b>: the lease is ended ({@code DELETE /system/api/leases/{id}}); its work
 *       volume stays on the target for the next.
 *   <li><b>A roll</b> ({@link #roll}) is {@code PUT /system/api/leases/{id}/spec};
 *       {@code FrontDeskSpecRoll} keeps deciding when.
 *   <li><b>Removal</b> ({@link #retire}): the lease ended and, once it is ENDED, the work volume
 *       purged.
 * </ul>
 *
 * <p><b>The mapping</b> ({@link #observe}), from {@link TargetLeaseEvents}' {@code
 * TargetLeaseChanged} and its reconcile alike, onto the row the desk state is computed from
 * ({@code FrontDesks.state}): QUEUED keeps the lease's {@code detail} in {@code lease_detail} (the
 * desk reads QUEUED and the detail is the view's {@code failureDetail}); GRANTED reads PROVISIONING
 * — <b>for as long as it takes</b>: the target pulls the multi-GB desk image cold, and nothing here
 * times a grant out; RUNNING reads RUNNING and ends a legacy desk's {@code legacy_placement}
 * entry; EXITED and FAILED give the token back and end the lease (FAILED leaves the detail as the
 * desk's {@code failure_detail}, which the sweep or a person's Start retries); ENDED clears the
 * lease off the row, gives the token back, and a desk still wanted asks for the next one.
 *
 * <p><b>A desk is found by its lease id</b>, never by the lease's owner ref: a lease the row does
 * not name (an earlier one, or one a crash orphaned) moves nothing, and the reconcile ends it.
 *
 * <p><b>RUNNING only ever comes from a lease.</b> The desk state reads RUNNING only while its row
 * names a lease qits-system last called RUNNING; a row with no lease reads QUEUED or STOPPED, so a
 * desk whose container is gone can never read RUNNING with nothing to revise it (qits-1592's
 * lesson). V61 clears every runner report for that reason.
 *
 * <p><b>A legacy desk</b> (a {@code legacy_placement} entry, V61) asks for its first lease with
 * {@code adopt = qits-projects-desk-runner-desk-<projectId>-<oldRunnerId8>} and pinned to the
 * target on the old runner's docker engine — its engine id, else its host name, else the runner's
 * name. With no such target it waits QUEUED, saying which one, and asks again on every reconcile;
 * the desk's old container keeps running meanwhile, so its token is kept. Every later lease adopts
 * the same name ({@code adopted_volume}).
 *
 * <p>Each write is its own {@code requiringNew} transaction; every HTTP call and every token
 * revocation follows it.
 */
@ApplicationScoped
public class DeskLeases {

  private static final Logger LOG = Logger.getLogger(DeskLeases.class);

  /** The failure detail of a desk whose lease qits-system refused for good. */
  public static final String TARGET_REFUSED = "TARGET_REFUSED";

  @Inject FrontDeskRepository desks;

  @Inject ProjectRepository projects;

  @Inject LegacyPlacementRepository legacy;

  @Inject FrontDeskSpecs specs;

  @Inject FrontDeskTokens tokens;

  @Inject FrontDesks frontDesks;

  /** Absent is no qits-system: no desk can be leased, and a wanted desk says so while it waits. */
  @Inject Instance<SystemLeases> system;

  @ConfigProperty(name = "qits.projects.target-leases.ending-deadline", defaultValue = "PT60S")
  Duration endingDeadline;

  /** Work volumes a removal could not purge yet (their lease was still ending): the reconcile's. */
  private final Set<String> pendingPurges = ConcurrentHashMap.newKeySet();

  /** What {@link #observe} did. */
  public enum Outcome {
    NO_ROW,
    UNCHANGED,
    CHANGED
  }

  /** A lease qits-system no longer has: as good as ENDED. */
  public static Lease ended(UUID leaseId) {
    return new Lease(leaseId, null, SystemLeases.SLOT_TYPE, LeaseState.ENDED, null, null, null, null, null);
  }

  /** The {@code lease_detail} of a legacy desk waiting for the target that holds its volume. */
  public static String waitingForTarget(String runner) {
    return "waiting for target " + runner + " to be installed (it holds this desk's volume)";
  }

  /**
   * Whether a desk's failure keeps it from being leased by a pass: anything but a token that could
   * not be minted, which every pass tries again. A lease failure is retried by the sweep or a
   * person's Start, which clear it first.
   */
  static boolean blocks(String failureDetail) {
    return failureDetail != null && !failureDetail.startsWith(FrontDesks.TOKEN_UNAVAILABLE);
  }

  // --- desired state ---------------------------------------------------------------------------

  /**
   * Bring the desk of {@code projectId} in line with its desired state: wanted with no lease, one is
   * asked for; unwanted with a live lease, it is ended. Never throws for a refusal.
   */
  public void apply(String projectId) {
    FrontDesk row = read(projectId);
    if (row == null) {
      return;
    }
    if (row.wanted() && row.leaseId == null) {
      start(projectId);
    } else if (!row.wanted() && row.leaseId != null) {
      endLease(projectId);
    }
  }

  private void start(String projectId) {
    if (!system.isResolvable()) {
      waitWith(projectId, "no qits-system is configured to grant a desk slot");
      return;
    }
    SystemLeases leases = system.get();
    FrontDesk row = read(projectId);
    if (row == null || !row.wanted() || row.leaseId != null || blocks(row.failureDetail)) {
      return;
    }
    Project project = QuarkusTransaction.requiringNew().call(() -> projects.findById(projectId));
    if (project == null) {
      return;
    }
    if (specs.imageOrNull() == null) {
      recordFailure(projectId, FrontDeskSpecs.EDGE_PLANE_UNCONFIGURED);
      return;
    }
    LegacyPin pin = legacyPin(projectId, leases);
    if (pin.waitingFor() != null) {
      waitWith(projectId, waitingForTarget(pin.waitingFor()));
      return;
    }
    // The lease's token, minted immediately before the lease is asked for, outside any transaction.
    FrontDeskTokens.Minted minted;
    try {
      minted = tokens.mint(projectId);
    } catch (RuntimeException e) {
      LOG.warnf("Could not mint the desk token of project %s: %s", projectId, e.getMessage());
      recordFailure(projectId, FrontDesks.TOKEN_UNAVAILABLE + ": " + e.getMessage());
      return;
    }
    Optional<String> previous = swapToken(projectId, minted);
    if (previous == null) {
      // The row went, or took a lease meanwhile: nothing runs with this token.
      tokens.revoke(minted.tokenId());
      return;
    }
    previous.ifPresent(this::revokeQuietly);
    frontDesks.bindSubject(projectId, minted.subject());
    FrontDesk current = read(projectId);
    if (current == null) {
      giveBackToken(projectId, minted.tokenId());
      return;
    }
    FrontDeskSpecs.Composed composed;
    try {
      composed = specs.compose(project, current);
    } catch (FrontDeskSpecs.EdgePlaneUnconfigured unconfigured) {
      giveBackToken(projectId, minted.tokenId());
      recordFailure(projectId, FrontDeskSpecs.EDGE_PLANE_UNCONFIGURED);
      return;
    } catch (RuntimeException e) {
      giveBackToken(projectId, minted.tokenId());
      recordFailure(projectId, "Could not compose the desk: " + e.getMessage());
      return;
    }
    String adopted =
        pin.volumeGone()
            ? null
            : current.adoptedVolume != null && !current.adoptedVolume.isBlank()
                ? current.adoptedVolume
                : pin.legacyName();
    SystemLeases.LeaseRequest request =
        new SystemLeases.LeaseRequest(
            projectId,
            leaseSpec(composed.spec()),
            adopted == null ? null : new SystemLeases.Adopt(adopted, adopted),
            pin.targetId());
    Lease lease;
    try {
      lease = leases.create(request);
    } catch (LeaseRefused refused) {
      giveBackToken(projectId, minted.tokenId());
      if (refused.permanent()) {
        recordFailure(projectId, TARGET_REFUSED + ": " + refused.getMessage());
      } else {
        // LEASE_EXISTS (a previous lease still ending, or one a crash left), or no answer: the
        // reconcile asks again.
        waitWith(projectId, "qits-system has not granted a desk slot yet: " + refused.getMessage());
      }
      return;
    }
    if (lease == null || lease.id() == null) {
      giveBackToken(projectId, minted.tokenId());
      waitWith(projectId, "qits-system answered a lease request with no lease");
      return;
    }
    Instant now = Instant.now();
    String stored = adopted;
    boolean recorded =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  FrontDesk row2 = desks.findById(projectId);
                  if (row2 == null
                      || row2.leaseId != null
                      || !Objects.equals(row2.tokenId, minted.tokenId())) {
                    return false;
                  }
                  row2.leaseId = lease.id();
                  row2.targetId = lease.targetId();
                  row2.reportedState =
                      lease.state() == null ? LeaseState.QUEUED.name() : lease.state().name();
                  row2.reportedSpecHash = lease.specHash();
                  row2.reportedAt = now;
                  row2.leaseDetail = lease.detail();
                  row2.specJson = composed.json();
                  row2.specHash = composed.hash();
                  row2.placedAt = now;
                  row2.failureDetail = null;
                  row2.rollRequestedAt = null;
                  row2.adoptedVolume = stored;
                  if (row2.queuedAt == null) {
                    row2.queuedAt = now;
                  }
                  return true;
                });
    if (!recorded) {
      LOG.infof(
          "The desk of project %s changed while its lease %s was asked for; ending it",
          projectId, lease.id());
      endQuietly(leases, lease.id());
      giveBackToken(projectId, minted.tokenId());
      return;
    }
    LOG.infof(
        "Asked for DESK lease %s for the front desk of project %s%s%s",
        lease.id(),
        projectId,
        adopted == null ? "" : " (adopting " + adopted + ")",
        pin.targetId() == null ? "" : " pinned to target " + pin.targetId());
    if (lease.state() != null && lease.state() != LeaseState.QUEUED) {
      observe(lease);
    }
  }

  /** End the desk's lease: unwanted. Its work volume stays on the target. */
  public void endLease(String projectId) {
    FrontDesk row = read(projectId);
    if (row == null
        || row.leaseId == null
        || LeaseState.ENDING.name().equals(row.reportedState)
        || !system.isResolvable()) {
      return;
    }
    UUID leaseId = row.leaseId;
    Lease answer;
    try {
      answer = system.get().end(leaseId).orElseGet(() -> ended(leaseId));
    } catch (LeaseRefused refused) {
      LOG.warnf(
          "Could not end lease %s of the desk of project %s; the reconcile asks again: %s",
          leaseId, projectId, refused.getMessage());
      return;
    }
    observe(answer);
  }

  // --- a roll ----------------------------------------------------------------------------------

  /**
   * Roll the desk's lease onto {@code composed} ({@code PUT /system/api/leases/{id}/spec}): the
   * target recreates the container. True when it landed and the row holds the new spec; a pending
   * roll request is answered by it.
   */
  public boolean roll(FrontDesk row, FrontDeskSpecs.Composed composed) {
    if (row == null || row.leaseId == null || !system.isResolvable()) {
      return false;
    }
    UUID leaseId = row.leaseId;
    Lease rolled;
    try {
      rolled = system.get().replaceSpec(leaseId, leaseSpec(composed.spec()));
    } catch (LeaseRefused refused) {
      if (refused.status() == 404) {
        observe(ended(leaseId));
      }
      LOG.warnf(
          "Could not roll lease %s of the desk of project %s: %s",
          leaseId, row.projectId, refused.getMessage());
      return false;
    }
    String hash = rolled == null ? null : rolled.specHash();
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              FrontDesk current = desks.findById(row.projectId);
              if (current == null || !leaseId.equals(current.leaseId)) {
                return false;
              }
              current.specJson = composed.json();
              current.specHash = composed.hash();
              current.rollRequestedAt = null;
              if (hash != null) {
                current.reportedSpecHash = hash;
              }
              return true;
            });
  }

  // --- a lease's state, applied ------------------------------------------------------------------

  private record Applied(
      String projectId, boolean changed, String tokenToRevoke, boolean endLease, boolean ended) {}

  /** Applies what {@code lease} says to the desk that names it; see the class javadoc. */
  public Outcome observe(Lease lease) {
    if (lease == null || lease.id() == null || lease.state() == null) {
      return Outcome.NO_ROW;
    }
    Instant now = Instant.now();
    Applied applied =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  FrontDesk row = desks.lockByLeaseId(lease.id());
                  return row == null ? null : apply(row, lease, now);
                });
    if (applied == null) {
      return Outcome.NO_ROW;
    }
    if (applied.tokenToRevoke() != null) {
      frontDesks.unbindSubject(applied.projectId());
      revokeQuietly(applied.tokenToRevoke());
    }
    if (applied.endLease() && system.isResolvable()) {
      // The answer is applied too: an end qits-system answers ENDED lets the lease go at once.
      try {
        Lease answer = system.get().end(lease.id()).orElseGet(() -> ended(lease.id()));
        if (answer.state() == LeaseState.ENDED) {
          observe(answer);
        }
      } catch (RuntimeException e) {
        LOG.warnf("Could not end lease %s; the reconcile asks again: %s", lease.id(), e.getMessage());
      }
    }
    if (applied.ended()) {
      // A desk still wanted asks for its next lease now, not on the next pass.
      try {
        apply(applied.projectId());
      } catch (RuntimeException e) {
        LOG.warnf(e, "Could not restart the desk of project %s; the reconcile will", applied.projectId());
      }
    }
    return applied.changed() ? Outcome.CHANGED : Outcome.UNCHANGED;
  }

  private Applied apply(FrontDesk row, Lease lease, Instant now) {
    String wasState = row.reportedState;
    String wasDetail = row.leaseDetail;
    String wasFailure = row.failureDetail;
    UUID wasTarget = row.targetId;
    String token = null;
    boolean end = false;
    boolean ended = false;
    switch (lease.state()) {
      case QUEUED -> {
        row.reportedState = LeaseState.QUEUED.name();
        row.leaseDetail = lease.detail();
      }
      case GRANTED, RUNNING -> {
        row.reportedState = lease.state().name();
        row.leaseDetail = null;
        row.queuedAt = null;
        if (lease.targetId() != null) {
          row.targetId = lease.targetId();
        }
        if (lease.state() == LeaseState.RUNNING) {
          // The adopted lease is the desk's now: the legacy entry has done its work.
          legacy.deleteById(row.projectId);
        }
      }
      case ENDING -> row.reportedState = LeaseState.ENDING.name();
      case EXITED -> {
        row.reportedState = LeaseState.EXITED.name();
        token = clearToken(row);
        end = true;
      }
      case FAILED -> {
        row.reportedState = LeaseState.FAILED.name();
        row.failureDetail =
            lease.detail() == null || lease.detail().isBlank()
                ? "The target could not start the desk."
                : lease.detail();
        token = clearToken(row);
        end = true;
      }
      case ENDED -> {
        token = clearToken(row);
        row.leaseId = null;
        row.targetId = null;
        row.reportedState = null;
        row.reportedSpecHash = null;
        row.leaseDetail = null;
        ended = true;
      }
      case UNKNOWN -> {}
    }
    if (lease.state() != LeaseState.ENDED && lease.specHash() != null) {
      row.reportedSpecHash = lease.specHash();
    }
    row.reportedAt = now;
    boolean changed =
        ended
            || token != null
            || !Objects.equals(wasState, row.reportedState)
            || !Objects.equals(wasDetail, row.leaseDetail)
            || !Objects.equals(wasFailure, row.failureDetail)
            || !Objects.equals(wasTarget, row.targetId);
    return new Applied(row.projectId, changed, token, end, ended);
  }

  // --- the reconcile ---------------------------------------------------------------------------

  /**
   * One reconcile pass (every {@code qits.projects.target-leases.reconcile-interval}): each desk
   * that names a lease is told that lease's state, a live lease of this service's that no desk
   * names, older than {@code orphanAfter}, is ended, and every desk is brought in line with its
   * desired state. Answers the projects whose desk moved; nothing when qits-system cannot be asked.
   *
   * <p><b>Never a verdict from a listing that predates the lease</b> (qits-workspaces' reconcile
   * race, qits-1532):
   *
   * <ol>
   *   <li>the desks and the leases they name are read <b>first</b>, then the leases are listed, and
   *       only the desks of that snapshot are reconciled — a lease recorded after the snapshot is
   *       not looked at in this pass;
   *   <li>a snapshot lease the listing does not hold is asked for directly: only a 404 is ENDED; an
   *       answer is applied as given; a failure leaves the desk for the next pass;
   *   <li>an orphan is a listed lease that no desk names <b>in a fresh read made after the
   *       listing</b> and that is older than {@code orphanAfter}.
   * </ol>
   */
  public List<String> reconcile(Duration orphanAfter, Instant now) {
    if (!system.isResolvable()) {
      return List.of();
    }
    SystemLeases leases = system.get();
    Map<String, UUID> snapshot = new LinkedHashMap<>();
    for (FrontDesk row : QuarkusTransaction.requiringNew().call(desks::findWithLease)) {
      snapshot.put(row.projectId, row.leaseId);
    }
    List<Lease> listed;
    try {
      listed = leases.list();
    } catch (RuntimeException e) {
      // An unreadable listing must not read as "every lease is gone".
      LOG.debugf("Could not list this service's leases; the next pass asks again: %s", e.getMessage());
      return List.of();
    }
    Map<UUID, Lease> byId = new HashMap<>();
    for (Lease lease : listed) {
      if (lease != null
          && lease.id() != null
          && (lease.slotType() == null || SystemLeases.SLOT_TYPE.equals(lease.slotType()))) {
        byId.put(lease.id(), lease);
      }
    }
    List<String> changed = new ArrayList<>();
    for (Map.Entry<String, UUID> desk : snapshot.entrySet()) {
      UUID leaseId = desk.getValue();
      Lease lease = byId.get(leaseId);
      if (lease == null) {
        try {
          lease = leases.get(leaseId).orElseGet(() -> ended(leaseId));
        } catch (RuntimeException e) {
          LOG.debugf(
              "Could not read lease %s of the desk of project %s; the next pass asks again: %s",
              leaseId, desk.getKey(), e.getMessage());
          continue;
        }
      }
      try {
        if (observe(lease) == Outcome.CHANGED) {
          changed.add(desk.getKey());
        }
      } catch (RuntimeException e) {
        LOG.warnf(e, "Could not reconcile the desk of project %s with lease %s", desk.getKey(), leaseId);
      }
    }
    Set<UUID> named = new HashSet<>();
    for (FrontDesk row : QuarkusTransaction.requiringNew().call(desks::findWithLease)) {
      named.add(row.leaseId);
    }
    for (Lease lease : byId.values()) {
      if (named.contains(lease.id())
          || lease.state() == null
          || !lease.state().live()
          || lease.state() == LeaseState.ENDING
          || lease.requestedAt() == null
          || lease.requestedAt().isAfter(now.minus(orphanAfter))) {
        continue;
      }
      LOG.infof("Ending lease %s (owner ref %s): no front desk names it", lease.id(), lease.ownerRef());
      endQuietly(leases, lease.id());
    }
    for (FrontDesk row : QuarkusTransaction.requiringNew().call(() -> desks.listAll())) {
      if ((row.wanted() && row.leaseId == null && !blocks(row.failureDetail))
          || (!row.wanted() && row.leaseId != null)) {
        try {
          apply(row.projectId);
        } catch (RuntimeException e) {
          LOG.warnf(e, "Could not bring the desk of project %s in line", row.projectId);
        }
      }
    }
    retryPurges(leases);
    return changed;
  }

  // --- removal ---------------------------------------------------------------------------------

  /**
   * A desk whose row is gone (its DELETE, its project's deletion): its lease ended and, once
   * qits-system calls it ENDED (up to {@code qits.projects.target-leases.ending-deadline}), its work
   * volume purged; one still ending then is purged by a later reconcile. A legacy desk never adopted
   * leaves its old volume on the old node, named in a WARN for a person.
   */
  public void retire(FrontDesk gone) {
    if (gone == null) {
      return;
    }
    String projectId = gone.projectId;
    LegacyPlacement entry =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  LegacyPlacement found = legacy.findById(projectId);
                  if (found != null) {
                    legacy.delete(found);
                  }
                  return found;
                });
    if (entry != null) {
      LOG.warnf(
          "The front desk of project %s was removed before its first lease adopted %s on runner %s's"
              + " node; a person removes that container and volume",
          projectId, entry.legacyName(), entry.runnerName);
    }
    if (!system.isResolvable()) {
      return;
    }
    SystemLeases leases = system.get();
    if (gone.leaseId != null && !awaitEnded(leases, gone.leaseId)) {
      pendingPurges.add(SystemLeases.workKey(projectId));
      LOG.infof(
          "Lease %s of the removed desk of project %s is still ending; its volume is purged later",
          gone.leaseId, projectId);
      return;
    }
    purge(leases, SystemLeases.workKey(projectId));
  }

  /** End {@code leaseId} and wait for ENDED; true once it is (or qits-system has no such lease). */
  private boolean awaitEnded(SystemLeases leases, UUID leaseId) {
    Instant deadline = Instant.now().plus(endingDeadline);
    try {
      Optional<Lease> now = leases.end(leaseId);
      while (now.isPresent() && now.get().state() != LeaseState.ENDED) {
        if (!Instant.now().isBefore(deadline)) {
          return false;
        }
        Thread.sleep(500);
        now = leases.get(leaseId);
      }
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    } catch (LeaseRefused refused) {
      LOG.warnf("Could not end lease %s: %s", leaseId, refused.getMessage());
      return false;
    }
  }

  private void purge(SystemLeases leases, String workKey) {
    try {
      leases.purgeWorkVolume(workKey);
      pendingPurges.remove(workKey);
    } catch (LeaseRefused refused) {
      pendingPurges.add(workKey);
      LOG.warnf("Could not purge work volume %s yet: %s", workKey, refused.getMessage());
    }
  }

  private void retryPurges(SystemLeases leases) {
    for (String workKey : List.copyOf(pendingPurges)) {
      String projectId = workKey.substring("desk-".length());
      if (read(projectId) != null) {
        // A desk came back meanwhile and works on that volume now.
        pendingPurges.remove(workKey);
        continue;
      }
      purge(leases, workKey);
    }
  }

  /** The work keys waiting for a purge; for the tests. */
  Set<String> pendingPurges() {
    return Set.copyOf(pendingPurges);
  }

  // --- the legacy pin ------------------------------------------------------------------------------

  /**
   * Where a desk a retired runner held may be adopted.
   *
   * @param targetId the target on the old runner's docker engine, or null
   * @param waitingFor the old runner's name while no target on its node is installed
   * @param volumeGone the entry names nothing to find the node by: asked for unpinned, adopting
   *     nothing
   * @param legacyName the old container and volume, or null for a desk with no entry
   */
  private record LegacyPin(UUID targetId, String waitingFor, boolean volumeGone, String legacyName) {
    static final LegacyPin NONE = new LegacyPin(null, null, false, null);
  }

  /**
   * The target on the docker engine a retired runner ran the desk on: the target whose engine id is
   * the one the runner recorded, else whose host name is, else whose name is the runner's name.
   * <b>No match is a wait, never an unpinned lease</b>: a lease granted anywhere else would start
   * the desk on a fresh volume and strand its work on the old node. The case is logged at WARN with
   * the desk and the volume.
   */
  private LegacyPin legacyPin(String projectId, SystemLeases leases) {
    LegacyPlacement entry =
        QuarkusTransaction.requiringNew().call(() -> legacy.findById(projectId));
    if (entry == null) {
      return LegacyPin.NONE;
    }
    if (blank(entry.runnerName) && blank(entry.engineId) && blank(entry.hostname)) {
      LOG.warnf(
          "The desk of project %s was held by runner %s, of which nothing is recorded: volume %s is"
              + " taken as gone, and the desk starts on a fresh volume",
          projectId, entry.oldRunnerId, entry.legacyName());
      QuarkusTransaction.requiringNew().run(() -> legacy.deleteById(projectId));
      return new LegacyPin(null, null, true, null);
    }
    String waitFor = blank(entry.runnerName) ? entry.oldRunnerId.toString() : entry.runnerName;
    List<SystemLeases.Target> targets;
    try {
      targets = leases.targets();
    } catch (RuntimeException unread) {
      LOG.warnf(
          "The desk of project %s adopts %s, and the targets could not be read to pin it (%s); it"
              + " waits",
          projectId, entry.legacyName(), unread.getMessage());
      return new LegacyPin(null, waitFor, false, entry.legacyName());
    }
    Optional<UUID> pinned =
        match(targets, entry.engineId, SystemLeases.Target::engineId)
            .or(() -> match(targets, entry.hostname, SystemLeases.Target::hostname))
            .or(() -> match(targets, entry.runnerName, SystemLeases.Target::name));
    if (pinned.isEmpty()) {
      LOG.warnf(
          "The desk of project %s adopts volume %s, and no target is on runner %s's node yet"
              + " (engine id %s, host name %s): it waits for one",
          projectId, entry.legacyName(), entry.runnerName, entry.engineId, entry.hostname);
      return new LegacyPin(null, waitFor, false, entry.legacyName());
    }
    return new LegacyPin(pinned.get(), null, false, entry.legacyName());
  }

  private static Optional<UUID> match(
      List<SystemLeases.Target> targets,
      String wanted,
      Function<SystemLeases.Target, String> fact) {
    if (blank(wanted)) {
      return Optional.empty();
    }
    return targets.stream()
        .filter(t -> wanted.equals(fact.apply(t)))
        .map(SystemLeases.Target::id)
        .filter(Objects::nonNull)
        .findFirst();
  }

  // --- internals ---------------------------------------------------------------------------------

  /** The lease spec of a composed desk spec. */
  static SystemLeases.LeaseSpec leaseSpec(FrontDeskSpecs.DeskSpec spec) {
    return new SystemLeases.LeaseSpec(
        spec.image(),
        spec.env(),
        spec.user(),
        new SystemLeases.Limits(
            spec.memory(), spec.memorySwap(), spec.pidsLimit(), spec.cpus(), spec.oomScoreAdj()));
  }

  private FrontDesk read(String projectId) {
    return QuarkusTransaction.requiringNew().call(() -> desks.findById(projectId));
  }

  /**
   * Store {@code minted} on a desk that still has no lease; answers the token it replaces (empty for
   * none), or null when the desk is gone or holds a lease now.
   */
  private Optional<String> swapToken(String projectId, FrontDeskTokens.Minted minted) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              FrontDesk row = desks.findById(projectId);
              if (row == null || row.leaseId != null) {
                return null;
              }
              String previous = row.tokenId;
              row.tokenId = minted.tokenId();
              row.tokenValue = minted.token();
              row.tokenSubject = minted.subject();
              if (row.failureDetail != null
                  && row.failureDetail.startsWith(FrontDesks.TOKEN_UNAVAILABLE)) {
                row.failureDetail = null;
              }
              return Optional.ofNullable(previous);
            });
  }

  /** Clear {@code tokenId} off the desk (if it still holds it) and revoke it. */
  private void giveBackToken(String projectId, String tokenId) {
    boolean cleared =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  FrontDesk row = desks.findById(projectId);
                  if (row == null || !Objects.equals(row.tokenId, tokenId)) {
                    return false;
                  }
                  clearToken(row);
                  return true;
                });
    if (cleared) {
      frontDesks.unbindSubject(projectId);
    }
    revokeQuietly(tokenId);
  }

  private static String clearToken(FrontDesk row) {
    String tokenId = row.tokenId;
    row.tokenId = null;
    row.tokenValue = null;
    row.tokenSubject = null;
    return tokenId;
  }

  private void revokeQuietly(String tokenId) {
    try {
      if (!tokens.revoke(tokenId)) {
        LOG.warnf("Could not revoke desk token %s; the token reconcile reaps it", tokenId);
      }
    } catch (RuntimeException e) {
      LOG.warnf("Could not revoke desk token %s; the token reconcile reaps it: %s", tokenId, e.getMessage());
    }
  }

  private static void endQuietly(SystemLeases leases, UUID leaseId) {
    try {
      leases.end(leaseId);
    } catch (RuntimeException e) {
      LOG.warnf("Could not end lease %s; the reconcile asks again: %s", leaseId, e.getMessage());
    }
  }

  /** A wanted desk with no lease, waiting, and why. */
  private void waitWith(String projectId, String detail) {
    Instant now = Instant.now();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              FrontDesk row = desks.findById(projectId);
              if (row != null && row.leaseId == null) {
                row.leaseDetail = detail;
                if (row.wanted() && row.queuedAt == null) {
                  row.queuedAt = now;
                }
              }
            });
  }

  private void recordFailure(String projectId, String detail) {
    QuarkusTransaction.requiringNew()
        .run(() -> desks.update("failureDetail = ?1 where projectId = ?2", detail, projectId));
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }
}
