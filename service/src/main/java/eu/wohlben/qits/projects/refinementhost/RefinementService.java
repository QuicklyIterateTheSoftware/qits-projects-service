package eu.wohlben.qits.projects.refinementhost;

import eu.wohlben.qits.entities.control.EntityDispatchService;
import eu.wohlben.qits.entities.control.EntityStateMachine;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.EntityStatus;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.projects.api.QualifiedEntityIds;
import eu.wohlben.qits.projects.control.GitMirrorRegistry;
import eu.wohlben.qits.projects.control.ProjectService;
import eu.wohlben.qits.projects.control.RepositoryService;
import eu.wohlben.qits.projects.control.TechnicalProcess;
import eu.wohlben.qits.projects.control.WorkspaceAgentDispatch;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.Refinement;
import eu.wohlben.qits.projects.entity.Repository;
import eu.wohlben.qits.projects.error.CodedRefusalException;
import eu.wohlben.qits.projects.error.DomainException;
import eu.wohlben.qits.projects.error.NotFoundException;
import eu.wohlben.qits.projects.gitmirror.GitMirrorException;
import eu.wohlben.qits.projects.gitmirror.PushOutcome;
import eu.wohlben.qits.projects.gitmirror.RepoMirror;
import eu.wohlben.qits.projects.persistence.RefinementRepository;
import eu.wohlben.qits.projects.control.TechnicalProcessRegistry;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The refinement lifecycle: find-or-create keyed by entity (an epic, a ticket or a campaign), the
 * session's demand (ensure, stop), discard, and the row projection the status strip reads.
 *
 * <h2>Two kinds of room (epic qits-770)</h2>
 *
 * <p><b>A room opened now is a session room</b>: one more session of the project's front desk, keyed
 * by the entity id, wanted while its {@code last_demand_at} is inside {@code
 * qits.projects.agent-idle-timeout} — see {@link RefinementSessions}. It cuts no branch, places no
 * container and commissions no client. <b>A room opened before that is a legacy container room</b>
 * ({@link Refinement#containerRoom()}: it has a {@code refining/<slug>} branch) and keeps the
 * container ladder below until qits-1316 removes it; every door below answers both kinds, by that
 * rule.
 *
 * <p>What follows describes the legacy container rooms. The projects-side
 * replacement for what the refining route used to take from qits-workspaces' {@code
 * WorkspaceService}, narrowed to what that route actually consumes.
 *
 * <h2>The ladder</h2>
 *
 * <ol>
 *   <li><b>Running</b> — no-op plus an idle-clock stamp; the process narration says so and settles.
 *   <li><b>Present but stopped</b> — wake: one {@code ensure} under {@code Recreate.ifChanged}, a
 *       start in place unless an image bump landed while it slept.
 *   <li><b>Absent</b> — provision: commission a credential, ensure a fresh container; the daemon
 *       self-clones the wrapper and its terminal {@code Provisioned}/{@code ProvisionFailed} is
 *       what settles the narration, routed by {@link RefinementDaemonRegistry}.
 * </ol>
 *
 * <p>The container work runs <b>off the request thread</b>: an ensure can sit behind an image pull,
 * and the browser gets the technical-process id to watch instead of a request that hangs. One lock
 * per row serializes the ladder; a second click while it is held answers the live process.
 *
 * <h2>The branch</h2>
 *
 * <p>{@code refining/<slug>}, cut on the project's wrapper at its default branch — a refinement
 * always forks the wrapper's main, which is why the parent/child workspace tree and the integrate
 * door do not exist here. The wrapper is the project, and that holds for a ticket exactly as for an
 * epic: neither names a repository, and both dispatch onto the wrapper too. The slug is the entity's,
 * and a root entity's slug is unique per project across archetypes, so an epic and a ticket never
 * cut the same branch. Adopt-existing is the create's ordinary path, not an error dance: a branch
 * already on the origin (a previous refinement of this entity, discarded row and all) is adopted as
 * it stands.
 */
@ApplicationScoped
public class RefinementService {

  private static final Logger LOG = Logger.getLogger(RefinementService.class);

  /** Reads an entity of any archetype — the open is about epics and tickets alike (qits-395). */
  @Inject EntityDispatchService entities;

  /**
   * Optional, like every port here, and read for one thing: whether an agent is already working the
   * entity a room is being opened on. See {@link #findOrCreate} for what its never-throw contract
   * means for that check.
   */
  @Inject Instance<WorkspaceAgentDispatch> workspaces;
  @Inject ProjectService projects;
  @Inject RepositoryService repositories;
  @Inject GitMirrorRegistry mirrors;
  @Inject RefinementRepository store;
  @Inject RefinementRuntime runtime;
  @Inject RefinementDaemonRegistry registry;
  @Inject RefinementTunnels tunnels;
  @Inject RefinementCommissions commissions;

  /** The room's own token (qits-1482): minted at the open, revoked by the discard. */
  @Inject RefinementTokens tokens;

  /** A session room's desired list and its report (epic qits-770). */
  @Inject RefinementSessions sessions;

  /**
   * Told whenever a session room's demand moves (epic qits-770), so the front desk is created and
   * its desired state recomputed. Null in a hand-built service; a fire that fails is a WARN.
   */
  @Inject Event<RefinementDemandChanged> demandChanged;

  /** The project's front desk, which a session room's answer reports beside its own state. */
  @Inject Instance<RefinementDesk> desks;

  /**
   * How many session rooms of one project may want their session at once (epic qits-770): the open
   * of one more, or the ensure of a stopped one, is a 409 naming the running ones.
   */
  @ConfigProperty(name = "qits.projects.refinement-sessions-max", defaultValue = "4")
  int sessionsMax;

  /** The 409 code of an open or ensure beyond {@link #sessionsMax}. */
  public static final String REFINEMENT_SESSIONS_FULL = "REFINEMENT_SESSIONS_FULL";

  /**
   * Told whenever a room's token or row comes or goes (qits-1482), so the front desk re-sends its
   * daemon the session credentials. Null in a hand-built service; a fire that fails is a WARN.
   */
  @Inject Event<RefinementCredentialsChanged> credentialsChanged;
  @Inject TechnicalProcessRegistry processes;
  @Inject RefinementChangePublisher changes;
  @Inject RefinementDrift drift;

  /**
   * Read for one thing only: {@link RefinementContainerFactory#imageVersion()}, the image pin the
   * projection reports beside the connected daemon's own version. The factory is asked rather than
   * the key being read a second time here, for the reason that method's javadoc gives — {@code GET
   * /projects/api/pins}, every container start and this projection have to give one answer.
   */
  @Inject RefinementContainerFactory factory;

  /**
   * One permit per refinement, so the ladder is serialized without serializing the service. A
   * {@link Semaphore} and deliberately not a lock: the permit is taken on the request thread and
   * released on the executor thread that finishes the work, and a lock's owner check would refuse
   * exactly that hand-over.
   */
  private final ConcurrentHashMap<Long, Semaphore> locks = new ConcurrentHashMap<>();

  /** The last ensure's failure, shown as {@code runtimeError} until the next attempt. */
  private final ConcurrentHashMap<Long, String> lastErrors = new ConcurrentHashMap<>();

  /** The live ensure/recreate narration per row, answered by the active-process read. */
  private final ConcurrentHashMap<Long, TechnicalProcess> activeProcesses =
      new ConcurrentHashMap<>();

  private final ExecutorService executor =
      Executors.newCachedThreadPool(
          runnable -> {
            Thread thread = new Thread(runnable, "refinement-ensure");
            thread.setDaemon(true);
            return thread;
          });

  @PreDestroy
  void shutdown() {
    executor.shutdownNow();
  }

  // ---- find or create ----------------------------------------------------------------------

  /**
   * The refinement of {@code entityId} — an epic, a ticket or a campaign — created if it has none,
   * and its session asked for. Creation needs the entity itself — its archetype and status gate the
   * open — so an unknown id 404s here and nothing is half-made.
   *
   * <p><b>The refusals, in order, and all of them on the create path only</b> (qits-395, qits-770):
   *
   * <ol>
   *   <li><b>A piece of a plan</b> — a feature or a task is a <b>409</b>. It holds a status since
   *       qits-763, but no phase runs on it, and its scope is its epic's: the room to open is the
   *       epic's. A campaign is refined like an epic (epic qits-770): its room's scope is the
   *       campaign and its REPORTED members.
   *   <li><b>Not REPORTED</b> — a <b>409</b> naming the status. Refinement is the REPORTED phase and
   *       scope is editable only there ({@code EntityLifecycle.requireReported}), for every
   *       archetype; a room on a frozen scope would be a room whose every write is refused.
   *   <li><b>A dispatch is running on it</b> — a <b>409</b>. An ACTIVE qits-workspaces agent naming
   *       this entity means an agent is (or may be) working it, and at REPORTED that agent is
   *       <em>refining</em> it — writing the very description, tree and dossier a person in the room
   *       would be writing. Read through {@link WorkspaceAgentDispatch#agentsFor}, whose contract is
   *       <b>never throw, answer empty on failure</b> — so the check <b>fails open</b>.
   *   <li><b>No wrapper</b> — a <b>409</b>: the desk the session runs in clones the wrapper.
   *   <li><b>Beyond the cap</b> — a <b>409</b> {@value #REFINEMENT_SESSIONS_FULL} while {@code
   *       qits.projects.refinement-sessions-max} of the project's rooms already want their session,
   *       naming them.
   * </ol>
   *
   * <p><b>An existing room is always answered, whatever the entity's state now.</b> One room per
   * entity (the unique key on {@code entity_id}); a second open returns the first, and asks for its
   * session again — unless the project is at the cap, when the room is answered as it stands and
   * its ensure door says why. A legacy container room is answered untouched.
   *
   * <p>The room's token is minted before its demand is stamped and pushed, so the credentials frame
   * carrying it goes out before the sessions entry that runs on it.
   */
  public Refinement findOrCreate(String entityId) {
    Optional<Refinement> existing =
        QuarkusTransaction.requiringNew().call(() -> store.findByEntity(entityId));
    if (existing.isPresent()) {
      // A room opened while qits-idp was away holds no token yet; the open is a cheap retry.
      Refinement room = mintTokenIfMissing(existing.get());
      if (room.containerRoom()) {
        return room;
      }
      Instant now = Instant.now();
      if (sessions.wantsRunning(room, now) || runningBeside(room, now).size() < sessionsMax) {
        return stampDemand(room, now);
      }
      return room;
    }
    WorkEntity entity = entities.get(entityId); // 404, of any archetype
    String noun = nounOf(entity);
    if (entity.archetype != Archetype.EPIC
        && entity.archetype != Archetype.TICKET
        && entity.archetype != Archetype.CAMPAIGN) {
      throw new DomainException(
          409,
          "A "
              + noun
              + " runs no phase of its own, so it is not refined on its own — only an epic, a"
              + " ticket or a campaign opens a refinement. Refine the epic it belongs to.");
    }
    // The merged row stores the status word, so REPORTED is compared by name against the column.
    if (!EntityStatus.REPORTED.name().equals(entity.status)) {
      throw new DomainException(
          409,
          capitalized(noun)
              + " "
              + entityId
              + " is "
              + entity.status
              + " — only a REPORTED "
              + noun
              + " can be refined. "
              + (EntityStateMachine.isTerminal(entity.status)
                  ? EntityStateMachine.finality(EntityStatus.valueOf(entity.status)) + "."
                  : "Move it back to REPORTED to reopen its scope."));
    }
    refuseWhileDispatched(entity, noun);
    Project project = projects.get(entity.projectId);
    Repository wrapper = wrapperOf(project);
    Instant now = Instant.now();
    refuseBeyondCap(project, null, now);

    Refinement refinement = new Refinement();
    refinement.entityId = entity.id;
    refinement.projectId = project.id;
    refinement.repositoryId = wrapper.id;
    refinement.label = label(entity.slug);
    refinement.createdAt = now;
    refinement.lastDemandAt = now;
    try {
      QuarkusTransaction.requiringNew().run(() -> store.persist(refinement));
    } catch (RuntimeException maybeRace) {
      // Two opens racing: the unique constraint on entity_id decides, and the loser adopts.
      Optional<Refinement> won =
          QuarkusTransaction.requiringNew().call(() -> store.findByEntity(entityId));
      if (won.isPresent()) {
        return mintTokenIfMissing(won.get());
      }
      throw maybeRace;
    }
    LOG.infof(
        "Opened refinement %s for %s %s as a session of project %s's front desk",
        refinement.id, noun, entityId, project.id);
    Refinement opened = mintTokenIfMissing(refinement);
    if (opened.tokenId == null) {
      // A mint announces itself; a room created without one is a row the list should still hear of.
      announceCredentialsChanged(refinement.projectId);
    }
    demandMoved(opened.projectId);
    return opened;
  }

  // ---- a session room's demand (epic qits-770) ----------------------------------------------

  /**
   * {@code POST /refinements/{id}/ensure}: a session room's session asked for — demand stamped, the
   * desk told, the list pushed — and the row answered; a 409 {@value #REFINEMENT_SESSIONS_FULL} when
   * it is not already wanted and the project is at the cap. A legacy container room is brought up by
   * its ladder instead, as {@link #ensureContainer} does.
   */
  public Refinement ensure(long id) {
    Refinement room = get(id);
    if (room.containerRoom()) {
      begin(room, false);
      return room;
    }
    Instant now = Instant.now();
    Refinement current = mintTokenIfMissing(room);
    if (!sessions.wantsRunning(current, now)) {
      refuseBeyondCap(projects.get(current.projectId), current, now);
    }
    return stampDemand(current, now);
  }

  /**
   * {@code POST /refinements/{id}/stop}: a session room's demand cleared, so its session is desired
   * STOPPED and the daemon stops it; idempotent. A legacy container room's container is stopped, as
   * {@link #stopContainer} does.
   */
  public Refinement stop(long id) {
    Refinement room = get(id);
    if (room.containerRoom()) {
      return stopContainer(id);
    }
    QuarkusTransaction.requiringNew()
        .run(() -> store.update("lastDemandAt = null where id = ?1", id));
    room.lastDemandAt = null;
    demandMoved(room.projectId);
    changes.fire(id, RefinementChangeHint.Topic.PROCESS);
    return get(id);
  }

  /** Stamp a session room's demand now, tell the desk and push; answers the row as it now stands. */
  private Refinement stampDemand(Refinement room, Instant now) {
    QuarkusTransaction.requiringNew()
        .run(() -> store.update("lastDemandAt = ?1 where id = ?2", now, room.id));
    room.lastDemandAt = now;
    demandMoved(room.projectId);
    changes.fire(room.id, RefinementChangeHint.Topic.PROCESS);
    return QuarkusTransaction.requiringNew()
        .call(() -> store.findByIdOptional(room.id))
        .orElse(room);
  }

  /** The project's other session rooms wanting their session at {@code now}. */
  private List<Refinement> runningBeside(Refinement room, Instant now) {
    String projectId = room.projectId;
    return sessions.running(projectId, now).stream()
        .filter(other -> !other.id.equals(room.id))
        .toList();
  }

  /**
   * The cap: a 409 {@value #REFINEMENT_SESSIONS_FULL} naming the running rooms by qualified id when
   * {@code qits.projects.refinement-sessions-max} of {@code project}'s session rooms other than
   * {@code self} (null for a room about to be created) already want their session.
   */
  private void refuseBeyondCap(Project project, Refinement self, Instant now) {
    List<Refinement> running =
        sessions.running(project.id, now).stream()
            .filter(other -> self == null || !other.id.equals(self.id))
            .toList();
    if (running.size() < sessionsMax) {
      return;
    }
    String names =
        running.stream()
            .map(room -> qualifiedIdOrId(room.entityId, project))
            .collect(Collectors.joining(", "));
    throw new CodedRefusalException(
        409,
        REFINEMENT_SESSIONS_FULL,
        "Project "
            + (project.slug == null ? project.id : project.slug)
            + " already has "
            + running.size()
            + " refinement rooms running ("
            + names
            + "), the most qits.projects.refinement-sessions-max allows. Stop one of them, then"
            + " open this one.");
  }

  /** The entity's qualified id, or its uuid when that cannot be rendered. */
  private String qualifiedIdOrId(String entityId, Project project) {
    try {
      String qualified = RefinementSessions.qualifiedIdOf(entities.fresh(entityId), project);
      return qualified == null ? entityId : qualified;
    } catch (RuntimeException e) {
      return entityId;
    }
  }

  /**
   * A session room's demand moved: the desk is told (it may now be wanted, or no longer), then the
   * sessions list is pushed. Never throws.
   */
  private void demandMoved(String projectId) {
    if (projectId == null) {
      return;
    }
    if (demandChanged != null) {
      try {
        demandChanged.fire(new RefinementDemandChanged(projectId));
      } catch (RuntimeException e) {
        LOG.warnf(
            "Could not announce a refinement demand change for project %s: %s",
            projectId, e.getMessage());
      }
    }
    if (sessions != null) {
      sessions.push(projectId);
    }
  }

  // ---- the room's own token (qits-1482) -----------------------------------------------------

  /**
   * Mint the room's token when the row holds none, and answer the row as it now stands. <b>Never
   * throws</b>: a mint that fails is one WARN and the row answered without a token — opening a room
   * must not depend on qits-idp, and {@link RefinementTokenReconcile} mints it on its next pass. The
   * token is stored only on a row that still holds none; a lost race (or a row discarded meanwhile)
   * revokes what was minted, so one row never orphans a second live token.
   */
  public Refinement mintTokenIfMissing(Refinement refinement) {
    if (refinement == null || refinement.id == null || refinement.tokenId != null) {
      return refinement;
    }
    RefinementTokens.Minted minted;
    try {
      minted = tokens.mint(refinement.id, refinement.projectId, refinement.entityId);
    } catch (RuntimeException e) {
      LOG.warnf(
          "Could not mint the token of refinement %s (entity %s); the token reconcile retries it: %s",
          refinement.id, refinement.entityId, e.getMessage());
      return refinement;
    }
    int stored;
    try {
      stored =
          QuarkusTransaction.requiringNew()
              .call(
                  () ->
                      store.update(
                          "tokenId = ?1, tokenValue = ?2, tokenSubject = ?3"
                              + " where id = ?4 and tokenId is null",
                          minted.tokenId(),
                          minted.token(),
                          minted.subject(),
                          refinement.id));
    } catch (RuntimeException e) {
      LOG.warnf(
          "Could not store the token of refinement %s; revoking it, the reconcile mints again: %s",
          refinement.id, e.getMessage());
      tokens.revoke(minted.tokenId());
      return refinement;
    }
    if (stored == 0) {
      tokens.revoke(minted.tokenId());
      return QuarkusTransaction.requiringNew()
          .call(() -> store.findByIdOptional(refinement.id))
          .orElse(refinement);
    }
    refinement.tokenId = minted.tokenId();
    refinement.tokenValue = minted.token();
    refinement.tokenSubject = minted.subject();
    LOG.infof(
        "Minted the token of refinement %s (entity %s) as %s",
        refinement.id, refinement.entityId, minted.subject());
    announceCredentialsChanged(refinement.projectId);
    return refinement;
  }

  /**
   * The credential a refinement's session runs on — {@code {key: entityId, token, subject}} — for
   * the daemon's {@code sessionCredentials} push (qits-1482 §3). Empty while the row holds none.
   */
  public Optional<RefinementTokens.SessionCredential> credentialOf(long id) {
    return QuarkusTransaction.requiringNew()
        .call(() -> store.findByIdOptional(id))
        .flatMap(RefinementTokens::credentialOf);
  }

  /**
   * Announce that {@code projectId}'s refinement credentials may have moved (qits-1482). Never
   * throws: the desk's sweep re-sends the list anyway, so a failure here costs one interval.
   */
  private void announceCredentialsChanged(String projectId) {
    if (credentialsChanged == null || projectId == null) {
      return;
    }
    try {
      credentialsChanged.fire(new RefinementCredentialsChanged(projectId));
    } catch (RuntimeException e) {
      LOG.warnf(
          "Could not announce a refinement credential change for project %s: %s",
          projectId, e.getMessage());
    }
  }

  /**
   * Revoke the token the row holds, before the row goes. Never throws: a revoke that could not reach
   * qits-idp is a WARN, and {@link RefinementTokenReconcile} reaps the token once no row names it.
   */
  private void revokeToken(Refinement refinement) {
    if (refinement.tokenId == null) {
      return;
    }
    boolean gone;
    try {
      gone = tokens.revoke(refinement.tokenId);
    } catch (RuntimeException e) {
      gone = false;
    }
    if (!gone) {
      LOG.warnf(
          "Could not revoke the token of refinement %s; the token reconcile reaps it",
          refinement.id);
    }
  }

  /**
   * The third refusal of {@link #findOrCreate}: the entity has an ACTIVE agent (qits-1152). Only
   * ACTIVE counts — the port answers OBSOLETE and REMOVED agents too, and their work is over, so they
   * collide with nothing. The agents are also matched back to this entity's id rather than trusted
   * to be about it: a check that refuses somebody else's work would be the worse bug.
   */
  private void refuseWhileDispatched(WorkEntity entity, String noun) {
    if (workspaces.isUnsatisfied()) {
      return; // no workspaces context: nothing can be dispatched here, so nothing is running
    }
    Optional<WorkspaceAgentDispatch.Agent> live =
        workspaces.get().agentsFor(List.of(entity.id)).stream()
            // A write-side reader, so ACTIVE alone: see the port's javadoc for why this is ours.
            .filter(WorkspaceAgentDispatch.Agent::active)
            .filter(agent -> entity.id.equals(agent.workId()))
            .findFirst();
    if (live.isPresent()) {
      throw new DomainException(
          409,
          capitalized(noun)
              + " "
              + entity.id
              + " has a dispatched agent working it ("
              + live.get().agentId()
              + ", on "
              + live.get().wrapperBranch()
              + "), so a refinement room will not be opened beside it — two authors on one draft"
              + " would overwrite each other. Let that run finish, or discard the agent, then"
              + " refine.");
    }
  }

  private static String nounOf(WorkEntity entity) {
    return entity.archetype == null
        ? "entity"
        : entity.archetype.name().toLowerCase(java.util.Locale.ROOT);
  }

  private static String capitalized(String noun) {
    return Character.toUpperCase(noun.charAt(0)) + noun.substring(1);
  }

  /** The refinement row, or 404. */
  public Refinement get(long id) {
    return QuarkusTransaction.requiringNew()
        .call(() -> store.findByIdOptional(id))
        .orElseThrow(() -> new NotFoundException("No refinement " + id));
  }

  /** The entity this id names, of any archetype, or a 404 — for a read that must not answer null. */
  public WorkEntity requireEntity(String entityId) {
    return entities.get(entityId);
  }

  /** The refinement of an entity (epic or ticket), or empty — the read that never creates. */
  public Optional<Refinement> findByEntity(String entityId) {
    return QuarkusTransaction.requiringNew().call(() -> store.findByEntity(entityId));
  }

  /**
   * A project's refinements with their live halves and <b>no git drift</b> — the list redraws on
   * every activity hint, and the strip that renders drift is on the single row's page, not here.
   *
   * <p>The light projection costs nothing now that {@link #view} costs nothing either: the
   * difference is no longer a fetch per row, it is that a listing does not ask {@link
   * RefinementDrift} for a row and therefore never schedules a pass for one nobody has opened.
   */
  public List<RefinementView> listByProject(String projectId) {
    return QuarkusTransaction.requiringNew().call(() -> store.listByProject(projectId)).stream()
        .map(this::lightView)
        .toList();
  }

  private RefinementView lightView(Refinement refinement) {
    if (!refinement.containerRoom()) {
      return sessionView(refinement);
    }
    long id = refinement.id;
    Semaphore lock = locks.get(id);
    boolean provisioning = lock != null && lock.availablePermits() == 0;
    String runtimeStatus;
    String runtimeError = lastErrors.get(id);
    RefinementRuntime.ContainerInfo container = null;
    try {
      container = runtime.inspect(id).orElse(null);
    } catch (RuntimeException e) {
      runtimeError = e.getMessage();
    }
    if (provisioning) {
      runtimeStatus = "PROVISIONING";
    } else if (registry.provisionFailure(id).isPresent() || runtimeError != null) {
      runtimeStatus = "FAILED";
      runtimeError = registry.provisionFailure(id).orElse(runtimeError);
    } else if (container == null) {
      runtimeStatus = "STOPPED";
    } else {
      runtimeStatus = container.running() ? "RUNNING" : "STOPPED";
    }
    RefinementDaemonRegistry.DaemonInfo daemon = registry.lookup(id).orElse(null);
    return new RefinementView(
        refinement,
        runtimeStatus,
        runtimeError,
        "RUNNING".equals(runtimeStatus) ? registry.clean(id).orElse(null) : null,
        null,
        null,
        false,
        registry.agentActivity(id).orElse(null),
        daemon == null ? null : daemon.connectedAt(),
        daemon == null ? null : daemon.daemonVersion(),
        registry.daemonOutdated(id),
        factory.imageVersion(),
        staleAgainstPin(daemon, factory.imageVersion()));
  }

  // ---- lifecycle ---------------------------------------------------------------------------

  /**
   * Bring the container up (asynchronously) and answer the narration to watch. On a session room
   * (the alias the released SPA still calls, until qits-1316) the session is asked for as {@link
   * #ensure} does and the narration is one already settled: there is no bring-up to watch here, the
   * desk runs it.
   */
  public String ensureContainer(long id) {
    Refinement refinement = get(id);
    if (!refinement.containerRoom()) {
      ensure(id);
      return settledNarration("The room's session runs in the project's front desk.");
    }
    return begin(refinement, false);
  }

  /** A narration begun and settled at once, for a door whose answer must name one. */
  private String settledNarration(String line) {
    TechnicalProcess process = processes.begin("ensure");
    process.openSegment("session");
    process.completeNoOp("session", line);
    process.finishProvision(true);
    return process.id();
  }

  /**
   * Replace the container: remove it (never its volume), then provision fresh — gated on a tree
   * the daemon has vouched is clean, because the writable layer dies with the container.
   */
  public String recreateContainer(long id) {
    Refinement refinement = get(id);
    if (!refinement.containerRoom()) {
      // A session room has no container to replace: a stop and an ensure is the honest reading.
      stop(id);
      ensure(id);
      return settledNarration("The room's session was stopped and asked for again.");
    }
    if (!registry.clean(id).map(Boolean::booleanValue).orElse(false)) {
      throw new DomainException(
          400,
          "The working tree is not provably clean, so the container will not be replaced."
              + " Commit or discard the changes first — or if the container is not running,"
              + " simply start it.");
    }
    return begin(refinement, true);
  }

  /**
   * Stop the container, leaving it and its checkout in place. Idempotent. On a session room, {@link
   * #stop}.
   */
  public Refinement stopContainer(long id) {
    Refinement refinement = get(id);
    if (!refinement.containerRoom()) {
      return stop(id);
    }
    runtime.stop(id);
    tunnels.closeTunnel(id);
    registry.forget(id);
    activeProcesses.remove(id);
    changes.fire(id, RefinementChangeHint.Topic.PROCESS);
    return refinement;
  }

  /**
   * Best-effort graceful stop ahead of {@link RefinementRuntime#delete}, at every call site that
   * tears a refinement container down. {@code runtime.stop} is itself documented never-throw, but a
   * failure here must never block the removal that follows, so any {@link RuntimeException} is
   * logged and swallowed. The point: the refinement container runs the same workspace daemon a
   * workspace container does, and a coding agent inside it (started with {@code claude
   * --remote-control}) only archives its claude.ai session on SIGTERM — a bare delete (docker rm
   * -f) SIGKILLs it instead and leaves the session dangling.
   */
  private void stopBeforeDelete(long id) {
    try {
      runtime.stop(id);
    } catch (RuntimeException e) {
      LOG.debugf(e, "Graceful stop before removal failed for refinement %s; removing anyway", id);
    }
  }

  /**
   * The end of a refinement: container, volume, credential (the commissioned client and the room's
   * own token, qits-1482), branch, row — in that order, so a failure leaves nothing orphaned ahead
   * of it. The entity's DROPPED transition is its own call on
   * its archetype's surface; this tears down only what this service hosts.
   */
  public void discard(long id) {
    Refinement refinement = get(id);
    if (!refinement.containerRoom()) {
      discardSession(refinement);
      return;
    }
    tunnels.closeTunnel(id);
    registry.forget(id);
    activeProcesses.remove(id);
    stopBeforeDelete(id);
    runtime.delete(id);
    commissions.handBack(refinement);
    revokeToken(refinement);
    deleteBranchQuietly(refinement);
    QuarkusTransaction.requiringNew().run(() -> store.deleteById(id));
    announceCredentialsChanged(refinement.projectId);
    lastErrors.remove(id);
    drift.forget(id);
    LOG.infof("Discarded refinement %s (entity %s)", id, refinement.entityId);
  }

  /**
   * The end of a session room (epic qits-770): the row deleted (its draft, attachments and designs
   * cascade), the daemon sent the list without its key — which stops the session — then the room's
   * token revoked and the credentials sent without it, and the desk's demand recomputed.
   */
  private void discardSession(Refinement room) {
    long id = room.id;
    QuarkusTransaction.requiringNew().run(() -> store.deleteById(id));
    demandMoved(room.projectId);
    revokeToken(room);
    announceCredentialsChanged(room.projectId);
    lastErrors.remove(id);
    LOG.infof("Discarded refinement %s (entity %s)", id, room.entityId);
  }

  /** The id of the ensure narration currently live for this row, or null. */
  public String activeProcessId(long id) {
    TechnicalProcess process = activeProcesses.get(id);
    if (process == null) {
      return null;
    }
    if (process.isTerminal()) {
      activeProcesses.remove(id);
      return null;
    }
    return process.id();
  }

  private String begin(Refinement refinement, boolean replace) {
    long id = refinement.id;
    Semaphore lock = locks.computeIfAbsent(id, key -> new Semaphore(1));
    if (!lock.tryAcquire()) {
      // Somebody is already doing exactly this; watch their narration rather than queue a second
      // bring-up behind an image pull.
      String live = activeProcessId(id);
      if (live != null) {
        return live;
      }
      throw new DomainException(409, "The refinement container is already being worked on.");
    }
    try {
      TechnicalProcess process = processes.begin("ensure");
      activeProcesses.put(id, process);
      lastErrors.remove(id);
      registry.attachProvisionProcess(id, process);
      changes.fire(id, RefinementChangeHint.Topic.PROCESS);
      executor.submit(() -> run(refinement, replace, process, lock));
      return process.id();
    } catch (RuntimeException e) {
      lock.release();
      throw e;
    }
  }

  /** The ladder, off the request thread. The lock is held for the container verbs only. */
  private void run(Refinement refinement, boolean replace, TechnicalProcess process, Semaphore lock) {
    long id = refinement.id;
    try {
      // Off the request thread there is no ambient session, so the reads open their own.
      Project project =
          QuarkusTransaction.requiringNew().call(() -> projects.get(refinement.projectId));
      Repository wrapper =
          QuarkusTransaction.requiringNew().call(() -> repositories.get(refinement.repositoryId));
      String wrapperName = ProjectService.wrapperName(project);
      String slug = slugOf(refinement);
      WorkEntity entity = entityOf(refinement);
      // Read at every bring-up, so a block, a retitle or a transition that happened while the
      // container slept is right at its wake (qits-614, qits-617).
      RefinementRuntime.RefinedEntity named =
          entity == null
              ? RefinementRuntime.RefinedEntity.UNKNOWN
              : new RefinementRuntime.RefinedEntity(
                  qualifiedIdOf(entity, project), entity.title, entity.status, entity.blocked);
      process.openSegment("container");
      if (!branchStillExists(refinement)) {
        // The branch is gone from under the refinement — somebody resolved it out-of-band. The
        // container and its checkout are torn down rather than resurrecting a deleted branch; the
        // next open recreates the refinement from the entity.
        process.appendLine(
            "container",
            "The branch " + refinement.branch + " no longer exists on " + ProjectService.wrapperName(project) + ".");
        stopBeforeDelete(id);
        runtime.delete(id);
        commissions.handBack(refinement);
        revokeToken(
            QuarkusTransaction.requiringNew()
                .call(() -> store.findByIdOptional(id))
                .orElse(refinement));
        QuarkusTransaction.requiringNew().run(() -> store.deleteById(id));
        announceCredentialsChanged(refinement.projectId);
        process.appendLine("container", "The refinement was torn down.");
        process.settleSegment("container", false);
        process.failProvision(
            "The refining branch is gone; open the refinement again to start afresh.");
        return;
      }
      RefinementRuntime.ContainerInfo existing = runtime.inspect(id).orElse(null);
      if (replace && existing != null) {
        process.appendLine("container", "Removing the container (the checkout volume survives).");
        // A stopped container has nothing to SIGTERM; only stop it first when it is known running.
        if (existing.running()) {
          stopBeforeDelete(id);
        }
        runtime.delete(id);
        existing = null;
      }
      if (existing == null) {
        process.appendLine("container", "Provisioning a fresh refinement container.");
        runtime.provision(refinement, project.slug, slug, wrapperName, named);
        process.settleSegment("container", true);
        // Not settled here: the daemon's Provisioned/ProvisionFailed settles the narration, via
        // the registry. The idle reaper is the backstop for a daemon that never dials home.
      } else if (!existing.running()) {
        process.appendLine("container", "Waking the stopped container.");
        runtime.wake(refinement, project.slug, slug, wrapperName, named);
        process.settleSegment("container", true);
      } else {
        runtime.touch(id);
        process.completeNoOp("container", "The container is already running.");
        process.finishProvision(true);
      }
    } catch (DomainException refused) {
      lastErrors.put(id, refused.getMessage());
      process.failProvision(refused.getMessage());
    } catch (RuntimeException e) {
      LOG.errorf(e, "Could not ensure the refinement container %s", id);
      lastErrors.put(id, e.getMessage());
      process.failProvision(e.getMessage());
    } finally {
      lock.release();
      changes.fire(id, RefinementChangeHint.Topic.PROCESS);
    }
  }

  // ---- projection --------------------------------------------------------------------------

  /** The row as the status strip reads it. */
  public RefinementView view(Refinement refinement) {
    if (!refinement.containerRoom()) {
      return sessionView(refinement);
    }
    long id = refinement.id;
    Semaphore lock = locks.get(id);
    boolean provisioning = lock != null && lock.availablePermits() == 0;
    RefinementRuntime.ContainerInfo container = null;
    String runtimeError = lastErrors.get(id);
    try {
      container = runtime.inspect(id).orElse(null);
    } catch (RuntimeException e) {
      runtimeError = e.getMessage();
    }
    String runtimeStatus;
    if (provisioning) {
      runtimeStatus = "PROVISIONING";
    } else if (registry.provisionFailure(id).isPresent() || runtimeError != null) {
      runtimeStatus = "FAILED";
      runtimeError = registry.provisionFailure(id).orElse(runtimeError);
    } else if (container == null) {
      runtimeStatus = "STOPPED";
    } else {
      runtimeStatus = container.running() ? "RUNNING" : "STOPPED";
    }

    Boolean clean =
        "RUNNING".equals(runtimeStatus) ? registry.clean(id).orElse(null) : null;

    // Read, never computed. This used to be a mirror refresh — a git fetch, or a full clone when
    // the mirror was cold, which is precisely the state a just-created refinement leaves it in — so
    // the one read the refining page opens with was the one that could block on the wire. It is a
    // cache lookup now, answering null ("not known yet") until the background pass has an answer
    // and firing GIT_STATUS when it gets one. See RefinementDrift.
    RefinementDrift.Drift gitDrift = driftOf(refinement);

    RefinementDaemonRegistry.DaemonInfo daemon = registry.lookup(id).orElse(null);
    return new RefinementView(
        refinement,
        runtimeStatus,
        runtimeError,
        clean,
        gitDrift.ahead(),
        gitDrift.behind(),
        gitDrift.conflictsWithParent(),
        registry.agentActivity(id).orElse(null),
        daemon == null ? null : daemon.connectedAt(),
        daemon == null ? null : daemon.daemonVersion(),
        registry.daemonOutdated(id),
        factory.imageVersion(),
        staleAgainstPin(daemon, factory.imageVersion()));
  }

  /**
   * A session room as the strip reads it (epic qits-770): {@code runtimeStatus} is the key's last
   * reported state — {@code STARTING}, {@code RUNNING}, {@code STOPPED} or {@code FAILED} — and
   * {@code runtimeError} the report's detail. Before any report the desired state stands in: a wanted
   * session is {@code STARTING}, an unwanted one {@code STOPPED}. A report saying RUNNING about a room
   * no longer wanted reads as it is reported until the next report. The container-only halves are
   * null or false; the desk's state rides beside.
   */
  RefinementView sessionView(Refinement room) {
    boolean wanted = sessions.wantsRunning(room, Instant.now());
    String status =
        room.sessionState != null
            ? room.sessionState
            : wanted
                ? eu.wohlben.qits.projectsdaemon.protocol.DaemonProtocol.ReportedState.STARTING
                : eu.wohlben.qits.projectsdaemon.protocol.DaemonProtocol.ReportedState.STOPPED;
    RefinementDesk.DeskStatus desk =
        desks == null || desks.isUnsatisfied()
            ? null
            : desks.get().of(room.projectId).orElse(null);
    return new RefinementView(
        room,
        status,
        room.sessionDetail,
        null,
        null,
        null,
        false,
        null,
        null,
        null,
        null,
        null,
        false,
        true,
        room.sessionId,
        room.sessionCommandId,
        desk == null ? null : desk.status(),
        desk == null ? null : desk.detail());
  }

  /**
   * Whether the connected daemon is running something other than the image this service pins —
   * {@code agenthost/AgentContainerState.against} applied on the refinement axis, and deliberately
   * the same three rules.
   *
   * <p><b>It is gated on a daemon being connected, so it is never a guess.</b> With no daemon there
   * is nothing to compare and the answer is {@code false}: "not stale" is the absence of a claim
   * here, exactly as it is in the sweep that stops a container on one. A connected daemon that
   * reported no version, or a blank one, counts as behind — an image built without build-time
   * filtering cannot vouch that it is the pinned one, and the pin is the thing being asserted.
   *
   * <p><b>This is not {@code daemonOutdated} and the two must not be collapsed.</b> That one is
   * relative to the newest daemon connected to <em>this host</em> — right for "somebody else has a
   * newer one", and {@code null} whenever this is the only refinement daemon connected, which is
   * the ordinary case. This one is relative to the pom pin — right for "this is not the image we
   * deploy" — and it answers on a host with exactly one container.
   */
  static boolean staleAgainstPin(RefinementDaemonRegistry.DaemonInfo daemon, String pinnedVersion) {
    return daemon != null
        && (daemon.daemonVersion() == null
            || daemon.daemonVersion().isBlank()
            || !daemon.daemonVersion().equals(pinnedVersion));
  }

  /**
   * Everything the DTO is assembled from — the row plus the live halves.
   *
   * <p>{@code pinnedDaemonVersion} is a property of this service and not of the container, so it is
   * answered whether or not a daemon is connected; {@code daemonVersionStale} is the comparison
   * against it, and {@link #staleAgainstPin} carries the whole rule and the distinction from {@code
   * daemonOutdated}.
   */
  public record RefinementView(
      Refinement refinement,
      String runtimeStatus,
      String runtimeError,
      Boolean clean,
      Integer ahead,
      Integer behind,
      boolean conflictsWithParent,
      String agentActivity,
      Instant daemonConnectedAt,
      String daemonVersion,
      Boolean daemonOutdated,
      String pinnedDaemonVersion,
      boolean daemonVersionStale,
      boolean session,
      String sessionId,
      String commandId,
      String deskStatus,
      String deskDetail) {

    /** A legacy container room's view: none of the session halves. */
    public RefinementView(
        Refinement refinement,
        String runtimeStatus,
        String runtimeError,
        Boolean clean,
        Integer ahead,
        Integer behind,
        boolean conflictsWithParent,
        String agentActivity,
        Instant daemonConnectedAt,
        String daemonVersion,
        Boolean daemonOutdated,
        String pinnedDaemonVersion,
        boolean daemonVersionStale) {
      this(
          refinement,
          runtimeStatus,
          runtimeError,
          clean,
          ahead,
          behind,
          conflictsWithParent,
          agentActivity,
          daemonConnectedAt,
          daemonVersion,
          daemonOutdated,
          pinnedDaemonVersion,
          daemonVersionStale,
          false,
          null,
          null,
          null,
          null);
    }
  }

  // ---- the pieces --------------------------------------------------------------------------

  private Repository wrapperOf(Project project) {
    String wrapperName = ProjectService.wrapperName(project);
    return repositories
        .findByProjectAndName(project.id, wrapperName)
        .orElseThrow(
            () ->
                new DomainException(
                    409,
                    "Project "
                        + project.id
                        + " has no wrapper repository ("
                        + wrapperName
                        + "), so there is nothing to refine against."));
  }

  /** Cut the branch at the wrapper's default tip, or adopt one already on the origin. */
  private void cutOrAdoptBranch(Repository wrapper, String branch) {
    RepoMirror mirror = mirrors.of(wrapper.id);
    try {
      if (mirror.remoteBranchSha(branch).isPresent()) {
        return; // adopt as it stands — a previous refinement's work is work, not a conflict
      }
      mirror.refresh();
      String from = wrapper.mainBranch == null ? "main" : wrapper.mainBranch;
      PushOutcome outcome = mirror.createBranch(branch, from);
      if (!outcome.accepted() && mirror.remoteBranchSha(branch).isEmpty()) {
        throw new DomainException(
            502, "Could not cut " + branch + ": " + outcome.output());
      }
    } catch (GitMirrorException e) {
      throw new DomainException(
          502, "Could not reach the git host to cut " + branch + ": " + e.getMessage());
    }
  }

  private boolean branchStillExists(Refinement refinement) {
    try {
      return mirrors.of(refinement.repositoryId).remoteBranchSha(refinement.branch).isPresent();
    } catch (RuntimeException e) {
      // Could not ask is not "gone" — tearing a refinement down wants positive evidence.
      return true;
    }
  }

  private void deleteBranchQuietly(Refinement refinement) {
    try {
      mirrors.of(refinement.repositoryId).deleteBranch(refinement.branch);
    } catch (RuntimeException e) {
      LOG.warnf(
          "Could not delete %s while discarding refinement %s: %s",
          refinement.branch, refinement.id, e.getMessage());
    }
  }

  /**
   * The drift half of a projection, and it cannot fail the read. {@link RefinementDrift#of} does no
   * git work and does not throw by contract; the catch is there so that a future one could not turn
   * an advisory number into a 500 on the page's opening request.
   */
  private RefinementDrift.Drift driftOf(Refinement refinement) {
    try {
      return drift.of(refinement);
    } catch (RuntimeException e) {
      LOG.debugf("Could not read the drift of refinement %s: %s", refinement.id, e.getMessage());
      return RefinementDrift.Drift.UNKNOWN;
    }
  }

  /**
   * The refined entity, read fresh for the labels its container carries — its qualified id, title,
   * status and block flag (qits-614, qits-617). The refinement names the entity by uuid only, so all
   * of them live one read away, and one read serves them all.
   *
   * <p><b>Degrades to {@code null}, never throws.</b> They are labels on a container somebody is
   * waiting for: an entity gone between the open and this read, or a read that fails outright,
   * start the container without any — named by its uuid and unmarked, as before.
   */
  private WorkEntity entityOf(Refinement refinement) {
    try {
      return entities.fresh(refinement.entityId);
    } catch (RuntimeException e) {
      LOG.warnf(
          "Could not read entity %s for refinement %s; starting its container without its"
              + " qualified id, title, status or block flag: %s",
          refinement.entityId, refinement.id, e.toString());
      return null;
    }
  }

  /**
   * The refined entity's {@code <project-slug>-<number>} — {@code qits-614} — for the container's
   * {@code QITS_WORKSPACE_DAEMON_ENTITY_ID} (qits-614), rendered by {@link QualifiedEntityIds#render}
   * and nowhere else; {@code null} when the entity, its number or the project's slug is missing.
   */
  private static String qualifiedIdOf(WorkEntity entity, Project project) {
    if (entity == null
        || entity.number < 1
        || project == null
        || project.slug == null
        || project.slug.isBlank()) {
      return null;
    }
    return QualifiedEntityIds.render(project.slug, entity.number);
  }

  /**
   * The entity slug a refinement's container name is built from, read off the branch it cut.
   *
   * <p>Package-private and static rather than private, so {@link RefinementStaleImageSweep} can
   * resolve a container name back to a row without a second reading of the {@code refining/<slug>}
   * grammar. Two readings of one convention is exactly how a sweep comes to match nothing on the
   * day the branch prefix changes, with everything still compiling and every test still green.
   */
  static String slugOf(Refinement refinement) {
    return refinement.branch != null && refinement.branch.startsWith("refining/")
        ? refinement.branch.substring("refining/".length())
        : refinement.label;
  }

  /** {@code refining-<slug>}, non-alphanumeric runs collapsed, 64 chars — the SPA's own rule. */
  static String label(String slug) {
    String label = ("refining-" + slug).replaceAll("[^A-Za-z0-9_-]+", "-");
    return label.length() <= 64 ? label : label.substring(0, 64);
  }
}
