package eu.wohlben.qits.projects.refinementhost;

import eu.wohlben.qits.entities.control.EntityDispatchService;
import eu.wohlben.qits.entities.control.EntityStateMachine;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.EntityStatus;
import eu.wohlben.qits.entities.entity.WorkEntity;
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
import eu.wohlben.qits.projects.persistence.RefinementRepository;
import eu.wohlben.qits.projects.control.TechnicalProcessRegistry;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The refinement lifecycle: find-or-create keyed by entity (an epic, a ticket or a campaign), the
 * session's demand (ensure, stop), discard, and the row projection the status strip reads.
 *
 * <p><b>Every room is a session room</b> (epic qits-770): one more session of the project's front
 * desk, keyed by the entity id, wanted while its {@code last_demand_at} is inside {@code
 * qits.projects.agent-idle-timeout} — see {@link RefinementSessions}. It cuts no branch, places no
 * container and commissions no client. The per-room containers that came before are retired
 * (qits-1316); {@link LegacyRefinementContainers} cleared what they left outside the database.
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
  @Inject RefinementRepository store;

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
   * its ensure door says why.
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
   * it is not already wanted and the project is at the cap.
   */
  public Refinement ensure(long id) {
    Refinement room = get(id);
    Instant now = Instant.now();
    Refinement current = mintTokenIfMissing(room);
    if (!sessions.wantsRunning(current, now)) {
      refuseBeyondCap(projects.get(current.projectId), current, now);
    }
    return stampDemand(current, now);
  }

  /**
   * {@code POST /refinements/{id}/stop}: a session room's demand cleared, so its session is desired
   * STOPPED and the daemon stops it; idempotent.
   */
  public Refinement stop(long id) {
    Refinement room = get(id);
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

  /** A project's refinements as the status strip reads them. */
  public List<RefinementView> listByProject(String projectId) {
    return QuarkusTransaction.requiringNew().call(() -> store.listByProject(projectId)).stream()
        .map(this::view)
        .toList();
  }

  // ---- lifecycle ---------------------------------------------------------------------------

  /**
   * {@code POST /refinements/{id}/ensure-container}: {@link #ensure} under the door's old name, kept
   * while a consumer's pact still calls it (qits-platform-access-cli's {@code qits work refinement
   * open}). The session is asked for and the narration answered is one already settled: there is no
   * bring-up to watch here, the desk runs it.
   */
  public String ensureContainer(long id) {
    ensure(id);
    TechnicalProcess process = processes.begin("ensure");
    process.openSegment("session");
    process.completeNoOp("session", "The room's session runs in the project's front desk.");
    process.finishProvision(true);
    return process.id();
  }

  /**
   * The end of a refinement room: the row deleted (its draft, attachments and designs cascade), the
   * daemon sent the list without its key — which stops the session — then the room's token revoked
   * and the credentials sent without it, and the desk's demand recomputed. The entity's own
   * transition is its own call on its archetype's surface; this tears down only what this service
   * hosts.
   */
  public void discard(long id) {
    Refinement room = get(id);
    QuarkusTransaction.requiringNew().run(() -> store.deleteById(id));
    demandMoved(room.projectId);
    revokeToken(room);
    announceCredentialsChanged(room.projectId);
    LOG.infof("Discarded refinement %s (entity %s)", id, room.entityId);
  }

  // ---- projection --------------------------------------------------------------------------

  /**
   * The room as the strip reads it (epic qits-770): {@code runtimeStatus} is the key's last reported
   * state — {@code STARTING}, {@code RUNNING}, {@code STOPPED} or {@code FAILED} — and {@code
   * runtimeError} the report's detail. Before any report the desired state stands in: a wanted
   * session is {@code STARTING}, an unwanted one {@code STOPPED}. A report saying RUNNING about a room
   * no longer wanted reads as it is reported until the next report. The desk's state rides beside.
   */
  public RefinementView view(Refinement room) {
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
        room.sessionId,
        room.sessionCommandId,
        desk == null ? null : desk.status(),
        desk == null ? null : desk.detail());
  }

  /** Everything the DTO is assembled from — the row plus the live halves. */
  public record RefinementView(
      Refinement refinement,
      String runtimeStatus,
      String runtimeError,
      String sessionId,
      String commandId,
      String deskStatus,
      String deskDetail) {}

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

  /** {@code refining-<slug>}, non-alphanumeric runs collapsed, 64 chars — the SPA's own rule. */
  static String label(String slug) {
    String label = ("refining-" + slug).replaceAll("[^A-Za-z0-9_-]+", "-");
    return label.length() <= 64 ? label : label.substring(0, 64);
  }
}
