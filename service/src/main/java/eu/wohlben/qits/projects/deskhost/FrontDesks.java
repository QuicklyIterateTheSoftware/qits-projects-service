package eu.wohlben.qits.projects.deskhost;

import eu.wohlben.qits.projects.agenthost.AgentDaemonRegistry;
import eu.wohlben.qits.projects.agenthost.AgentRuntimeStatus;
import eu.wohlben.qits.projects.agenthost.AgentTunnels;
import eu.wohlben.qits.projects.control.ProjectService;
import eu.wohlben.qits.projects.entity.FrontDesk;
import eu.wohlben.qits.projects.entity.FrontDeskDesired;
import eu.wohlben.qits.projects.entity.FrontDeskLifecycle;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.error.CodedRefusalException;
import eu.wohlben.qits.projects.error.DomainException;
import eu.wohlben.qits.projects.persistence.FrontDeskRepository;
import eu.wohlben.qits.projects.persistence.ProjectRepository;
import eu.wohlben.qits.projects.targethost.DeskLeases;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.Context;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.jboss.logging.Logger;

/**
 * The project front desks (qits-767): the {@code front_desk} rows and every verb on them — the
 * doors' ensure, stop, delete and read, the desired state, the tokens, and the state a desk reads.
 *
 * <p><b>Where a desk runs is a DESK lease on an execution target</b> (epic qits-1481, qits-1551):
 * every change to a desk's desired state is handed to {@link DeskLeases#apply}, which asks
 * qits-system for a lease for a wanted desk with none and ends the lease of an unwanted one. The
 * front-desk runners, their socket and their pull placement are gone.
 *
 * <p><b>The desk token is a lease's</b>: {@link DeskLeases} mints it immediately before it asks for
 * a lease, stores it on the row ({@code token_*}) and revokes it once the lease is over; a mint
 * that fails leaves the desk FAILED with {@link #TOKEN_UNAVAILABLE}, and the next pass retries it.
 *
 * <p><b>The delivery token</b> (epic qits-1440) follows the project's {@code delivery_enabled}
 * instead ({@link #syncDeliveryToken}): minted while the loop is on and the row holds none, revoked
 * and cleared when it is off, and revoked with the desk. A failed mint leaves the desk usable — the
 * loop simply has no credential yet — and the sweep retries it.
 *
 * <p><b>The session token</b> (qits-1482) is minted when the row is created ({@link
 * #mintSessionIfMissing}) and goes with the row; a failed mint is a WARN, never a FAILED desk, and
 * the sweep retries it. It is in no spec: {@link #sessionToken} holds it for the control socket.
 * Every mint or revoke of the session or the delivery token re-sends the desk's daemon its session
 * credentials ({@link DeskSessionCredentials#push}).
 *
 * <p><b>The bound subjects</b> — each desk's {@code token_subject} — are also held in memory, loaded
 * at boot and kept with every write ({@link #bindSubject}, {@link #unbindSubject}), because the
 * control socket's upgrade check and the dial-back route ask on the event loop.
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

  @Inject FrontDeskTokens tokens;

  @Inject FrontDeskDemand demand;

  @Inject FrontDeskSpecs specs;

  /** Where a desk runs: its DESK lease (epic qits-1481). */
  @Inject DeskLeases leases;

  @Inject AgentDaemonRegistry daemons;

  @Inject AgentTunnels tunnels;

  /** Re-sent to the desk's daemon whenever a session or delivery token comes or goes (qits-1482). */
  @Inject DeskSessionCredentials sessionCredentials;

  /** Each desk's bound {@code tok-} subject, by project. */
  private final ConcurrentHashMap<String, String> subjects = new ConcurrentHashMap<>();

  private volatile boolean subjectsLoaded;

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

  /** {@code projectId}'s desk holds a token for {@code subject} now (a lease's, epic qits-1481). */
  public void bindSubject(String projectId, String subject) {
    if (projectId != null && subject != null) {
      subjects.put(projectId, subject);
    }
  }

  /** {@code projectId}'s desk holds no token any more: its lease is over. */
  public void unbindSubject(String projectId) {
    if (projectId != null) {
      subjects.remove(projectId);
    }
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

  /**
   * The session token of {@code projectId}'s desk (qits-1482) — its id, value and subject — or empty
   * while it has none. Held for the push that hands it to the daemon over the control socket;
   * nothing puts it into an environment.
   */
  public Optional<FrontDeskTokens.Minted> sessionToken(String projectId) {
    return find(projectId)
        .filter(row -> row.sessionTokenValue != null)
        .map(
            row ->
                new FrontDeskTokens.Minted(
                    row.sessionTokenId, row.sessionTokenValue, row.sessionTokenSubject));
  }

  // --- the doors ---------------------------------------------------------------------------------

  /**
   * {@code POST …/agent-container/ensure}: the row if absent, demand stamped, a lease failure
   * cleared (an explicit Start tries again), the desired state recomputed and the lease asked for;
   * answers the desk as it stands.
   */
  public FrontDeskState ensure(String projectId) {
    project(projectId);
    Instant now = Instant.now();
    ensureRow(projectId);
    demand.stamp(projectId, now);
    checkEdge(projectId);
    clearLeaseFailure(projectId);
    reconcileAndApply(projectId, now);
    return status(projectId);
  }

  /**
   * {@code POST …/agent-container/stop}: an ON_DEMAND desk's demand cleared, so it is desired
   * STOPPED and its lease ended (its work volume kept); an ALWAYS_ON desk is a 409 {@link
   * #FRONT_DESK_ALWAYS_ON}.
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
    reconcileAndApply(projectId, Instant.now());
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
   * Remove a desk whole: the row deleted, its lease ended and its work volume purged once the lease
   * is ENDED ({@link DeskLeases#retire}), its tokens revoked, the tunnel and the daemon's in-memory
   * state forgotten. A desk still wanted (ALWAYS_ON) is created afresh and leased again by the next
   * sweep. Also the project deletion's path.
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
    try {
      leases.retire(gone);
    } catch (RuntimeException e) {
      LOG.warnf(
          "Could not retire the lease of the removed desk of project %s: %s",
          projectId, e.getMessage());
    }
    if (gone.tokenId != null && !tokens.revoke(gone.tokenId)) {
      LOG.warnf(
          "Could not revoke the desk token of project %s; the token reconcile reaps it", projectId);
    }
    if (gone.deliveryTokenId != null && !tokens.revokeDelivery(gone.deliveryTokenId)) {
      LOG.warnf(
          "Could not revoke the delivery token of project %s; the token reconcile reaps it",
          projectId);
    }
    if (gone.sessionTokenId != null && !tokens.revokeSession(gone.sessionTokenId)) {
      LOG.warnf(
          "Could not revoke the session token of project %s; the token reconcile reaps it",
          projectId);
    }
    // The row is gone and its tokens with it: a daemon still connected is told it holds nothing.
    sessionCredentials.push(projectId);
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

  /**
   * The row of {@code projectId}, created if absent, with its session token — and its delivery token
   * too while the project's loop is enabled. The desk token is a lease's: {@link DeskLeases} mints
   * it.
   */
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
    mintSessionIfMissing(projectId);
    syncDeliveryToken(projectId);
  }

  /**
   * Mint the desk's session token (qits-1482) when it has none: outside any transaction, stored only
   * on a row that still has none (a lost race, or a row deleted meanwhile, revokes what was minted).
   * A failed mint — an idp that does not know {@code desk-session} yet, say — is a WARN: the desk is
   * usable without it, and the sweep calls this again.
   */
  public void mintSessionIfMissing(String projectId) {
    FrontDesk row = find(projectId).orElse(null);
    if (row == null || row.sessionTokenId != null) {
      return;
    }
    FrontDeskTokens.Minted minted;
    try {
      minted = tokens.mintSession(projectId);
    } catch (RuntimeException e) {
      LOG.warnf("Could not mint the session token of project %s: %s", projectId, e.getMessage());
      return;
    }
    int stored =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    desks.update(
                        "sessionTokenId = ?1, sessionTokenValue = ?2, sessionTokenSubject = ?3"
                            + " where projectId = ?4 and sessionTokenId is null",
                        minted.tokenId(),
                        minted.token(),
                        minted.subject(),
                        projectId));
    if (stored == 0) {
      tokens.revokeSession(minted.tokenId());
      return;
    }
    LOG.infof("Minted the session token of project %s", projectId);
    sessionCredentials.push(projectId);
  }

  /**
   * Bring the desk's delivery token in line with the project's {@code delivery_enabled} (epic
   * qits-1440): enabled and none held, one is minted outside any transaction and stored only on a
   * row that still holds none (a lost race, or a row deleted meanwhile, revokes what was minted);
   * disabled and one held, it is cleared from the row and revoked. A desk with no row is left alone.
   * A failed mint or revoke is a WARN: the sweep calls this again, and the token reconcile reaps
   * what a failed revoke left.
   */
  public void syncDeliveryToken(String projectId) {
    FrontDesk row = find(projectId).orElse(null);
    if (row == null) {
      return;
    }
    Project project = QuarkusTransaction.requiringNew().call(() -> projectRows.findById(projectId));
    boolean enabled = project != null && project.deliveryEnabled;
    if (enabled && row.deliveryTokenId == null) {
      FrontDeskTokens.Minted minted;
      try {
        minted = tokens.mintDelivery(projectId);
      } catch (RuntimeException e) {
        LOG.warnf("Could not mint the delivery token of project %s: %s", projectId, e.getMessage());
        return;
      }
      int stored =
          QuarkusTransaction.requiringNew()
              .call(
                  () ->
                      desks.update(
                          "deliveryTokenId = ?1, deliveryTokenValue = ?2, deliveryTokenSubject = ?3"
                              + " where projectId = ?4 and deliveryTokenId is null",
                          minted.tokenId(),
                          minted.token(),
                          minted.subject(),
                          projectId));
      if (stored == 0) {
        tokens.revokeDelivery(minted.tokenId());
        return;
      }
      LOG.infof("Minted the delivery token of project %s", projectId);
      sessionCredentials.push(projectId);
    } else if (!enabled && row.deliveryTokenId != null) {
      String tokenId = row.deliveryTokenId;
      int cleared =
          QuarkusTransaction.requiringNew()
              .call(
                  () ->
                      desks.update(
                          "deliveryTokenId = null, deliveryTokenValue = null,"
                              + " deliveryTokenSubject = null"
                              + " where projectId = ?1 and deliveryTokenId = ?2",
                          projectId,
                          tokenId));
      if (cleared == 0) {
        return;
      }
      // The front desk session goes back to the session token before the old one stops working.
      sessionCredentials.push(projectId);
      if (!tokens.revokeDelivery(tokenId)) {
        LOG.warnf(
            "Could not revoke the delivery token of project %s; the token reconcile reaps it",
            projectId);
      } else {
        LOG.infof("Revoked the delivery token of project %s: its delivery loop is off", projectId);
      }
    }
  }

  /** Record why a desk is not usable. */
  void recordFailure(String projectId, String detail) {
    QuarkusTransaction.requiringNew()
        .run(() -> desks.update("failureDetail = ?1 where projectId = ?2", detail, projectId));
  }

  /**
   * Clear a failure a lease left (the target's FAILED, a refusal), so the next lease is asked for —
   * an explicit Start, and the sweep. A failure this service decided (an unconfigured edge, a token
   * that could not be minted) is left for its own check.
   */
  public void clearLeaseFailure(String projectId) {
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                desks.update(
                    "failureDetail = null where projectId = ?1 and failureDetail is not null"
                        + " and failureDetail not like ?2 and failureDetail not like ?3",
                    projectId,
                    FrontDeskSpecs.EDGE_PLANE_UNCONFIGURED + "%",
                    TOKEN_UNAVAILABLE + "%"));
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
   * Recompute the desk's desired state; a wanted desk with no lease is stamped {@code queued_at} if
   * it has none, an unwanted one loses it. Answers whether the desired state moved.
   */
  public boolean reconcile(String projectId, Instant now) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              FrontDesk row = desks.findById(projectId);
              Project project = row == null ? null : projectRows.findById(projectId);
              if (row == null || project == null) {
                return false;
              }
              FrontDeskDesired next = demand.desired(project, row, now);
              boolean moved = row.desired != next;
              row.desired = next;
              if (row.leaseId == null) {
                if (next == FrontDeskDesired.RUNNING) {
                  if (row.queuedAt == null) {
                    row.queuedAt = now;
                  }
                } else {
                  row.queuedAt = null;
                  row.leaseDetail = null;
                }
              }
              if (moved) {
                LOG.infof("The front desk of project %s is now desired %s", projectId, next);
              }
              return moved;
            });
  }

  /** {@link #reconcile}, then the desk's lease brought in line ({@link DeskLeases#apply}). */
  public void reconcileAndApply(String projectId, Instant now) {
    reconcile(projectId, now);
    try {
      leases.apply(projectId);
    } catch (RuntimeException e) {
      LOG.warnf(
          "Could not bring the lease of project %s's desk in line: %s", projectId, e.getMessage());
    }
  }

  // --- roll requests -----------------------------------------------------------------------------

  /**
   * Ask for the desk of {@code projectId} to roll between turns (qits-1514): a front-desk settings
   * change. Answers the rows touched — 0 for a project with no desk.
   */
  public int requestRoll(String projectId, Instant now) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                desks
                    .getEntityManager()
                    .createNativeQuery(
                        "update front_desk set roll_requested_at = :now where project_id = :project")
                    .setParameter("now", now)
                    .setParameter("project", projectId)
                    .executeUpdate());
  }

  /** Drop a pending roll request: the composed spec is the applied one, nothing to roll. */
  public int clearRollRequest(String projectId) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                desks
                    .getEntityManager()
                    .createNativeQuery(
                        "update front_desk set roll_requested_at = null"
                            + " where project_id = :project and roll_requested_at is not null")
                    .setParameter("project", projectId)
                    .executeUpdate());
  }

  // --- the computed state ------------------------------------------------------------------------

  /**
   * A desk's state, from its row, its lease as qits-system last called it, and its daemon:
   *
   * <ul>
   *   <li>ABSENT — no row; FAILED — a failure detail (the lease FAILED or was refused, an
   *       unconfigured edge, no token) or a daemon's {@code ProvisionFailed};
   *   <li>QUEUED — wanted and no lease yet, or the lease QUEUED; the lease's {@code detail} (why it
   *       waits) is answered as {@code failureDetail};
   *   <li>PROVISIONING — the lease GRANTED (the target pulls and starts the image: no time limit),
   *       or wanted with a lease that is not running (EXITED or ending: a new one follows);
   *   <li>RUNNING — the lease RUNNING, and only then; STOPPED — otherwise.
   * </ul>
   *
   * <p>{@code UNAVAILABLE} (a runner gone past its grace) is never computed any more.
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
    } else if (row.leaseId == null) {
      status = row.wanted() ? AgentRuntimeStatus.QUEUED : AgentRuntimeStatus.STOPPED;
      detail = row.wanted() ? row.leaseDetail : null;
    } else if ("QUEUED".equals(row.reportedState)) {
      status = AgentRuntimeStatus.QUEUED;
      detail = row.leaseDetail;
    } else if ("GRANTED".equals(row.reportedState)) {
      status = AgentRuntimeStatus.PROVISIONING;
    } else if ("RUNNING".equals(row.reportedState)) {
      status = AgentRuntimeStatus.RUNNING;
    } else if (row.wanted()) {
      status = AgentRuntimeStatus.PROVISIONING;
    } else {
      status = AgentRuntimeStatus.STOPPED;
    }
    return new FrontDeskState(
        status, connected, version, pinned, stale, detail, row.targetId, lifecycle, row.queuedAt);
  }
}
