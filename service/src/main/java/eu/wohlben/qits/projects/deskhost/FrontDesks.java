package eu.wohlben.qits.projects.deskhost;

import eu.wohlben.qits.projects.agenthost.AgentDaemonRegistry;
import eu.wohlben.qits.projects.agenthost.AgentRuntimeStatus;
import eu.wohlben.qits.projects.agenthost.AgentTunnels;
import eu.wohlben.qits.projects.control.ProjectService;
import eu.wohlben.qits.projects.entity.DeskRole;
import eu.wohlben.qits.projects.entity.DeskRunner;
import eu.wohlben.qits.projects.entity.FrontDesk;
import eu.wohlben.qits.projects.entity.FrontDeskDesired;
import eu.wohlben.qits.projects.entity.FrontDeskLifecycle;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.error.CodedRefusalException;
import eu.wohlben.qits.projects.error.DomainException;
import eu.wohlben.qits.projects.persistence.DeskRunnerRepository;
import eu.wohlben.qits.projects.persistence.FrontDeskRepository;
import eu.wohlben.qits.projects.persistence.ProjectRepository;
import eu.wohlben.qits.projectsdeskrunner.protocol.DesiredState;
import eu.wohlben.qits.projectsdeskrunner.protocol.DeskEntry;
import eu.wohlben.qits.projectsdeskrunner.protocol.DeskState;
import eu.wohlben.qits.projectsdeskrunner.protocol.Estate;
import eu.wohlben.qits.projectsdeskrunner.protocol.HeldDesk;
import eu.wohlben.qits.projectsdeskrunner.protocol.Inventory;
import eu.wohlben.qits.projectsdeskrunner.protocol.Remove;
import eu.wohlben.qits.projectsdeskrunner.protocol.Take;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.Context;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jboss.logging.Logger;

/**
 * The project desks (qits-767): the {@code front_desk} rows and every verb on them — the doors'
 * ensure, stop, delete and read, placement, the estate a runner is sent, and what a runner's
 * inventory, {@code launchFailed} and {@code removed} say about its desks.
 *
 * <p><b>A project has a desk per {@link DeskRole}</b> (qits-1482): its FRONT desk, and while its
 * delivery loop is enabled a DELIVERY desk that runs the loop alone ({@link #syncDeliveryDesk}).
 * Every verb that is about "the project's desk" — the agent-container doors, the lifecycle hook —
 * means the FRONT desk; every verb a runner or a daemon drives is keyed by the desk id, which for the
 * FRONT desk is the project id, so nothing that addressed the one desk a project used to have
 * changed.
 *
 * <p><b>Placement is pull and compare-and-swap.</b> A runner's {@code reserve} ({@link #take}) tries
 * the oldest queued rows in turn and claims one with {@code update … where runner_id is null}; the
 * one that updates a row answers {@code take}, a lost race tries the next. The server's own count of
 * the runner's wanted desks gates it against the row's slots (qits-624), and the gate is only on new
 * placement: an owned desk whose desired state becomes RUNNING is started on its runner whatever its
 * count. A desk is sticky: only {@link #remove} (the DELETE door, the project's deletion) unplaces
 * it, and a runner owning desks cannot be deleted.
 *
 * <p><b>A runner reserves only while it is told there is a backlog</b> (qits-1110): the runner
 * toolkit's slot ledger sends {@code reserve} when a slot is free <em>and</em> the host's last
 * {@code backlog} was above zero, and a {@code nothing} parks it until the next one. So every
 * committed change that adds a desk to the queue or takes one off it ({@link #placeable}) — desired
 * flipping, a late token, a DELETE, a {@code removed} — tells {@link
 * DeskRunnerRegistry#backlogChanged}; a {@code take} is told by the registry itself.
 *
 * <p><b>The token is minted before the desk can be QUEUED</b>, outside any claim transaction, and
 * stored on the row ({@link #mintIfMissing}); a mint that fails leaves the desk FAILED with {@link
 * #TOKEN_UNAVAILABLE} and the sweep retries it. Stop never revokes it; {@link #remove} does.
 *
 * <p><b>The other tokens</b> are minted by {@link #syncTokens}, and a failed mint of either is a
 * WARN, never a FAILED desk — the sweep retries it. The DELIVERY desk's delivery token (epic
 * qits-1440) is minted while the row holds none and goes with the desk, which goes when the loop is
 * disabled. The FRONT desk's session token (qits-1482) is minted beside its desk token and goes with
 * it; a delivery token a FRONT desk still holds from before qits-1482 is revoked and cleared.
 *
 * <p><b>The bound subjects</b> — each desk's {@code token_subject} — are also held in memory, loaded
 * at boot and kept with every write here, because the control socket's upgrade check and the
 * dial-back route ask on the event loop.
 */
@ApplicationScoped
public class FrontDesks {

  private static final Logger LOG = Logger.getLogger(FrontDesks.class);

  /** The 409 a stop of an ALWAYS_ON desk answers. */
  public static final String FRONT_DESK_ALWAYS_ON = "FRONT_DESK_ALWAYS_ON";

  /** The failure detail of a desk whose token could not be minted. */
  public static final String TOKEN_UNAVAILABLE = "TOKEN_UNAVAILABLE";

  @Inject FrontDeskRepository desks;

  @Inject ProjectRepository projectRows;

  @Inject ProjectService projects;

  @Inject DeskRunnerRepository runnerRows;

  @Inject FrontDeskTokens tokens;

  @Inject FrontDeskDemand demand;

  @Inject FrontDeskSpecs specs;

  @Inject DeskRunnerRegistry registry;

  @Inject AgentDaemonRegistry daemons;

  @Inject AgentTunnels tunnels;

  @Inject FrontDeskEstates estates;

  /** Each desk's bound {@code tok-} subject, by desk id. */
  private final ConcurrentHashMap<String, String> subjects = new ConcurrentHashMap<>();

  /** Each desk's project, by desk id: what a control socket's hello is checked against. */
  private final ConcurrentHashMap<String, String> projectsByDesk = new ConcurrentHashMap<>();

  private volatile boolean subjectsLoaded;

  /** Since when this process has been able to see a runner; a never-seen runner's drop is this. */
  private final Instant bootedAt = Instant.now();

  void onStart(@Observes StartupEvent event) {
    try {
      loadSubjects();
    } catch (RuntimeException e) {
      LOG.warnf("Could not load the front desks' bound subjects at boot: %s", e.getMessage());
    }
  }

  // --- reads -------------------------------------------------------------------------------------

  /**
   * The desk row of {@code deskId}, detached, or empty. A FRONT desk's id is its project id, so this
   * is also "the project's front desk".
   */
  public Optional<FrontDesk> find(String deskId) {
    return Optional.ofNullable(QuarkusTransaction.requiringNew().call(() -> desks.findById(deskId)));
  }

  /** {@code projectId}'s desk of {@code role}, detached, or empty. */
  public Optional<FrontDesk> find(String projectId, DeskRole role) {
    return find(role.deskId(projectId));
  }

  /**
   * The {@code tok-} subject {@code deskId}'s desk token introspects as, or null. Answered from
   * memory; read from the database on a miss only off the event loop.
   */
  public String tokenSubject(String deskId) {
    if (deskId == null) {
      return null;
    }
    String cached = subjects.get(deskId);
    if (cached != null || Context.isOnEventLoopThread()) {
      return cached;
    }
    if (!subjectsLoaded) {
      loadSubjects();
      return subjects.get(deskId);
    }
    FrontDesk row = find(deskId).orElse(null);
    if (row != null && row.tokenSubject != null) {
      remember(row);
      return row.tokenSubject;
    }
    return null;
  }

  /**
   * The project the desk {@code deskId} belongs to, or null when there is no such desk. Answered
   * from memory; read from the database on a miss only off the event loop. What a control socket's
   * {@code hello} is held to (qits-1482): a DELIVERY desk dials {@code
   * /projects/daemon/<projectId>-delivery} and says the real project id.
   */
  public String projectOf(String deskId) {
    if (deskId == null) {
      return null;
    }
    String cached = projectsByDesk.get(deskId);
    if (cached != null || Context.isOnEventLoopThread()) {
      return cached;
    }
    FrontDesk row = find(deskId).orElse(null);
    if (row == null) {
      return null;
    }
    remember(row);
    return row.projectId;
  }

  /**
   * The FRONT desk's session token of {@code projectId} (qits-1482) — its id, value and subject — or
   * empty while it has none. Held for the push that hands it to the daemon over the control socket;
   * nothing puts it into an environment.
   */
  public Optional<FrontDeskTokens.Minted> sessionToken(String projectId) {
    return find(projectId, DeskRole.FRONT)
        .filter(row -> row.sessionTokenValue != null)
        .map(
            row ->
                new FrontDeskTokens.Minted(
                    row.sessionTokenId, row.sessionTokenValue, row.sessionTokenSubject));
  }

  private void loadSubjects() {
    List<FrontDesk> rows = QuarkusTransaction.requiringNew().call(() -> desks.listAll());
    rows.forEach(this::remember);
    subjectsLoaded = true;
  }

  private void remember(FrontDesk row) {
    if (row.projectId != null) {
      projectsByDesk.put(row.deskId(), row.projectId);
    }
    if (row.tokenSubject != null) {
      subjects.put(row.deskId(), row.tokenSubject);
    }
  }

  private void forgetDesk(String deskId) {
    subjects.remove(deskId);
    projectsByDesk.remove(deskId);
  }

  // --- the doors ---------------------------------------------------------------------------------

  /**
   * {@code POST …/agent-container/ensure}: the row (and its token) if absent, demand stamped, the
   * desired state recomputed, the runner told when it moved; answers the desk as it stands.
   */
  public FrontDeskState ensure(String projectId) {
    project(projectId);
    Instant now = Instant.now();
    ensureRow(projectId);
    demand.stamp(projectId, now);
    checkEdge(projectId);
    reconcileAndPush(projectId, now);
    return status(projectId);
  }

  /**
   * {@code POST …/agent-container/stop}: an ON_DEMAND desk's demand cleared, so it is desired
   * STOPPED; an ALWAYS_ON desk is a 409 {@link #FRONT_DESK_ALWAYS_ON}. The token is kept.
   */
  public FrontDeskState stop(String projectId) {
    Project project = project(projectId);
    if (project.frontDeskLifecycle == FrontDeskLifecycle.ALWAYS_ON) {
      throw new CodedRefusalException(
          409,
          FRONT_DESK_ALWAYS_ON,
          FRONT_DESK_ALWAYS_ON
              + ": project "
              + projectId
              + " declares front_desk.lifecycle ALWAYS_ON in its project.yml, so its desk is kept"
              + " up; change the declaration to stop it");
    }
    if (find(projectId).isEmpty()) {
      return FrontDeskState.absent(project.frontDeskLifecycle);
    }
    demand.clear(projectId);
    reconcileAndPush(projectId, Instant.now());
    return status(projectId);
  }

  /**
   * {@code DELETE …/agent-container}: 404 for an unknown project, else the project's FRONT desk
   * removed — see {@link #removeDesk}. Idempotent.
   */
  public void remove(String projectId) {
    project(projectId);
    removeDesk(DeskRole.FRONT.deskId(projectId));
  }

  /** Every desk of {@code projectId} removed: the project deletion's path. */
  public void removeProjectDesks(String projectId) {
    List<FrontDesk> rows =
        QuarkusTransaction.requiringNew().call(() -> desks.allOfProject(projectId));
    for (FrontDesk row : rows) {
      removeDesk(row.deskId());
    }
  }

  /**
   * Remove a desk whole: the row deleted (which unplaces it), {@code remove} sent to the runner that
   * held it (container <b>and</b> volume) and a fresh estate after it, its tokens revoked, the tunnel
   * and the daemon's in-memory state forgotten. A desk still wanted (ALWAYS_ON) is created afresh,
   * with a new token, and QUEUED by the next sweep.
   */
  public void removeDesk(String deskId) {
    FrontDesk gone =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  FrontDesk row = desks.findById(deskId);
                  if (row != null) {
                    desks.delete(row);
                  }
                  return row;
                });
    forgetDesk(deskId);
    if (gone == null) {
      return;
    }
    if (placeable(gone)) {
      registry.backlogChanged();
    }
    if (gone.runnerId != null) {
      boolean told = registry.send(gone.runnerId, new Remove(deskId));
      LOG.infof(
          "The %s desk %s was removed from runner %s%s",
          gone.role(),
          deskId,
          gone.runnerId,
          told ? "" : " (not connected: its next estate drops the desk)");
      estates.push(gone.runnerId);
    }
    revokeAll(gone);
    tunnels.closeTunnel(deskId);
    daemons.forget(deskId);
  }

  /** Revoke every token a deleted row held; a failure is a WARN the token reconcile follows up. */
  private void revokeAll(FrontDesk gone) {
    String deskId = gone.deskId();
    if (gone.tokenId != null && !tokens.revoke(gone.tokenId)) {
      LOG.warnf("Could not revoke the desk token of desk %s; the token reconcile reaps it", deskId);
    }
    if (gone.deliveryTokenId != null && !tokens.revokeDelivery(gone.deliveryTokenId)) {
      LOG.warnf(
          "Could not revoke the delivery token of desk %s; the token reconcile reaps it", deskId);
    }
    if (gone.sessionTokenId != null && !tokens.revokeSession(gone.sessionTokenId)) {
      LOG.warnf(
          "Could not revoke the session token of desk %s; the token reconcile reaps it", deskId);
    }
  }

  /** {@code GET …/agent-container}. */
  public FrontDeskState status(String projectId) {
    Project project = project(projectId);
    return QuarkusTransaction.requiringNew()
        .call(() -> state(project, desks.findById(projectId), Instant.now()));
  }

  /** The project, 404 for an unknown one, 409 for one with no slug and so no wrapper to serve. */
  private Project project(String projectId) {
    Project project = projects.get(projectId);
    if (project.slug == null || project.slug.isBlank()) {
      throw new DomainException(
          409,
          "Project "
              + project.id
              + " has no slug, so it has no wrapper repository and no front desk to serve one.");
    }
    return project;
  }

  // --- rows and tokens ---------------------------------------------------------------------------

  /** The project's FRONT desk row, created if absent, holding its tokens if they could be minted. */
  public void ensureRow(String projectId) {
    ensureDesk(projectId, DeskRole.FRONT);
  }

  /**
   * {@code projectId}'s desk of {@code role}, created if absent, holding its token if one could be
   * minted — and the other tokens of its role ({@link #syncTokens}).
   */
  public void ensureDesk(String projectId, DeskRole role) {
    String deskId = role.deskId(projectId);
    if (find(deskId).isEmpty()) {
      try {
        QuarkusTransaction.requiringNew()
            .run(
                () -> {
                  if (desks.findById(deskId) == null) {
                    FrontDesk row = FrontDesk.of(projectId, role);
                    row.desired = FrontDeskDesired.STOPPED;
                    row.createdAt = Instant.now();
                    desks.persist(row);
                  }
                });
        projectsByDesk.put(deskId, projectId);
        LOG.infof("Created the %s desk of project %s", role, projectId);
      } catch (RuntimeException raced) {
        if (find(deskId).isEmpty()) {
          throw raced;
        }
      }
    }
    mintIfMissing(deskId);
    syncTokens(deskId);
  }

  /**
   * Bring the project's DELIVERY desk in line with its {@code delivery_enabled} (qits-1482): enabled,
   * the desk is created with its tokens, the edge belt applied, its desired state recomputed and its
   * runner told; disabled, a DELIVERY desk that exists is stopped, then removed with its tokens
   * revoked ({@link #retireDesk}). A project with no slug has no wrapper to serve and gets no desk.
   */
  public void syncDeliveryDesk(String projectId) {
    Project project = QuarkusTransaction.requiringNew().call(() -> projectRows.findById(projectId));
    String deskId = DeskRole.DELIVERY.deskId(projectId);
    if (project != null
        && project.deliveryEnabled
        && project.slug != null
        && !project.slug.isBlank()) {
      ensureDesk(projectId, DeskRole.DELIVERY);
      checkEdge(deskId);
      reconcileAndPush(deskId, Instant.now());
    } else if (find(deskId).isPresent()) {
      retireDesk(deskId);
    }
  }

  /**
   * A desk nothing wants any more, gone for good: desired STOPPED and its runner told, then removed
   * whole ({@link #removeDesk}) — container, volume, row and tokens. The DELIVERY desk's way out when
   * its project's loop is disabled.
   */
  public void retireDesk(String deskId) {
    UUID runner =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  FrontDesk row = desks.findById(deskId);
                  if (row == null) {
                    return null;
                  }
                  row.desired = FrontDeskDesired.STOPPED;
                  row.queuedAt = null;
                  return row.runnerId;
                });
    if (runner != null) {
      estates.push(runner);
    }
    LOG.infof("Retiring desk %s: nothing wants it any more", deskId);
    removeDesk(deskId);
  }

  /**
   * Bring the desk's other tokens in line with its role, each minted outside any transaction and
   * stored only on a row that still holds none (a lost race, or a row deleted meanwhile, revokes what
   * was minted). A desk with no row is left alone; a failed mint or revoke is a WARN — the sweep calls
   * this again, and the token reconcile reaps what a failed revoke left.
   *
   * <ul>
   *   <li>a DELIVERY desk holds the delivery token (epic qits-1440) while its project's loop is on;
   *   <li>a FRONT desk holds its session token (qits-1482), and never a delivery token: one left from
   *       before qits-1482 is cleared from the row and revoked.
   * </ul>
   */
  public void syncTokens(String deskId) {
    FrontDesk row = find(deskId).orElse(null);
    if (row == null) {
      return;
    }
    if (row.role() == DeskRole.DELIVERY) {
      Project project =
          QuarkusTransaction.requiringNew().call(() -> projectRows.findById(row.projectId));
      if (project != null && project.deliveryEnabled && row.deliveryTokenId == null) {
        mintDelivery(row);
      }
      return;
    }
    if (row.deliveryTokenId != null) {
      clearDelivery(row);
    }
    if (row.sessionTokenId == null) {
      mintSession(row);
    }
  }

  private void mintDelivery(FrontDesk row) {
    String deskId = row.deskId();
    FrontDeskTokens.Minted minted;
    try {
      minted = tokens.mintDelivery(row.projectId);
    } catch (RuntimeException e) {
      LOG.warnf("Could not mint the delivery token of desk %s: %s", deskId, e.getMessage());
      return;
    }
    int stored =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    desks.update(
                        "deliveryTokenId = ?1, deliveryTokenValue = ?2, deliveryTokenSubject = ?3"
                            + " where deskId = ?4 and deliveryTokenId is null",
                        minted.tokenId(),
                        minted.token(),
                        minted.subject(),
                        deskId));
    if (stored == 0) {
      tokens.revokeDelivery(minted.tokenId());
      return;
    }
    LOG.infof("Minted the delivery token of desk %s", deskId);
  }

  /** A FRONT desk's delivery token from before qits-1482: cleared, then revoked. */
  private void clearDelivery(FrontDesk row) {
    String deskId = row.deskId();
    String tokenId = row.deliveryTokenId;
    int cleared =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    desks.update(
                        "deliveryTokenId = null, deliveryTokenValue = null,"
                            + " deliveryTokenSubject = null"
                            + " where deskId = ?1 and deliveryTokenId = ?2",
                        deskId,
                        tokenId));
    if (cleared == 0) {
      return;
    }
    if (!tokens.revokeDelivery(tokenId)) {
      LOG.warnf(
          "Could not revoke the delivery token front desk %s held; the token reconcile reaps it",
          deskId);
    } else {
      LOG.infof(
          "Revoked the delivery token front desk %s held: the loop has a desk of its own", deskId);
    }
  }

  private void mintSession(FrontDesk row) {
    String deskId = row.deskId();
    FrontDeskTokens.Minted minted;
    try {
      minted = tokens.mintSession(row.projectId);
    } catch (RuntimeException e) {
      LOG.warnf("Could not mint the session token of desk %s: %s", deskId, e.getMessage());
      return;
    }
    int stored =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    desks.update(
                        "sessionTokenId = ?1, sessionTokenValue = ?2, sessionTokenSubject = ?3"
                            + " where deskId = ?4 and sessionTokenId is null",
                        minted.tokenId(),
                        minted.token(),
                        minted.subject(),
                        deskId));
    if (stored == 0) {
      tokens.revokeSession(minted.tokenId());
      return;
    }
    LOG.infof("Minted the session token of desk %s", deskId);
  }

  /**
   * Mint the desk's token when it has none: outside any transaction, stored only on a row that still
   * has none (a lost race, or a row deleted meanwhile, revokes what was minted).
   */
  public void mintIfMissing(String deskId) {
    FrontDesk row = find(deskId).orElse(null);
    if (row == null || row.tokenId != null) {
      return;
    }
    FrontDeskTokens.Minted minted;
    try {
      minted = tokens.mint(deskId, row.projectId);
    } catch (RuntimeException e) {
      LOG.warnf("Could not mint the desk token of desk %s: %s", deskId, e.getMessage());
      recordFailure(deskId, TOKEN_UNAVAILABLE + ": " + e.getMessage());
      return;
    }
    int stored =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    desks.update(
                        "tokenId = ?1, tokenValue = ?2, tokenSubject = ?3"
                            + " where deskId = ?4 and tokenId is null",
                        minted.tokenId(),
                        minted.token(),
                        minted.subject(),
                        deskId));
    if (stored == 0) {
      tokens.revoke(minted.tokenId());
      return;
    }
    subjects.put(deskId, minted.subject());
    projectsByDesk.put(deskId, row.projectId);
    clearFailure(deskId, TOKEN_UNAVAILABLE);
    if (find(deskId).filter(FrontDesks::placeable).isPresent()) {
      // A wanted unplaced desk whose token came late has only now joined the queue.
      registry.backlogChanged();
    }
  }

  /** Record why a desk is not usable. */
  void recordFailure(String deskId, String detail) {
    QuarkusTransaction.requiringNew()
        .run(() -> desks.update("failureDetail = ?1 where deskId = ?2", detail, deskId));
  }

  /** Clear a failure that starts with {@code prefix}, leaving any other. */
  void clearFailure(String deskId, String prefix) {
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                desks.update(
                    "failureDetail = null where deskId = ?1 and failureDetail like ?2",
                    deskId,
                    prefix + "%"));
  }

  /**
   * The edge belt, applied to the row: an unconfigured domain makes it FAILED with {@link
   * FrontDeskSpecs#EDGE_PLANE_UNCONFIGURED}; a configured one clears that.
   */
  void checkEdge(String deskId) {
    if (specs.imageOrNull() == null) {
      recordFailure(deskId, FrontDeskSpecs.EDGE_PLANE_UNCONFIGURED);
    } else {
      clearFailure(deskId, FrontDeskSpecs.EDGE_PLANE_UNCONFIGURED);
    }
  }

  // --- desired state -----------------------------------------------------------------------------

  /**
   * Recompute the desk's desired state; a wanted unplaced desk is stamped {@code queued_at} if it
   * has none (once it holds its token), an unwanted unplaced one loses it. Answers the runner to
   * tell when a placed desk's desired state moved, else null.
   */
  public UUID reconcile(String deskId, Instant now) {
    Reconciled reconciled = reconcileRow(deskId, now);
    if (reconciled.queueMoved()) {
      registry.backlogChanged();
    }
    return reconciled.runner();
  }

  /** What one reconcile committed: the runner to tell, and whether the desk joined or left the queue. */
  private record Reconciled(UUID runner, boolean queueMoved) {}

  private Reconciled reconcileRow(String deskId, Instant now) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              FrontDesk row = desks.findById(deskId);
              Project project = row == null ? null : projectRows.findById(row.projectId);
              if (row == null || project == null) {
                return new Reconciled(null, false);
              }
              boolean wasPlaceable = placeable(row);
              FrontDeskDesired next = demand.desired(project, row, now);
              boolean moved = row.desired != next;
              row.desired = next;
              if (row.runnerId == null) {
                if (next == FrontDeskDesired.RUNNING) {
                  if (row.queuedAt == null && row.tokenValue != null) {
                    row.queuedAt = now;
                  }
                } else {
                  row.queuedAt = null;
                }
              }
              if (moved) {
                LOG.infof("The %s desk %s is now desired %s", row.role(), deskId, next);
              }
              return new Reconciled(moved ? row.runnerId : null, wasPlaceable != placeable(row));
            });
  }

  /**
   * Whether a desk is waiting for a runner — wanted, unplaced and holding its token: the predicate
   * of {@link FrontDeskRepository#queued}, which placement tries and the backlog counts.
   */
  static boolean placeable(FrontDesk row) {
    return row.runnerId == null
        && row.desired == FrontDeskDesired.RUNNING
        && row.tokenValue != null;
  }

  /**
   * How many desks are waiting for a runner: the {@code backlog} a greeted runner is told, without
   * which its slot ledger never sends a {@code reserve}.
   */
  public long backlog() {
    return QuarkusTransaction.requiringNew().call(() -> desks.countQueued());
  }

  /** {@link #reconcile}, then the runner's estate when a placed desk moved. */
  public void reconcileAndPush(String deskId, Instant now) {
    UUID runner = reconcile(deskId, now);
    if (runner != null) {
      estates.push(runner);
    }
  }

  // --- placement ---------------------------------------------------------------------------------

  /**
   * A runner's {@code reserve}: a {@link Take} for the oldest queued desk this runner wins the
   * compare-and-swap on, or null for {@code nothing} — also when the server's own count of the
   * runner's wanted desks is at its slots.
   */
  public Take take(UUID runnerId) {
    DeskRunner runner = QuarkusTransaction.requiringNew().call(() -> runnerRows.findById(runnerId));
    if (runner == null) {
      return null;
    }
    long held = QuarkusTransaction.requiringNew().call(() -> desks.wantedOn(runnerId));
    if (held >= runner.slots) {
      return null;
    }
    List<FrontDesk> queued = QuarkusTransaction.requiringNew().call(() -> desks.queued());
    for (FrontDesk candidate : queued) {
      Project project =
          QuarkusTransaction.requiringNew().call(() -> projectRows.findById(candidate.projectId));
      if (project == null) {
        continue;
      }
      FrontDeskSpecs.Composed composed;
      try {
        composed = specs.compose(project, candidate);
      } catch (FrontDeskSpecs.EdgePlaneUnconfigured unconfigured) {
        recordFailure(candidate.deskId(), FrontDeskSpecs.EDGE_PLANE_UNCONFIGURED);
        continue;
      } catch (RuntimeException e) {
        LOG.warnf("Could not compose the desk %s: %s", candidate.deskId(), e.getMessage());
        recordFailure(candidate.deskId(), "Could not compose the desk: " + e.getMessage());
        continue;
      }
      if (claim(candidate.deskId(), runnerId, composed) == 1) {
        LOG.infof(
            "Placed the %s desk %s on runner %s (%s)",
            candidate.role(), candidate.deskId(), runner.name, runnerId);
        return new Take(
            candidate.deskId(), DesiredState.RUNNING, composed.spec(), composed.hash());
      }
    }
    return null;
  }

  /** The compare-and-swap: 1 when this runner placed the desk, 0 when another got there first. */
  int claim(String deskId, UUID runnerId, FrontDeskSpecs.Composed composed) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                desks
                    .getEntityManager()
                    .createNativeQuery(
                        "update front_desk set runner_id = :runner, placed_at = :now,"
                            + " spec_json = cast(:json as jsonb), spec_hash = :hash,"
                            + " failure_detail = null"
                            + " where desk_id = :desk and runner_id is null"
                            + " and desired = 'RUNNING'")
                    .setParameter("runner", runnerId)
                    .setParameter("now", Instant.now())
                    .setParameter("json", composed.json())
                    .setParameter("hash", composed.hash())
                    .setParameter("desk", deskId)
                    .executeUpdate());
  }

  /** Store a rolled spec on a desk still on {@code runnerId}; 1 when it landed. */
  int storeSpec(String deskId, UUID runnerId, FrontDeskSpecs.Composed composed) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                desks
                    .getEntityManager()
                    .createNativeQuery(
                        "update front_desk set spec_json = cast(:json as jsonb), spec_hash = :hash"
                            + " where desk_id = :desk and runner_id = :runner")
                    .setParameter("json", composed.json())
                    .setParameter("hash", composed.hash())
                    .setParameter("desk", deskId)
                    .setParameter("runner", runnerId)
                    .executeUpdate());
  }

  /** The held desks a greeted runner keeps: those placed on it. */
  public List<String> adopted(UUID runnerId, List<String> held) {
    Set<String> owned = new HashSet<>();
    for (FrontDesk row : QuarkusTransaction.requiringNew().call(() -> desks.onRunner(runnerId))) {
      owned.add(row.deskId());
    }
    return held.stream().filter(owned::contains).toList();
  }

  // --- the estate --------------------------------------------------------------------------------

  /**
   * The full estate of {@code runnerId}: every desk placed on it with its desired state and applied
   * spec. A desk placed with no spec yet is composed now (and stored); one that cannot be is left
   * out and named. Throws when the rows cannot be read — an estate is sent only from a good read.
   */
  public Estate estate(UUID runnerId) {
    List<FrontDesk> rows = QuarkusTransaction.requiringNew().call(() -> desks.onRunner(runnerId));
    List<DeskEntry> entries = new ArrayList<>();
    for (FrontDesk row : rows) {
      String json = row.specJson;
      String hash = row.specHash;
      if (json == null || hash == null) {
        Project project =
            QuarkusTransaction.requiringNew().call(() -> projectRows.findById(row.projectId));
        try {
          FrontDeskSpecs.Composed composed = specs.compose(project, row);
          storeSpec(row.deskId(), runnerId, composed);
          json = composed.json();
          hash = composed.hash();
        } catch (RuntimeException e) {
          LOG.warnf(
              "The desk %s on runner %s has no spec to send: %s",
              row.deskId(), runnerId, e.getMessage());
          continue;
        }
      }
      entries.add(
          new DeskEntry(
              row.deskId(),
              row.wanted() ? DesiredState.RUNNING : DesiredState.STOPPED,
              FrontDeskSpecs.parse(json),
              hash));
    }
    return new Estate(entries, specs.imageOrNull());
  }

  // --- what a runner says ------------------------------------------------------------------------

  /**
   * An inventory: each desk placed on the runner takes its reported state, spec hash and the time;
   * a desk the inventory does not name is ABSENT. FAILED records the runner's detail; RUNNING — or
   * STOPPED/ABSENT on a desk desired STOPPED — clears a launch failure.
   */
  public void inventory(UUID runnerId, Inventory inventory) {
    Map<String, HeldDesk> held = new HashMap<>();
    for (HeldDesk desk : inventory.desks()) {
      held.put(desk.projectId(), desk);
    }
    Instant now = Instant.now();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              for (FrontDesk row : desks.onRunner(runnerId)) {
                HeldDesk desk = held.get(row.deskId());
                DeskState state = desk == null ? DeskState.ABSENT : desk.state();
                row.reportedState = state.name();
                row.reportedSpecHash = desk == null ? null : desk.specHash();
                row.reportedAt = now;
                if (state == DeskState.FAILED) {
                  row.failureDetail =
                      desk.detail() == null || desk.detail().isBlank()
                          ? "The runner reported the desk FAILED with no reason."
                          : desk.detail();
                } else if (launchFailure(row.failureDetail)
                    && (state == DeskState.RUNNING || !row.wanted())) {
                  row.failureDetail = null;
                }
              }
            });
  }

  /** A failure the runner reported, as opposed to one this service decided. */
  private static boolean launchFailure(String detail) {
    return detail != null
        && !detail.startsWith(FrontDeskSpecs.EDGE_PLANE_UNCONFIGURED)
        && !detail.startsWith(TOKEN_UNAVAILABLE);
  }

  /**
   * A {@code launchFailed}: the detail on the desk, if it is still this runner's. The wire calls the
   * key {@code projectId}; it is the desk id.
   */
  public void launchFailed(UUID runnerId, String deskId, String detail) {
    String reason =
        detail == null || detail.isBlank() ? "The runner could not launch the desk." : detail;
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                desks.update(
                    "failureDetail = ?1 where deskId = ?2 and runnerId = ?3",
                    reason,
                    deskId,
                    runnerId));
    LOG.warnf("Runner %s could not launch the desk %s: %s", runnerId, deskId, reason);
  }

  /**
   * A {@code removed}: nothing of the desk is on the runner's node. It completes a DELETE: an
   * unplaced row left over from a placement (one a DELETE could not drop) is dropped now and its
   * token revoked. A row created afresh since — never placed — is not touched.
   */
  public void removed(UUID runnerId, String deskId) {
    FrontDesk leftover =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  FrontDesk row = desks.findById(deskId);
                  if (row == null || row.runnerId != null || row.placedAt == null) {
                    return null;
                  }
                  desks.delete(row);
                  return row;
                });
    LOG.infof("Runner %s removed the desk %s", runnerId, deskId);
    if (leftover != null) {
      forgetDesk(deskId);
      revokeAll(leftover);
      if (placeable(leftover)) {
        registry.backlogChanged();
      }
    }
  }

  // --- the computed state ------------------------------------------------------------------------

  /** The desks on a runner, for its listing; inside the caller's transaction. */
  public List<FrontDesk> desksOn(UUID runnerId) {
    return desks.onRunner(runnerId);
  }

  /**
   * A desk's state, from its row, its runner's presence and its daemon; called inside a
   * transaction (it reads the runner's name).
   *
   * <ul>
   *   <li>ABSENT — no row; FAILED — a failure detail (a launch failure, an unconfigured edge, no
   *       token) or a daemon's {@code ProvisionFailed};
   *   <li>UNAVAILABLE — never stored: the owning runner has been disconnected longer than the
   *       reconnect grace;
   *   <li>QUEUED — wanted and unplaced; RUNNING — the inventory says so; PROVISIONING — placed,
   *       wanted, not yet running; STOPPED — otherwise.
   * </ul>
   */
  public FrontDeskState state(Project project, FrontDesk row, Instant now) {
    FrontDeskLifecycle lifecycle =
        project.frontDeskLifecycle == null
            ? FrontDeskLifecycle.ON_DEMAND
            : project.frontDeskLifecycle;
    if (row == null) {
      return FrontDeskState.absent(lifecycle);
    }
    Optional<AgentDaemonRegistry.DaemonInfo> daemon = daemons.lookup(row.deskId());
    boolean connected = daemon.isPresent();
    String version = daemon.map(AgentDaemonRegistry.DaemonInfo::daemonVersion).orElse(null);
    String pinned = specs.imageVersion();
    boolean stale =
        connected && (version == null || version.isBlank() || !version.equals(pinned));
    String provisionFailure = daemons.provisionFailure(row.deskId()).orElse(null);
    AgentRuntimeStatus status;
    String detail = null;
    if (row.failureDetail != null) {
      status = AgentRuntimeStatus.FAILED;
      detail = row.failureDetail;
    } else if (provisionFailure != null) {
      status = AgentRuntimeStatus.FAILED;
      detail = provisionFailure;
    } else if (row.runnerId != null && unavailable(row.runnerId, now)) {
      status = AgentRuntimeStatus.UNAVAILABLE;
    } else if (row.runnerId == null) {
      status = row.wanted() ? AgentRuntimeStatus.QUEUED : AgentRuntimeStatus.STOPPED;
    } else if (DeskState.RUNNING.name().equals(row.reportedState)) {
      status = AgentRuntimeStatus.RUNNING;
    } else if (row.wanted()) {
      status = AgentRuntimeStatus.PROVISIONING;
    } else {
      status = AgentRuntimeStatus.STOPPED;
    }
    String runnerName = null;
    if (row.runnerId != null) {
      DeskRunner runner = runnerRows.findById(row.runnerId);
      runnerName = runner == null ? null : runner.name;
    }
    return new FrontDeskState(
        status,
        connected,
        version,
        pinned,
        stale,
        detail,
        row.runnerId,
        runnerName,
        lifecycle,
        row.queuedAt);
  }

  /** Whether the runner has been gone longer than the reconnect grace. */
  boolean unavailable(UUID runnerId, Instant now) {
    if (registry.isConnected(runnerId)) {
      return false;
    }
    Instant since = registry.disconnectedSince(runnerId);
    if (since == null) {
      since = bootedAt;
    }
    return !since.plus(registry.reconnectGrace()).isAfter(now);
  }
}
