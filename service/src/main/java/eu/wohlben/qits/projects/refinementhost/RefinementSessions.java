package eu.wohlben.qits.projects.refinementhost;

import eu.wohlben.qits.entities.control.EntityBlockState;
import eu.wohlben.qits.entities.control.EntityDispatchService;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.projects.agenthost.AgentDaemonRegistry;
import eu.wohlben.qits.projects.api.QualifiedEntityIds;
import eu.wohlben.qits.projects.entity.FrontDeskLifecycle;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.entity.Refinement;
import eu.wohlben.qits.projects.persistence.ProjectRepository;
import eu.wohlben.qits.projects.persistence.RefinementRepository;
import eu.wohlben.qits.projectsdaemon.protocol.DaemonProtocol;
import eu.wohlben.qits.projectsdaemon.protocol.SessionCredentials;
import eu.wohlben.qits.projectsdaemon.protocol.SessionReport;
import eu.wohlben.qits.projectsdaemon.protocol.Sessions;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * A project's refinement rooms as sessions of its front desk (epic qits-770): the desired {@link
 * Sessions} list this service sends the desk's daemon, and the {@link SessionReport} it answers
 * with, written back onto the rows.
 *
 * <h2>The list ({@link #compose(Project, List, Function, Instant, Duration)}, pure)</h2>
 *
 * <ul>
 *   <li>{@value SessionCredentials#FRONT_DESK_KEY}, surface {@code project.work}, {@code RUNNING} —
 *       only while the project's {@code front_desk.lifecycle} is {@code ALWAYS_ON}. On {@code
 *       ON_DEMAND} it is left out: the desk panel launches that session itself ({@code POST
 *       /agents}), and the daemon keeps it anyway while the delivery loop is on.
 *   <li>one entry per <b>session room</b> of the project ({@link Refinement#containerRoom()} false):
 *       key the entity id, surface {@code epic.chat}, {@code RUNNING} while {@code last_demand_at}
 *       is younger than {@code qits.projects.agent-idle-timeout}, else {@code STOPPED};
 *       {@code resumeSessionId} the last session id the daemon reported; the entity's facts read
 *       live. A legacy container room is never in it — its agent runs in its own container.
 * </ul>
 *
 * <h2>When it is sent ({@link #push})</h2>
 *
 * <p>Only to a daemon whose {@code hello} said capability {@value
 * AgentDaemonRegistry#SESSIONS_CAPABILITY} or later: on that hello (after the credentials), on every
 * change — a room opened, ensured, stopped or discarded, its entity moved, blocked, unblocked or
 * retitled — and every {@code qits.projects.desk-runner.estate-interval} for every connected desk.
 * Resending an unchanged list is a no-op on the daemon. A room's credential reaches the daemon
 * before its entry: the token is minted (and the credentials frame sent) before the open stamps
 * demand and pushes, and the hello sends credentials first.
 *
 * <h2>The report ({@link #onReport})</h2>
 *
 * <p>Always the full list: a named key's state, detail and command land on its row, and its session
 * id when it carries one (kept after the session stops, so the next launch resumes it); a session
 * room the report does not name is {@code STOPPED}.
 *
 * <p>Every method here <b>never throws</b> except the reads: each caller has done its own work, and
 * a missed push is caught by the next change or the periodic resend.
 */
@ApplicationScoped
public class RefinementSessions {

  private static final Logger LOG = Logger.getLogger(RefinementSessions.class);

  /** The surface a refinement room's session launches as. */
  public static final String ROOM_SURFACE = "epic.chat";

  /** The surface of the front desk's own session. Ignored by the daemon, sent for the reader. */
  public static final String FRONT_DESK_SURFACE = "project.work";

  /** How often a session's activity may re-stamp its room's demand. */
  static final Duration EVIDENCE_INTERVAL = Duration.ofSeconds(60);

  @Inject RefinementRepository store;

  @Inject ProjectRepository projects;

  @Inject EntityDispatchService entities;

  @Inject AgentDaemonRegistry daemons;

  @Inject RefinementChangePublisher changes;

  /** The session's idle window — the desk's own key, deliberately: one idle rule for the desk. */
  @ConfigProperty(name = "qits.projects.agent-idle-timeout", defaultValue = "PT4H")
  Duration idleTimeout;

  /** When each (project, command)'s activity last reached a row. */
  private final ConcurrentHashMap<String, Instant> evidenceWritten = new ConcurrentHashMap<>();

  // ---- desired state -----------------------------------------------------------------------------

  /**
   * Whether {@code row}'s session is wanted at {@code now}: a session room with demand younger than
   * {@code idle}. A zero or negative window means once asked for, wanted until stopped. Pure.
   */
  public static boolean wantsRunning(Refinement row, Instant now, Duration idle) {
    if (row == null || row.containerRoom() || row.lastDemandAt == null) {
      return false;
    }
    if (idle == null || idle.isZero() || idle.isNegative()) {
      return true;
    }
    return Duration.between(row.lastDemandAt, now).compareTo(idle) < 0;
  }

  /** {@link #wantsRunning(Refinement, Instant, Duration)} under the configured idle window. */
  public boolean wantsRunning(Refinement row, Instant now) {
    return wantsRunning(row, now, idleTimeout);
  }

  /** The project's session rooms whose session is wanted at {@code now}, oldest first. */
  public List<Refinement> running(String projectId, Instant now) {
    if (projectId == null) {
      return List.of();
    }
    return QuarkusTransaction.requiringNew().call(() -> store.listByProject(projectId)).stream()
        .filter(row -> wantsRunning(row, now))
        .toList();
  }

  // ---- the list ----------------------------------------------------------------------------------

  /**
   * The full desired list for a project. Pure: the entity facts come from {@code facts}, keyed by
   * entity id (null for an entity it cannot describe).
   */
  public static Sessions compose(
      Project project,
      List<Refinement> rooms,
      Function<String, Sessions.Entity> facts,
      Instant now,
      Duration idle) {
    List<Sessions.Session> sessions = new ArrayList<>();
    if (project != null && project.frontDeskLifecycle == FrontDeskLifecycle.ALWAYS_ON) {
      sessions.add(
          new Sessions.Session(
              SessionCredentials.FRONT_DESK_KEY,
              FRONT_DESK_SURFACE,
              DaemonProtocol.DesiredState.RUNNING,
              null,
              null));
    }
    if (rooms != null) {
      for (Refinement room : rooms) {
        if (room == null || room.containerRoom() || room.entityId == null) {
          continue;
        }
        sessions.add(
            new Sessions.Session(
                room.entityId,
                ROOM_SURFACE,
                wantsRunning(room, now, idle)
                    ? DaemonProtocol.DesiredState.RUNNING
                    : DaemonProtocol.DesiredState.STOPPED,
                room.sessionId,
                facts == null ? null : facts.apply(room.entityId)));
      }
    }
    return new Sessions(sessions);
  }

  /** The entity facts a session is named from, out of the entity row. Null for no row. */
  public static Sessions.Entity factsOf(WorkEntity entity, Project project) {
    if (entity == null) {
      return null;
    }
    EntityBlockState block = EntityBlockState.of(entity);
    return new Sessions.Entity(
        qualifiedIdOf(entity, project),
        entity.title,
        entity.status,
        block.blocked(),
        block.blocked() ? block.source() : null);
  }

  /** {@code <project slug>-<number>}, or null when either half is missing. */
  static String qualifiedIdOf(WorkEntity entity, Project project) {
    if (entity == null
        || entity.number < 1
        || project == null
        || project.slug == null
        || project.slug.isBlank()) {
      return null;
    }
    return QualifiedEntityIds.render(project.slug, entity.number);
  }

  /** {@link #compose(Project, List, Function, Instant, Duration)} over what the stores hold now. */
  public Sessions compose(String projectId) {
    return compose(projectId, Map.of());
  }

  /**
   * As {@link #compose(String)}, with {@code known} facts standing in for the live read of the
   * entities they name — what a change announcement already carries.
   */
  Sessions compose(String projectId, Map<String, Sessions.Entity> known) {
    Project project = QuarkusTransaction.requiringNew().call(() -> projects.findById(projectId));
    List<Refinement> rooms =
        QuarkusTransaction.requiringNew().call(() -> store.listByProject(projectId));
    return compose(
        project,
        rooms,
        entityId -> factsFor(entityId, known.get(entityId), project),
        Instant.now(),
        idleTimeout);
  }

  /**
   * The facts a change announced for the entity, its qualified id filled from the live row when the
   * announcement carries none; the live facts when nothing was announced.
   */
  private Sessions.Entity factsFor(String entityId, Sessions.Entity announced, Project project) {
    if (announced == null) {
      return liveFacts(entityId, project);
    }
    if (announced.qualifiedId() != null) {
      return announced;
    }
    Sessions.Entity live = liveFacts(entityId, project);
    return new Sessions.Entity(
        live == null ? null : live.qualifiedId(),
        announced.title(),
        announced.status(),
        announced.blocked(),
        announced.blockSource());
  }

  /** The entity's facts as committed now, or null when it cannot be read. */
  private Sessions.Entity liveFacts(String entityId, Project project) {
    try {
      return factsOf(entities.fresh(entityId), project);
    } catch (RuntimeException e) {
      LOG.debugf(
          "Could not read entity %s for its refinement session's name: %s",
          entityId, e.getMessage());
      return null;
    }
  }

  // ---- sending -----------------------------------------------------------------------------------

  /**
   * Send {@code projectId}'s connected desk daemon its full sessions list, if that daemon reads the
   * frame; answers whether one went out. A project with no capable daemon costs no read. <b>Never
   * throws.</b>
   */
  public boolean push(String projectId) {
    return push(projectId, Map.of());
  }

  private boolean push(String projectId, Map<String, Sessions.Entity> known) {
    if (projectId == null) {
      return false;
    }
    try {
      if (!daemons.acceptsSessions(projectId)) {
        return false;
      }
      Sessions frame = compose(projectId, known);
      boolean sent = daemons.sendSessions(projectId, frame);
      if (sent) {
        LOG.debugf(
            "Sent project %s's desk daemon its sessions: %s",
            projectId,
            frame.sessions().isEmpty()
                ? "none"
                : frame.sessions().stream()
                    .map(session -> session.key() + " " + session.state())
                    .collect(Collectors.joining(", ")));
      }
      return sent;
    } catch (RuntimeException e) {
      LOG.warnf(
          "Could not send project %s's desk daemon its sessions: %s",
          projectId, e.getClass().getSimpleName());
      return false;
    }
  }

  /**
   * The entity of a session room moved, was blocked or unblocked, or retitled: its daemon is sent
   * the list again with {@code facts} for it. Answers whether {@code entityId} has a session room
   * at all — false sends nothing, and a legacy container room is told down its own tunnel instead.
   * <b>Never throws.</b>
   */
  public boolean entityChanged(Refinement room, Sessions.Entity facts) {
    if (room == null || room.containerRoom()) {
      return false;
    }
    push(room.projectId, facts == null ? Map.of() : Map.of(room.entityId, facts));
    return true;
  }

  /** The safety net: every capable desk daemon is sent its list again. Answers how many were. */
  public int pushAll() {
    int sent = 0;
    for (String projectId : daemons.projectsAcceptingSessions()) {
      if (push(projectId)) {
        sent++;
      }
    }
    return sent;
  }

  @Scheduled(
      every = "{qits.projects.desk-runner.estate-interval}",
      concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
  void onInterval() {
    if (LaunchMode.current() != LaunchMode.NORMAL) {
      return;
    }
    try {
      pushAll();
    } catch (RuntimeException e) {
      LOG.warn("The refinement sessions resend failed — retried on the next interval.", e);
    }
  }

  // ---- the report --------------------------------------------------------------------------------

  /**
   * Write {@code report} onto {@code projectId}'s session rooms: a named key's state, detail and
   * command, its session id when it carries one; an unnamed room is {@code STOPPED}. Fires the
   * room's {@code PROCESS} hint when anything on it moved. <b>Never throws.</b>
   */
  public void onReport(String projectId, SessionReport report) {
    if (projectId == null || report == null) {
      return;
    }
    List<Long> moved;
    try {
      moved = QuarkusTransaction.requiringNew().call(() -> apply(projectId, report));
    } catch (RuntimeException e) {
      LOG.warnf(
          "Could not record project %s's session report: %s", projectId, e.getMessage());
      return;
    }
    for (Long id : moved) {
      changes.fire(id, RefinementChangeHint.Topic.PROCESS);
    }
  }

  /** The write half of {@link #onReport}, inside its transaction; answers the rows that moved. */
  List<Long> apply(String projectId, SessionReport report) {
    List<Long> moved = new ArrayList<>();
    for (Refinement room : store.listByProject(projectId)) {
      if (room.containerRoom()) {
        continue;
      }
      Optional<SessionReport.Session> entry = report.find(room.entityId);
      String state = entry.map(SessionReport.Session::state).orElse(DaemonProtocol.ReportedState.STOPPED);
      String detail = entry.map(SessionReport.Session::detail).orElse(null);
      String command = entry.map(SessionReport.Session::commandId).orElse(null);
      String session =
          entry.map(SessionReport.Session::sessionId).filter(id -> !id.isBlank()).orElse(null);
      boolean changed =
          !Objects.equals(room.sessionState, state)
              || !Objects.equals(room.sessionDetail, detail)
              || !Objects.equals(room.sessionCommandId, command)
              || (session != null && !session.equals(room.sessionId));
      if (!changed) {
        continue;
      }
      room.sessionState = state;
      room.sessionDetail = detail;
      room.sessionCommandId = command;
      if (session != null) {
        room.sessionId = session;
      }
      moved.add(room.id);
    }
    return moved;
  }

  // ---- evidence of use ---------------------------------------------------------------------------

  /**
   * The desk's daemon said something about {@code commandId} that evidences use: the session room it
   * runs, if any, has its demand re-stamped — throttled to one write per command per {@link
   * #EVIDENCE_INTERVAL}, written off the caller's thread, and only while the room is still wanted (a
   * session saying goodbye after a stop must not wake it again). <b>Never throws.</b>
   */
  public void evidenced(String projectId, String commandId) {
    evidenced(projectId, commandId, Instant.now());
  }

  void evidenced(String projectId, String commandId, Instant now) {
    if (projectId == null || commandId == null || commandId.isBlank()) {
      return;
    }
    Instant written =
        evidenceWritten.compute(
            projectId + "/" + commandId,
            (key, last) -> last != null && last.plus(EVIDENCE_INTERVAL).isAfter(now) ? last : now);
    if (written != now) {
      return;
    }
    Duration idle = idleTimeout;
    Instant floor =
        idle == null || idle.isZero() || idle.isNegative() ? Instant.EPOCH : now.minus(idle);
    Thread.ofVirtual()
        .name("qits-refinement-session-demand")
        .start(
            () -> {
              try {
                QuarkusTransaction.requiringNew()
                    .run(
                        () ->
                            store.update(
                                "lastDemandAt = ?1 where projectId = ?2 and sessionCommandId = ?3"
                                    + " and branch is null and lastDemandAt > ?4",
                                now,
                                projectId,
                                commandId,
                                floor));
              } catch (RuntimeException e) {
                LOG.debugf(
                    "Could not stamp demand on the refinement session of %s: %s",
                    commandId, e.getMessage());
              }
            });
  }
}
