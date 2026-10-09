package eu.wohlben.qits.projects.deskhost;

import eu.wohlben.qits.projects.agenthost.AgentDaemonRegistry;
import eu.wohlben.qits.projects.agenthost.AgentRuntimeStatus;
import eu.wohlben.qits.projects.agenthost.AgentTunnels;
import eu.wohlben.qits.projects.control.ProjectService;
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
 * The project front desks (qits-767): the {@code front_desk} rows and every verb on them — the
 * doors' ensure, stop, delete and read, placement, the estate a runner is sent, and what a runner's
 * inventory, {@code launchFailed} and {@code removed} say about its desks.
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

  /** Each desk's bound {@code tok-} subject, by project. */
  private final ConcurrentHashMap<String, String> subjects = new ConcurrentHashMap<>();

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

  /** The desk row of {@code projectId}, detached, or empty. */
  public Optional<FrontDesk> find(String projectId) {
    return Optional.ofNullable(
        QuarkusTransaction.requiringNew().call(() -> desks.findById(projectId)));
  }

  /**
   * The {@code tok-} subject {@code projectId}'s desk token introspects as, or null. Answered from
   * memory; read from the database on a miss only off the event loop.
   */
  public String tokenSubject(String projectId) {
    if (projectId == null) {
      return null;
    }
    String cached = subjects.get(projectId);
    if (cached != null || Context.isOnEventLoopThread()) {
      return cached;
    }
    if (!subjectsLoaded) {
      loadSubjects();
      return subjects.get(projectId);
    }
    FrontDesk row = find(projectId).orElse(null);
    if (row != null && row.tokenSubject != null) {
      subjects.put(projectId, row.tokenSubject);
      return row.tokenSubject;
    }
    return null;
  }

  private void loadSubjects() {
    List<FrontDesk> rows = QuarkusTransaction.requiringNew().call(() -> desks.listAll());
    for (FrontDesk row : rows) {
      if (row.tokenSubject != null) {
        subjects.put(row.projectId, row.tokenSubject);
      }
    }
    subjectsLoaded = true;
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
   * {@code DELETE …/agent-container}: 404 for an unknown project, else the desk removed — see
   * {@link #removeDesk}. Idempotent.
   */
  public void remove(String projectId) {
    project(projectId);
    removeDesk(projectId);
  }

  /**
   * Remove a desk whole: the row deleted (which unplaces it), {@code remove} sent to the runner that
   * held it (container <b>and</b> volume) and a fresh estate after it, the token revoked, the tunnel
   * and the daemon's in-memory state forgotten. A desk still wanted (ALWAYS_ON) is created afresh,
   * with a new token, and QUEUED by the next sweep. Also the project deletion's path.
   */
  public void removeDesk(String projectId) {
    FrontDesk gone =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  FrontDesk row = desks.findById(projectId);
                  if (row != null) {
                    desks.delete(row);
                  }
                  return row;
                });
    subjects.remove(projectId);
    if (gone == null) {
      return;
    }
    if (placeable(gone)) {
      registry.backlogChanged();
    }
    if (gone.runnerId != null) {
      boolean told = registry.send(gone.runnerId, new Remove(projectId));
      LOG.infof(
          "The front desk of project %s was removed from runner %s%s",
          projectId,
          gone.runnerId,
          told ? "" : " (not connected: its next estate drops the desk)");
      estates.push(gone.runnerId);
    }
    if (gone.tokenId != null && !tokens.revoke(gone.tokenId)) {
      LOG.warnf(
          "Could not revoke the desk token of project %s; the token reconcile reaps it", projectId);
    }
    tunnels.closeTunnel(projectId);
    daemons.forget(projectId);
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

  /** The row of {@code projectId}, created if absent, holding its token if one could be minted. */
  public void ensureRow(String projectId) {
    if (find(projectId).isEmpty()) {
      try {
        QuarkusTransaction.requiringNew()
            .run(
                () -> {
                  if (desks.findById(projectId) == null) {
                    FrontDesk row = new FrontDesk();
                    row.projectId = projectId;
                    row.desired = FrontDeskDesired.STOPPED;
                    row.createdAt = Instant.now();
                    desks.persist(row);
                  }
                });
        LOG.infof("Created the front desk of project %s", projectId);
      } catch (RuntimeException raced) {
        if (find(projectId).isEmpty()) {
          throw raced;
        }
      }
    }
    mintIfMissing(projectId);
  }

  /**
   * Mint the desk's token when it has none: outside any transaction, stored only on a row that still
   * has none (a lost race, or a row deleted meanwhile, revokes what was minted).
   */
  public void mintIfMissing(String projectId) {
    FrontDesk row = find(projectId).orElse(null);
    if (row == null || row.tokenId != null) {
      return;
    }
    FrontDeskTokens.Minted minted;
    try {
      minted = tokens.mint(projectId);
    } catch (RuntimeException e) {
      LOG.warnf("Could not mint the desk token of project %s: %s", projectId, e.getMessage());
      recordFailure(projectId, TOKEN_UNAVAILABLE + ": " + e.getMessage());
      return;
    }
    int stored =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    desks.update(
                        "tokenId = ?1, tokenValue = ?2, tokenSubject = ?3"
                            + " where projectId = ?4 and tokenId is null",
                        minted.tokenId(),
                        minted.token(),
                        minted.subject(),
                        projectId));
    if (stored == 0) {
      tokens.revoke(minted.tokenId());
      return;
    }
    subjects.put(projectId, minted.subject());
    clearFailure(projectId, TOKEN_UNAVAILABLE);
    if (find(projectId).filter(FrontDesks::placeable).isPresent()) {
      // A wanted unplaced desk whose token came late has only now joined the queue.
      registry.backlogChanged();
    }
  }

  /** Record why a desk is not usable. */
  void recordFailure(String projectId, String detail) {
    QuarkusTransaction.requiringNew()
        .run(() -> desks.update("failureDetail = ?1 where projectId = ?2", detail, projectId));
  }

  /** Clear a failure that starts with {@code prefix}, leaving any other. */
  void clearFailure(String projectId, String prefix) {
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                desks.update(
                    "failureDetail = null where projectId = ?1 and failureDetail like ?2",
                    projectId,
                    prefix + "%"));
  }

  /**
   * The edge belt, applied to the row: an unconfigured domain makes it FAILED with {@link
   * FrontDeskSpecs#EDGE_PLANE_UNCONFIGURED}; a configured one clears that.
   */
  void checkEdge(String projectId) {
    if (specs.imageOrNull() == null) {
      recordFailure(projectId, FrontDeskSpecs.EDGE_PLANE_UNCONFIGURED);
    } else {
      clearFailure(projectId, FrontDeskSpecs.EDGE_PLANE_UNCONFIGURED);
    }
  }

  // --- desired state -----------------------------------------------------------------------------

  /**
   * Recompute the desk's desired state; a wanted unplaced desk is stamped {@code queued_at} if it
   * has none (once it holds its token), an unwanted unplaced one loses it. Answers the runner to
   * tell when a placed desk's desired state moved, else null.
   */
  public UUID reconcile(String projectId, Instant now) {
    Reconciled reconciled = reconcileRow(projectId, now);
    if (reconciled.queueMoved()) {
      registry.backlogChanged();
    }
    return reconciled.runner();
  }

  /** What one reconcile committed: the runner to tell, and whether the desk joined or left the queue. */
  private record Reconciled(UUID runner, boolean queueMoved) {}

  private Reconciled reconcileRow(String projectId, Instant now) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              FrontDesk row = desks.findById(projectId);
              Project project = row == null ? null : projectRows.findById(projectId);
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
                LOG.infof("The front desk of project %s is now desired %s", projectId, next);
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
  public void reconcileAndPush(String projectId, Instant now) {
    UUID runner = reconcile(projectId, now);
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
        recordFailure(candidate.projectId, FrontDeskSpecs.EDGE_PLANE_UNCONFIGURED);
        continue;
      } catch (RuntimeException e) {
        LOG.warnf(
            "Could not compose the front desk of project %s: %s", candidate.projectId, e.getMessage());
        recordFailure(candidate.projectId, "Could not compose the desk: " + e.getMessage());
        continue;
      }
      if (claim(candidate.projectId, runnerId, composed) == 1) {
        LOG.infof(
            "Placed the front desk of project %s on runner %s (%s)",
            candidate.projectId, runner.name, runnerId);
        return new Take(
            candidate.projectId, DesiredState.RUNNING, composed.spec(), composed.hash());
      }
    }
    return null;
  }

  /** The compare-and-swap: 1 when this runner placed the desk, 0 when another got there first. */
  int claim(String projectId, UUID runnerId, FrontDeskSpecs.Composed composed) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                desks
                    .getEntityManager()
                    .createNativeQuery(
                        "update front_desk set runner_id = :runner, placed_at = :now,"
                            + " spec_json = cast(:json as jsonb), spec_hash = :hash,"
                            + " failure_detail = null"
                            + " where project_id = :project and runner_id is null"
                            + " and desired = 'RUNNING'")
                    .setParameter("runner", runnerId)
                    .setParameter("now", Instant.now())
                    .setParameter("json", composed.json())
                    .setParameter("hash", composed.hash())
                    .setParameter("project", projectId)
                    .executeUpdate());
  }

  /** Store a rolled spec on a desk still on {@code runnerId}; 1 when it landed. */
  int storeSpec(String projectId, UUID runnerId, FrontDeskSpecs.Composed composed) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                desks
                    .getEntityManager()
                    .createNativeQuery(
                        "update front_desk set spec_json = cast(:json as jsonb), spec_hash = :hash"
                            + " where project_id = :project and runner_id = :runner")
                    .setParameter("json", composed.json())
                    .setParameter("hash", composed.hash())
                    .setParameter("project", projectId)
                    .setParameter("runner", runnerId)
                    .executeUpdate());
  }

  /** The held desks a greeted runner keeps: those placed on it. */
  public List<String> adopted(UUID runnerId, List<String> held) {
    Set<String> owned = new HashSet<>();
    for (FrontDesk row : QuarkusTransaction.requiringNew().call(() -> desks.onRunner(runnerId))) {
      owned.add(row.projectId);
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
          storeSpec(row.projectId, runnerId, composed);
          json = composed.json();
          hash = composed.hash();
        } catch (RuntimeException e) {
          LOG.warnf(
              "The front desk of project %s on runner %s has no spec to send: %s",
              row.projectId, runnerId, e.getMessage());
          continue;
        }
      }
      entries.add(
          new DeskEntry(
              row.projectId,
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
                HeldDesk desk = held.get(row.projectId);
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

  /** A {@code launchFailed}: the detail on the desk, if it is still this runner's. */
  public void launchFailed(UUID runnerId, String projectId, String detail) {
    String reason =
        detail == null || detail.isBlank() ? "The runner could not launch the desk." : detail;
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                desks.update(
                    "failureDetail = ?1 where projectId = ?2 and runnerId = ?3",
                    reason,
                    projectId,
                    runnerId));
    LOG.warnf("Runner %s could not launch the front desk of project %s: %s", runnerId, projectId, reason);
  }

  /**
   * A {@code removed}: nothing of the desk is on the runner's node. It completes a DELETE: an
   * unplaced row left over from a placement (one a DELETE could not drop) is dropped now and its
   * token revoked. A row created afresh since — never placed — is not touched.
   */
  public void removed(UUID runnerId, String projectId) {
    FrontDesk leftover =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  FrontDesk row = desks.findById(projectId);
                  if (row == null || row.runnerId != null || row.placedAt == null) {
                    return null;
                  }
                  desks.delete(row);
                  return row;
                });
    LOG.infof("Runner %s removed the front desk of project %s", runnerId, projectId);
    if (leftover != null) {
      subjects.remove(projectId);
      if (leftover.tokenId != null) {
        tokens.revoke(leftover.tokenId);
      }
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
    Optional<AgentDaemonRegistry.DaemonInfo> daemon = daemons.lookup(project.id);
    boolean connected = daemon.isPresent();
    String version = daemon.map(AgentDaemonRegistry.DaemonInfo::daemonVersion).orElse(null);
    String pinned = specs.imageVersion();
    boolean stale =
        connected && (version == null || version.isBlank() || !version.equals(pinned));
    String provisionFailure = daemons.provisionFailure(project.id).orElse(null);
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
