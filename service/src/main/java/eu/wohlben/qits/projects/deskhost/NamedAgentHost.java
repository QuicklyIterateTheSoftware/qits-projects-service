package eu.wohlben.qits.projects.deskhost;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.projects.agenthost.AgentDaemonRegistry;
import eu.wohlben.qits.projects.agenthost.DaemonApiClient;
import eu.wohlben.qits.projects.agenthost.FrontDeskPrompter;
import eu.wohlben.qits.projects.control.NamedAgents;
import eu.wohlben.qits.projects.entity.FrontDesk;
import eu.wohlben.qits.projects.entity.NamedAgent;
import eu.wohlben.qits.projects.persistence.NamedAgentRepository;
import eu.wohlben.qits.projects.refinementhost.RefinementSessions;
import eu.wohlben.qits.projectsdaemon.protocol.DaemonProtocol;
import eu.wohlben.qits.projectsdaemon.protocol.SessionReport;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * <b>A project's named agents</b> (qits-2154): kept Claude sessions in the project's front desk
 * container beside the desk's own, each on its own surface and its own credential, prompted by
 * name. The registry is {@link NamedAgents}; {@code desk} is the front desk itself and is answered
 * from its own row and routes ({@code api/ProjectAgentsController} delegates to those), so
 * everything here is about the others — {@code ui-builder} first.
 *
 * <h2>What a prompt does ({@link #prompt})</h2>
 *
 * <ol>
 *   <li>The agent's row is created if absent, with its own {@code named-agent} token ({@link
 *       #ensureRow}); a fresh token re-sends the desk's daemon its session credentials.
 *   <li>The agent's demand is stamped, and so is the desk's: the desk is <b>ensured</b> ({@link
 *       FrontDesks#ensure}) exactly as the agent-container door ensures it, so prompting an agent
 *       starts a stopped desk container. Named agents never take a lease of their own: they run on
 *       the desk's one DESK lease.
 *   <li>The desk's daemon is sent the sessions frame, which now names the agent ({@link
 *       RefinementSessions#push}).
 *   <li>With a daemon of capability {@value AgentDaemonRegistry#NAMED_AGENTS_CAPABILITY} connected,
 *       the prompt goes to its {@code POST named-agents/{name}/prompt} and the answer passes
 *       through ({@link FrontDeskPrompter#classify}'s reading). With none connected — the container
 *       stopped or still starting — it is stored as the row's {@code pending_prompt} and the answer
 *       is a <b>202 {@code STARTING}</b>; so it is when the daemon has not taken the frame naming
 *       the agent yet (its 404). One prompt waits at a time: a second one is a 409.
 * </ol>
 *
 * <p><b>A pending prompt is delivered</b> ({@link #deliverPending}) once the daemon is connected at
 * capability 4 and has been sent the frame naming the agent: after its {@code hello} (credentials,
 * then sessions, then this), on every {@link SessionReport} that names an agent, and every {@code
 * qits.projects.desk.sessions-resend-interval} as the safety net. The daemon queues a prompt while
 * the agent's session is still {@code STARTING}, so it is typed into the session's first turn. It is
 * cleared only once the daemon took it.
 *
 * <h2>Its session</h2>
 *
 * <p>Asked for in the sessions frame while its {@code last_demand_at} is inside {@code
 * qits.projects.agent-idle-timeout} (the desk's own idle rule), resumed from the session id the
 * daemon last reported for {@code agent:<name>} — both in {@link RefinementSessions}. Its {@code
 * AgentActivity} re-stamps that demand ({@link #evidenced}), throttled, so an agent in use is not
 * stopped under a person; the desk's demand is stamped by the same frame, as for every session of
 * the container.
 *
 * <h2>Its listing ({@link #list})</h2>
 *
 * <p>One vocabulary for every agent, the desk included: {@code STOPPED} (no session, nothing asked
 * for), {@code STARTING} (asked for, not running yet — the container coming up, or the session
 * launching), {@code IDLE} and {@code BUSY} (running, between turns or in one — {@code WAITING}
 * reads as {@code BUSY}: the turn is not over), {@code FAILED} (the daemon could not run it).
 *
 * <p>Every method that a socket frame or a sweep calls <b>never throws</b>.
 */
@ApplicationScoped
public class NamedAgentHost {

  private static final Logger LOG = Logger.getLogger(NamedAgentHost.class);

  /** The listing's vocabulary. */
  public static final String STOPPED = "STOPPED";

  public static final String STARTING = "STARTING";

  public static final String IDLE = "IDLE";

  public static final String BUSY = "BUSY";

  public static final String FAILED = "FAILED";

  /** The daemon's routes, under its proxied base path (see {@code ContainerProxyPath}). */
  static final String LIST_ROUTE = "named-agents";

  static final String DESK_LAST_ANSWER_ROUTE = "front-desk/last-answer";

  /** How often a session's activity may re-stamp its agent's demand. */
  static final Duration EVIDENCE_INTERVAL = Duration.ofSeconds(60);

  @Inject NamedAgentRepository rows;

  @Inject FrontDeskTokens tokens;

  @Inject FrontDesks frontDesks;

  @Inject DeskSessionCredentials credentials;

  @Inject RefinementSessions sessions;

  @Inject AgentDaemonRegistry daemons;

  @Inject DaemonApiClient daemon;

  @Inject FrontDeskPrompter prompter;

  @Inject ObjectMapper json;

  /** The front desk's own bound on one proxied call — the same key, so every hop agrees. */
  @ConfigProperty(name = "qits.projects.front-desk.relay-timeout-ms", defaultValue = "10000")
  long timeoutMs;

  /** The desk's idle window, which is every named agent's too. */
  @ConfigProperty(name = "qits.projects.agent-idle-timeout", defaultValue = "PT4H")
  Duration idleTimeout;

  /** Which agent each command runs, by {@code <projectId>/<commandId>}, from the reports. */
  private final ConcurrentHashMap<String, String> commandAgents = new ConcurrentHashMap<>();

  /** When each (project, agent)'s activity last reached its row. */
  private final ConcurrentHashMap<String, Instant> evidenceWritten = new ConcurrentHashMap<>();

  /** The (project, agent)s whose pending prompt is being delivered right now. */
  private final Set<String> delivering = ConcurrentHashMap.newKeySet();

  /**
   * One named agent as the listing answers it.
   *
   * @param name the registry's name
   * @param surface the agent surface its session launches as
   * @param state {@link #STOPPED}, {@link #STARTING}, {@link #IDLE}, {@link #BUSY} or {@link
   *     #FAILED}
   * @param sessionId its harness session — the one the next launch resumes — or null before it
   *     ever ran
   * @param commandId the daemon's command running it now, for the terminal proxy {@code
   *     /projects/container/{projectId}/terminal/commands/{commandId}}; null while none runs
   */
  public record AgentState(
      String name, String surface, String state, String sessionId, String commandId) {}

  /** What the daemon's {@code GET named-agents} said about one agent. */
  record Hosted(String state, String commandId, String sessionId) {}

  // ---- the listing -------------------------------------------------------------------------------

  /**
   * Every named agent of {@code projectId}, in the registry's order — the desk first, then every
   * other, prompted or not (one nobody prompted reads {@link #STOPPED}). Asks the desk's daemon at
   * most twice (the desk's last answer, the named agents' listing) and reads every failure as the
   * state the rows alone say.
   */
  public List<AgentState> list(String projectId) {
    FrontDesk desk = frontDesks.find(projectId).orElse(null);
    boolean live = daemons.isDaemonLive(projectId);
    Map<String, NamedAgent> byName = new HashMap<>();
    for (NamedAgent row :
        QuarkusTransaction.requiringNew().call(() -> rows.listByProject(projectId))) {
      byName.put(row.name, row);
    }
    Map<String, Hosted> hosted =
        live && daemons.acceptsNamedAgents(projectId) ? hosted(projectId) : Map.of();
    Instant now = Instant.now();
    List<AgentState> answer = new ArrayList<>();
    for (NamedAgents.Agent agent : NamedAgents.ALL) {
      if (agent.desk()) {
        answer.add(deskState(projectId, agent, desk, live));
      } else {
        NamedAgent row = byName.get(agent.name());
        Hosted entry = hosted.get(agent.name());
        String activity =
            entry == null
                ? null
                : daemons
                    .sessionActivity(projectId, entry.sessionId(), entry.commandId())
                    .orElse(null);
        answer.add(
            state(
                agent,
                row,
                entry,
                activity,
                desk != null && desk.wanted(),
                RefinementSessions.wantsRunning(row, now, idleTimeout)));
      }
    }
    return answer;
  }

  /**
   * A named agent's state from its row (null before its first prompt), the daemon's word on it
   * (null when the daemon did not name it, or was not asked), the activity its session last
   * reported, and whether the desk and the agent's session are wanted. Pure.
   */
  static AgentState state(
      NamedAgents.Agent agent,
      NamedAgent row,
      Hosted hosted,
      String activity,
      boolean deskWanted,
      boolean sessionWanted) {
    String sessionId = row == null ? null : row.sessionId;
    if (hosted != null) {
      String session = hosted.sessionId() != null ? hosted.sessionId() : sessionId;
      return new AgentState(
          agent.name(),
          agent.surface(),
          fromDaemon(hosted.state(), activity),
          session,
          hosted.commandId());
    }
    boolean waiting = row != null && (row.pendingPrompt != null || sessionWanted);
    return new AgentState(
        agent.name(), agent.surface(), waiting && deskWanted ? STARTING : STOPPED, sessionId, null);
  }

  /** The daemon's session state ({@code DaemonProtocol.ReportedState}) in the listing's words. */
  static String fromDaemon(String reported, String activity) {
    if (reported == null) {
      return STOPPED;
    }
    return switch (reported) {
      case DaemonProtocol.ReportedState.RUNNING -> busy(activity) ? BUSY : IDLE;
      case DaemonProtocol.ReportedState.STARTING -> STARTING;
      case DaemonProtocol.ReportedState.FAILED -> FAILED;
      default -> STOPPED;
    };
  }

  /** Whether an agent state ({@code DaemonProtocol.AgentState}) means a turn is not over. */
  static boolean busy(String activity) {
    return DaemonProtocol.AgentState.BUSY.equals(activity)
        || DaemonProtocol.AgentState.WAITING.equals(activity);
  }

  /**
   * The desk as a named agent: not connected, {@link #STARTING} while the desk is wanted else
   * {@link #STOPPED}; connected, its kept command's last answer says which command runs it and
   * whether it is in a turn — the daemon's own 404 (no kept command) is {@link #STOPPED}, anything
   * else it cannot answer yet {@link #STARTING}.
   */
  private AgentState deskState(
      String projectId, NamedAgents.Agent agent, FrontDesk desk, boolean live) {
    String sessionId = desk == null ? null : desk.sessionId;
    if (!live) {
      return new AgentState(
          agent.name(),
          agent.surface(),
          desk != null && desk.wanted() ? STARTING : STOPPED,
          sessionId,
          null);
    }
    try {
      DaemonApiClient.Answer answer = daemon.get(projectId, DESK_LAST_ANSWER_ROUTE, timeoutMs);
      JsonNode node = readTree(answer.body());
      if (answer.status() == 200 && node != null) {
        String commandId = text(node, "commandId");
        String session = text(node, "sessionId");
        String activity =
            daemons
                .sessionActivity(projectId, session, commandId)
                .orElse(text(node, "state"));
        return new AgentState(
            agent.name(),
            agent.surface(),
            busy(activity) ? BUSY : IDLE,
            session != null ? session : sessionId,
            commandId);
      }
      if (answer.status() == 404 && node != null && node.hasNonNull("reason")) {
        return new AgentState(agent.name(), agent.surface(), STOPPED, sessionId, null);
      }
    } catch (DaemonApiClient.Unreachable e) {
      LOG.debugf("Could not ask project %s's desk for its state: %s", projectId, e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    return new AgentState(agent.name(), agent.surface(), STARTING, sessionId, null);
  }

  /** The daemon's {@code GET named-agents}, by name; empty when it could not be read. */
  private Map<String, Hosted> hosted(String projectId) {
    try {
      DaemonApiClient.Answer answer = daemon.get(projectId, LIST_ROUTE, timeoutMs);
      JsonNode node = answer.status() == 200 ? readTree(answer.body()) : null;
      if (node == null || !node.path("agents").isArray()) {
        LOG.debugf(
            "The desk daemon of project %s answered %d for %s",
            projectId, Integer.valueOf(answer.status()), LIST_ROUTE);
        return Map.of();
      }
      Map<String, Hosted> hosted = new HashMap<>();
      for (JsonNode entry : node.path("agents")) {
        String name = text(entry, "name");
        if (name != null) {
          hosted.put(
              name,
              new Hosted(text(entry, "state"), text(entry, "commandId"), text(entry, "sessionId")));
        }
      }
      return hosted;
    } catch (DaemonApiClient.Unreachable e) {
      LOG.debugf("Could not list project %s's named agents: %s", projectId, e.getMessage());
      return Map.of();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Map.of();
    }
  }

  // ---- the prompt --------------------------------------------------------------------------------

  /**
   * Hand {@code projectId}'s named agent {@code agent} (never the desk) the prompt {@code text}, as
   * the class javadoc says: the row and its token if absent, both demands stamped and the desk
   * ensured, the sessions sent, then the prompt forwarded — or stored for the daemon to come. The
   * caller has checked the text, the project and the name.
   */
  public FrontDeskPrompter.Reply prompt(String projectId, NamedAgents.Agent agent, String text) {
    Instant now = Instant.now();
    ensureRow(projectId, agent);
    NamedAgent row = QuarkusTransaction.requiringNew().call(() -> rows.find(projectId, agent.name()));
    if (row == null) {
      return new FrontDeskPrompter.Reply(
          503, null, "The named agent " + agent.name() + " could not be created; try again.");
    }
    if (row.pendingPrompt != null) {
      if (RefinementSessions.wantsRunning(row, now, idleTimeout)) {
        return new FrontDeskPrompter.Reply(
            409,
            null,
            "A prompt is already waiting for "
                + agent.name()
                + " to start; it is delivered once its session is up. Try again then.");
      }
      // Waited a whole idle window and was never delivered: the session it waited for is not
      // asked for any more, so it never will be. The new prompt replaces it.
      LOG.infof(
          "Dropping the undelivered prompt project %s's %s held since before its idle window",
          projectId, agent.name());
      clearPending(projectId, agent.name(), row.pendingPrompt);
    }
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                rows.update(
                    "lastDemandAt = ?1 where projectId = ?2 and name = ?3",
                    now,
                    projectId,
                    agent.name()));
    // The desk's own demand, and its container: the agent-container ensure door's path exactly.
    frontDesks.ensure(projectId);
    sessions.push(projectId);
    if (!daemons.isDaemonLive(projectId)) {
      return pend(projectId, agent, text);
    }
    if (!daemons.acceptsNamedAgents(projectId)) {
      if (daemons.lookup(projectId).map(i -> i.capabilityVersion() == 0).orElse(true)) {
        return pend(projectId, agent, text); // connected, but it has not said hello yet
      }
      return new FrontDeskPrompter.Reply(
          409,
          null,
          "The project's front desk runs a daemon too old to host named agents; it is rolled onto"
              + " the current one once it is quiet.");
    }
    String route = promptRoute(agent.name());
    DaemonApiClient.Answer answer;
    try {
      answer = daemon.post(projectId, route, body(text), timeoutMs);
    } catch (DaemonApiClient.Unreachable e) {
      if (e.noTunnel()) {
        return pend(projectId, agent, text);
      }
      return prompter.unreachable(projectId, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new FrontDeskPrompter.Reply(
          503, null, "Interrupted while asking the project's front desk.");
    }
    FrontDeskPrompter.Reply reply = prompter.classify(projectId, route, true, answer);
    if (reply.status() == 404 && reply.verbatim() != null) {
      // The daemon has not taken the frame naming the agent yet (the frame and this request travel
      // on different connections): it waits like a prompt to a container still starting.
      return pend(projectId, agent, text);
    }
    return reply;
  }

  /**
   * Store the prompt as the agent's pending one and answer 202 {@code STARTING}; a 409 when another
   * one got there first.
   */
  private FrontDeskPrompter.Reply pend(String projectId, NamedAgents.Agent agent, String text) {
    int stored =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    rows.update(
                        "pendingPrompt = ?1 where projectId = ?2 and name = ?3"
                            + " and pendingPrompt is null",
                        text,
                        projectId,
                        agent.name()));
    if (stored == 0) {
      return new FrontDeskPrompter.Reply(
          409,
          null,
          "A prompt is already waiting for "
              + agent.name()
              + " to start; it is delivered once its session is up. Try again then.");
    }
    LOG.infof(
        "Holding a prompt for project %s's named agent %s until its session is up",
        projectId, agent.name());
    // A daemon that connected meanwhile is handed it at once; otherwise its hello does.
    deliverPendingLater(projectId);
    return new FrontDeskPrompter.Reply(202, STARTING_BODY, null);
  }

  /** The 202 a prompt answers when it waits for the agent's session: the daemon's own shape. */
  static final String STARTING_BODY =
      "{\"commandId\":null,\"state\":\"STARTING\",\"delivered\":false,\"queued\":true,"
          + "\"position\":null}";

  // ---- the rows and their tokens -----------------------------------------------------------------

  /**
   * The row of {@code projectId}'s agent, created if absent, with its token: minted outside any
   * transaction when the row holds none and stored only on a row that still holds none (a lost race,
   * or a row deleted meanwhile, revokes what was minted). A failed mint is a WARN: the agent's
   * session fails closed on the missing credential until the next prompt mints it. A fresh token
   * re-sends the desk's daemon its session credentials.
   */
  public void ensureRow(String projectId, NamedAgents.Agent agent) {
    String name = agent.name();
    if (QuarkusTransaction.requiringNew().call(() -> rows.find(projectId, name)) == null) {
      try {
        QuarkusTransaction.requiringNew()
            .run(
                () -> {
                  if (rows.find(projectId, name) == null) {
                    NamedAgent row = new NamedAgent();
                    row.projectId = projectId;
                    row.name = name;
                    row.surface = agent.surface();
                    row.createdAt = Instant.now();
                    rows.persist(row);
                  }
                });
        LOG.infof("Created the named agent %s of project %s", name, projectId);
      } catch (RuntimeException raced) {
        if (QuarkusTransaction.requiringNew().call(() -> rows.find(projectId, name)) == null) {
          throw raced;
        }
      }
    }
    NamedAgent row = QuarkusTransaction.requiringNew().call(() -> rows.find(projectId, name));
    if (row == null || row.tokenId != null) {
      return;
    }
    FrontDeskTokens.Minted minted;
    try {
      minted = tokens.mintNamedAgent(projectId, name);
    } catch (RuntimeException e) {
      LOG.warnf(
          "Could not mint the token of project %s's named agent %s: %s",
          projectId, name, e.getMessage());
      return;
    }
    int stored =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    rows.update(
                        "tokenId = ?1, tokenValue = ?2, tokenSubject = ?3"
                            + " where projectId = ?4 and name = ?5 and tokenId is null",
                        minted.tokenId(),
                        minted.token(),
                        minted.subject(),
                        projectId,
                        name));
    if (stored == 0) {
      tokens.revokeNamedAgent(minted.tokenId());
      return;
    }
    LOG.infof("Minted the token of project %s's named agent %s", projectId, name);
    credentials.push(projectId);
  }

  /**
   * Remove every named agent of {@code projectId} — its project is being deleted: the rows deleted
   * and their tokens revoked (a failed revoke is a WARN; the token reconcile reaps it). Never
   * throws.
   */
  public void removeAll(String projectId) {
    List<NamedAgent> gone;
    try {
      gone =
          QuarkusTransaction.requiringNew()
              .call(
                  () -> {
                    List<NamedAgent> listed = rows.listByProject(projectId);
                    listed.forEach(rows::delete);
                    return listed;
                  });
    } catch (RuntimeException e) {
      LOG.warnf("Could not remove the named agents of project %s: %s", projectId, e.getMessage());
      return;
    }
    for (NamedAgent row : gone) {
      if (row.tokenId != null && !tokens.revokeNamedAgent(row.tokenId)) {
        LOG.warnf(
            "Could not revoke the token of project %s's named agent %s; the token reconcile reaps"
                + " it",
            projectId, row.name);
      }
    }
  }

  // ---- the pending prompt ------------------------------------------------------------------------

  /** {@link #deliverPending} on a virtual thread of its own: what a socket frame calls. */
  public void deliverPendingLater(String projectId) {
    if (projectId == null || !daemons.acceptsNamedAgents(projectId)) {
      return;
    }
    Thread.ofVirtual()
        .name("qits-named-agent-delivery")
        .start(
            () -> {
              try {
                deliverPending(projectId);
              } catch (RuntimeException e) {
                LOG.warnf(
                    "Could not deliver project %s's pending named-agent prompts: %s",
                    projectId, e.getMessage());
              }
            });
  }

  /**
   * Hand the desk's daemon every prompt of {@code projectId}'s named agents that waited for it,
   * once it hosts named agents; answers how many it took. A prompt is cleared only when the daemon
   * took it (200) or called it malformed (400, which asking again will not mend); anything else —
   * the agent not in its frame yet (404), not running (409), a full queue (429), a hop that failed —
   * leaves it for the next report or pass. One delivery per agent at a time.
   */
  public int deliverPending(String projectId) {
    if (projectId == null || !daemons.acceptsNamedAgents(projectId)) {
      return 0;
    }
    List<NamedAgent> waiting =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    rows.list(
                        "projectId = ?1 and pendingPrompt is not null", projectId));
    int delivered = 0;
    for (NamedAgent row : waiting) {
      String key = projectId + "/" + row.name;
      if (!delivering.add(key)) {
        continue;
      }
      try {
        if (deliver(projectId, row.name, row.pendingPrompt)) {
          delivered++;
        }
      } finally {
        delivering.remove(key);
      }
    }
    return delivered;
  }

  private boolean deliver(String projectId, String name, String text) {
    String route = promptRoute(name);
    DaemonApiClient.Answer answer;
    try {
      answer = daemon.post(projectId, route, body(text), timeoutMs);
    } catch (DaemonApiClient.Unreachable e) {
      LOG.debugf(
          "Could not deliver project %s's pending prompt to %s yet: %s",
          projectId, name, e.getMessage());
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
    if (answer.status() == 200) {
      clearPending(projectId, name, text);
      LOG.infof("Delivered project %s's pending prompt to its named agent %s", projectId, name);
      return true;
    }
    if (answer.status() == 400) {
      clearPending(projectId, name, text);
      LOG.warnf(
          "The desk daemon of project %s refused the pending prompt to %s as malformed: %s",
          projectId, name, answer.body());
      return false;
    }
    LOG.debugf(
        "The desk daemon of project %s answered %d to %s's pending prompt; it waits",
        projectId, Integer.valueOf(answer.status()), name);
    return false;
  }

  private void clearPending(String projectId, String name, String text) {
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                rows.update(
                    "pendingPrompt = null where projectId = ?1 and name = ?2 and pendingPrompt = ?3",
                    projectId,
                    name,
                    text));
  }

  /** The safety net: every project with a prompt waiting and a capable daemon is tried again. */
  @Scheduled(
      every = "{qits.projects.desk.sessions-resend-interval}",
      concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
  void onInterval() {
    if (LaunchMode.current() != LaunchMode.NORMAL) {
      return;
    }
    try {
      List<String> projects =
          QuarkusTransaction.requiringNew()
              .call(
                  () ->
                      rows.list("pendingPrompt is not null").stream()
                          .map(row -> row.projectId)
                          .distinct()
                          .toList());
      projects.forEach(this::deliverPending);
    } catch (RuntimeException e) {
      LOG.warn("The named agents' pending prompt pass failed — retried on the next interval.", e);
    }
  }

  // ---- the daemon's frames -----------------------------------------------------------------------

  /**
   * A {@link SessionReport} from {@code projectId}'s daemon: which command runs each named agent is
   * remembered (for {@link #evidenced}), and a report naming any named agent means the frame naming
   * it arrived, so a prompt waiting for it is delivered. <b>Never throws.</b>
   */
  public void onReport(String projectId, SessionReport report) {
    if (projectId == null || report == null || report.sessions() == null) {
      return;
    }
    boolean namesAnAgent = false;
    for (SessionReport.Session entry : report.sessions()) {
      Optional<String> name = entry == null ? Optional.empty() : NamedAgents.nameOfKey(entry.key());
      if (name.isEmpty()) {
        continue;
      }
      namesAnAgent = true;
      if (entry.commandId() != null && !entry.commandId().isBlank()) {
        commandAgents.put(projectId + "/" + entry.commandId(), name.get());
      }
    }
    if (namesAnAgent) {
      deliverPendingLater(projectId);
    }
  }

  /**
   * The desk's daemon said something about {@code commandId} that evidences use: the named agent it
   * runs, if any, has its demand re-stamped — throttled to one write per agent per {@link
   * #EVIDENCE_INTERVAL}, written off the caller's thread, and only while its session is still
   * wanted (a session saying goodbye after its window closed must not wake it again). <b>Never
   * throws.</b>
   */
  public void evidenced(String projectId, String commandId) {
    evidenced(projectId, commandId, Instant.now());
  }

  void evidenced(String projectId, String commandId, Instant now) {
    if (projectId == null || commandId == null || commandId.isBlank()) {
      return;
    }
    String name = commandAgents.get(projectId + "/" + commandId);
    if (name == null) {
      return;
    }
    Instant written =
        evidenceWritten.compute(
            projectId + "/" + name,
            (key, last) -> last != null && last.plus(EVIDENCE_INTERVAL).isAfter(now) ? last : now);
    if (written != now) {
      return;
    }
    Duration idle = idleTimeout;
    Instant floor =
        idle == null || idle.isZero() || idle.isNegative() ? Instant.EPOCH : now.minus(idle);
    Thread.ofVirtual()
        .name("qits-named-agent-demand")
        .start(
            () -> {
              try {
                QuarkusTransaction.requiringNew()
                    .run(
                        () ->
                            rows.update(
                                "lastDemandAt = ?1 where projectId = ?2 and name = ?3"
                                    + " and lastDemandAt > ?4",
                                now,
                                projectId,
                                name,
                                floor));
              } catch (RuntimeException e) {
                LOG.debugf(
                    "Could not stamp demand on project %s's named agent %s: %s",
                    projectId, name, e.getMessage());
              }
            });
  }

  // ---- the last answer ---------------------------------------------------------------------------

  /**
   * {@code projectId}'s named agent {@code agent}'s last answer, as the daemon's {@code GET
   * named-agents/{name}/last-answer} gives it — its 200, 400 and 404 passed through — or, as the
   * desk's door answers, a 503 with no daemon connected and a 409 for one too old to host named
   * agents.
   */
  public FrontDeskPrompter.Reply lastAnswer(String projectId, NamedAgents.Agent agent) {
    if (!daemons.isDaemonLive(projectId)) {
      return new FrontDeskPrompter.Reply(
          503, null, "No front desk daemon is connected for project " + projectId + ".");
    }
    if (!daemons.acceptsNamedAgents(projectId)) {
      return new FrontDeskPrompter.Reply(
          409,
          null,
          "The project's front desk runs a daemon too old to host named agents; it is rolled onto"
              + " the current one once it is quiet.");
    }
    String route = LIST_ROUTE + "/" + agent.name() + "/last-answer";
    try {
      return prompter.classify(projectId, route, true, daemon.get(projectId, route, timeoutMs));
    } catch (DaemonApiClient.Unreachable e) {
      return prompter.unreachable(projectId, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new FrontDeskPrompter.Reply(
          503, null, "Interrupted while asking the project's front desk.");
    }
  }

  // ---- helpers -----------------------------------------------------------------------------------

  static String promptRoute(String name) {
    return LIST_ROUTE + "/" + name + "/prompt";
  }

  private String body(String text) {
    try {
      return json.writeValueAsString(Map.of("text", text));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private JsonNode readTree(String body) {
    if (body == null || body.isBlank()) {
      return null;
    }
    try {
      return json.readTree(body);
    } catch (Exception e) {
      return null;
    }
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node == null ? null : node.get(field);
    return value == null || value.isNull() ? null : value.asText();
  }
}
