package eu.wohlben.qits.projects.workspacehost;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.http.QitsHttpClients;
import eu.wohlben.qits.projects.control.WorkspaceAgentDispatch;
import eu.wohlben.qits.projects.error.DomainException;
import io.quarkus.arc.DefaultBean;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The shipped {@link WorkspaceAgentDispatch}: one POST to qits-workspaces' agent-dispatch door.
 *
 * <h2>The contract</h2>
 *
 * <pre>
 *   POST {workspaces-url}/workspaces/api/agent-dispatches
 *   Authorization: Bearer &lt;machine token, audience qits-platform&gt;
 *   Content-Type: application/json
 *
 *   {"repositoryId": "…", "wrapperBranch": "ticket/qits-617",
 *    "workId": "…", "entityId": "qits-617", "entityTitle": "…",
 *    "entityStatus": "REFINED", "entityBlocked": false,
 *    "ticketId": "…", "instruction": "&lt;the agent's first turn&gt;"}
 *
 *   -&gt; 200 {"agent": {"agentId": "…", "workspaceRowId": 41|null, "repositoryId": "…", …},
 *           "fresh": true, "agentLaunch": "SCHEDULED"|"SKIPPED_RUNNING"|"QUEUED",
 *           "agentIdentity": "…"|null}
 * </pre>
 *
 * <p>qits-1152: no {@code branch}, {@code branchTree} or {@code gitRefs} any more. The far side
 * keys the agent by {@code workId}, stands its wrapper worktree on {@code wrapperBranch} and derives
 * the refs it may push from it.
 *
 * <p>Anything that is not a 200 is a dispatch that did not happen.
 *
 * <h2>A NEW class, not a second verb on {@link HttpReleasedBranchWorkspaces}</h2>
 *
 * <p>The package doc forbids growing that class a second verb, and the reason survives this change
 * intact: what travels through it is a workspace-lifecycle <em>fact</em>, announced after a release
 * that already happened, and its whole contract is that it never throws. This is a <em>request</em>
 * whose outcome a person is waiting on and whose failure has to reach them. Two verbs with opposite
 * failure contracts do not belong in one class. What is shared is the discipline, and it is copied
 * rather than inherited: {@code Map} in both directions, an instance {@link HttpClient}, the bearer
 * through {@link WorkspacesBearer}, no DTO and therefore no native-image registration.
 *
 * <h2>The address, and why there is a new key for it</h2>
 *
 * <p>{@code qits.projects.workspaces-url} is <b>this service's address for qits-workspaces</b>, and
 * it is what this hop reads. When it is unset or blank the hop falls back to
 * {@code qits.projects.release-requests.workspaces-url} — the key that already ships set, and the
 * one the deployed platform already injects — so a deployment that has configured qits-workspaces
 * once has configured it for this door too, and nothing had to be redeployed for the dispatch to
 * work.
 *
 * <p>The alternative was to read the old key and be done. It was rejected on its name: that key is
 * spelled {@code release-requests} and its whole documented job is one lifecycle call made after a
 * release, and a ticket dispatch reading it would put a second, unrelated flow behind a name that
 * lies about both. The fallback is what keeps that honesty from costing a configuration change: one
 * value to set today, and a correct name to move to. When every environment names the new key, the
 * old one narrows back to the release path it is named for.
 *
 * <h2>Failure is an answer here, not a WARN</h2>
 *
 * <p>The port's javadoc is the rule and this adapter implements exactly it: <b>503</b> when the hop
 * is not configured — no address, or no machine credential, which is a platform that cannot reach
 * qits-workspaces at all — and <b>502</b> for everything about the exchange: a non-200, an
 * unreachable or timing-out far side, an answer that will not parse or carries no workspace id.
 * There is no forwarded-header fallback, for {@link HttpReleasedBranchWorkspaces}' reason: this door
 * creates a container, and a call this service cannot authenticate is one it does not make.
 *
 * <p>That is the dispatch's rule only. The two reads beside it share one GET and two other
 * contracts: {@link #agentsFor} turns every failure into one WARN and an empty list, and {@link
 * #hasActiveAgent} into "not known" — never into "no agent", because its caller would drop a ticket
 * on that answer (qits-885).
 *
 * <p>The {@link HttpClient} is an <b>instance</b> field, not static, so a native image does not get
 * an {@code HttpClientFacade} in its build-time heap; {@code NativeImageContractTest} pins that. It
 * is wrapped with {@link QitsHttpClients}, which opens a CLIENT span and stamps {@code
 * X-Qits-Caller}.
 */
@ApplicationScoped
@DefaultBean
public class HttpWorkspaceAgentDispatch implements WorkspaceAgentDispatch {

  private static final Logger LOG = Logger.getLogger(HttpWorkspaceAgentDispatch.class);

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** How long a connect may take — qits-workspaces is a sibling service on the same network. */
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

  /**
   * The bound on the whole exchange, and it is generous: the far side may create a workspace and
   * commission credentials before it answers.
   */
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

  /**
   * The bound on a reference lookup, and it is short where the dispatch's is generous: this one sits
   * inside a page being drawn, so a far side that is slow must cost a second and a missing link —
   * never the listing itself.
   */
  private static final Duration LOOKUP_TIMEOUT = Duration.ofSeconds(3);

  private final HttpClient client;

  /** This service's address for qits-workspaces. Unset falls back to the key below. */
  @ConfigProperty(name = "qits.projects.workspaces-url")
  Optional<String> workspacesUrl;

  /** The address the release path already ships set; see the class javadoc. */
  @ConfigProperty(name = "qits.projects.release-requests.workspaces-url")
  Optional<String> releaseWorkspacesUrl;

  @Inject WorkspacesBearer bearer;

  @Inject
  public HttpWorkspaceAgentDispatch(QitsHttpClients clients) {
    this.client = clients.wrap(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
  }

  /** Shuts the client down with the bean: a leaked SelectorManager thread per instance (qits-1149). */
  @PreDestroy
  void close() {
    client.shutdownNow();
  }

  @Override
  public Dispatch dispatchAgent(
      String repositoryId, String wrapperBranch, Subject subject, String instruction) {
    String branch = wrapperBranch;
    String base =
        address()
            .orElseThrow(
                () ->
                    new DomainException(
                        503,
                        "No address for qits-workspaces is configured"
                            + " (qits.projects.workspaces-url), so no agent can be dispatched."));
    String authorization =
        bearer
            .authorization()
            .orElseThrow(
                () ->
                    new DomainException(
                        503,
                        "No machine credential for qits-workspaces is available, so no agent can"
                            + " be dispatched."));

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("repositoryId", repositoryId);
    body.put("wrapperBranch", wrapperBranch);
    // The work item, whatever its archetype: qits-workspaces keys the agent by it (qits-1152).
    if (subject != null && subject.workId() != null) {
      body.put("workId", subject.workId());
    }
    // The row as a person names it — `qits-614` — for the far side's session names (qits-614);
    // omitted, never null, when it was not rendered. A label only.
    if (subject != null && subject.qualifiedId() != null) {
      body.put("entityId", subject.qualifiedId());
    }
    // The rest of the session name, `<status square> <id> <title>` (qits-617): the title and the
    // status enum's name, each omitted when unknown, and the blocked flag that pales the square. The
    // dispatch seeds them; every later change reaches the agent through HttpWorkspaceAgentEntities.
    if (subject != null && subject.title() != null) {
      body.put("entityTitle", subject.title());
    }
    if (subject != null && subject.status() != null) {
      body.put("entityStatus", subject.status());
    }
    if (subject != null) {
      body.put("entityBlocked", subject.blocked());
    }
    // Which archetype the work item is, for the far side's links. Only the member that is set is
    // sent — an explicit null would be this hop stating a subject it does not have.
    if (subject != null && subject.ticketId() != null) {
      body.put("ticketId", subject.ticketId());
    }
    if (subject != null && subject.epicId() != null) {
      body.put("epicId", subject.epicId());
    }
    body.put("instruction", instruction);

    HttpResponse<String> response;
    try {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(base + "/workspaces/api/agent-dispatches"))
              .timeout(REQUEST_TIMEOUT)
              .header("Content-Type", "application/json")
              .header("Authorization", authorization)
              .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
              .build();
      response = client.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (InterruptedException e) {
      // Never swallow the interrupt, even though this one runs on a request thread.
      Thread.currentThread().interrupt();
      throw failed(branch, "interrupted while waiting for qits-workspaces");
    } catch (Exception e) {
      throw failed(branch, "could not reach qits-workspaces: " + e);
    }
    if (response.statusCode() != 200) {
      throw failed(
          branch, "qits-workspaces answered " + response.statusCode() + ": " + response.body());
    }
    return read(branch, response.body());
  }

  /**
   * The read back: {@code GET /workspaces/api/agent-dispatches/references?workId=…}, repeating,
   * answering {@code {"entries":[{"agent":{…}}]}} (qits-1152).
   *
   * <p>It answers <b>every</b> agent of the work items, removed ones included, each with its own
   * {@code state}. Nothing is filtered here — the port's javadoc puts that decision on each reader.
   *
   * <p><b>The path is under {@code agent-dispatches} and that is load-bearing, not tidy.</b> It sat
   * under {@code /workspaces/api/workspaces/references} for one release and answered <b>403</b> to
   * every call this class made: that far-side class is {@code @RolesAllowed("qits:admin")}, a
   * person's door, and this hop presents a machine bearer, which carries {@code qits:system}. The
   * contract below turned each 403 into an empty list, so the feature was deployed, inert, and
   * silent.
   *
   * <p><b>Nothing here throws, and that is the port's contract.</b> A missing address, a missing
   * credential, a far side that is away, a non-200 and an answer that will not parse all come out
   * the same way: one WARN and an empty list.
   */
  @Override
  public List<Agent> agentsFor(Collection<String> workIds) {
    String query = query(workIds);
    if (query.isEmpty()) {
      return List.of(); // asked about no rows — no call to make
    }
    Optional<String> base = address();
    Optional<String> authorization = bearer.authorization();
    if (base.isEmpty() || authorization.isEmpty()) {
      LOG.debugf(
          "No %s for qits-workspaces, so no agents are read",
          base.isEmpty() ? "address" : "machine credential");
      return List.of();
    }
    try {
      HttpResponse<String> response = references(base.get(), authorization.get(), query);
      if (response.statusCode() != 200) {
        LOG.warnf(
            "qits-workspaces answered %s to an agent lookup; reporting none: %s",
            response.statusCode(), response.body());
        return List.of();
      }
      return agents(response.body());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      LOG.warn("Interrupted while reading agents; reporting none");
      return List.of();
    } catch (Exception e) {
      LOG.warnf("Could not read agents, so none are reported: %s", e.toString());
      return List.of();
    }
  }

  /**
   * Whether an ACTIVE agent works on {@code workId} (qits-885): the same door as {@link #agentsFor},
   * asked about one row, with the <b>opposite reading of a failure</b> — see the port. {@code
   * Optional.of(…)} comes from a 200 that parsed and from nothing else; every other way this can
   * end is empty, which the gate-ticket close reads as "retry", never as "no agent".
   *
   * <p>The exchange is {@link #agentsFor}'s exactly — one shared {@link #references} call, the same
   * short timeout, the same path and query shape — so the consumer pact's references row covers
   * both verbs. What differs is only what each one makes of the outcome, and that is why each keeps
   * its own handling rather than sharing one that would have to choose.
   *
   * <p><b>The parse is {@link #agents}', and that is a decision with two consequences.</b> An entry
   * with no agent id or no repository id is skipped here as it is there: the far side writes both on
   * every row it holds, and an entry without them is not an agent this side could name in a comment
   * either. And a missing or blank {@code state} reads as {@link Agent#ACTIVE}, which is the {@link
   * Agent} record's own default — kept deliberately rather than overridden, because the mistake it
   * risks is the cheap one: a ticket left open with a comment saying an agent is on it, which a
   * person can drop by hand, against a drop the agent cannot undo. An answer with no {@code entries}
   * list at all is not "no agents" but an answer this side cannot read, so it is empty.
   */
  @Override
  public Optional<Boolean> hasActiveAgent(String workId) {
    if (workId == null || workId.isBlank()) {
      return Optional.of(false); // nothing is keyed by no row — no call to make
    }
    Optional<String> base = address();
    Optional<String> authorization = bearer.authorization();
    if (base.isEmpty() || authorization.isEmpty()) {
      LOG.debugf(
          "No %s for qits-workspaces, so whether an agent works on %s is not known",
          base.isEmpty() ? "address" : "machine credential", workId);
      return Optional.empty();
    }
    try {
      HttpResponse<String> response =
          references(base.get(), authorization.get(), query(List.of(workId)));
      if (response.statusCode() != 200) {
        LOG.warnf(
            "qits-workspaces answered %s to whether an agent works on %s; not known: %s",
            response.statusCode(), workId, response.body());
        return Optional.empty();
      }
      Map<?, ?> answer = MAPPER.readValue(response.body(), Map.class);
      if (answer == null || !(answer.get("entries") instanceof List<?>)) {
        LOG.warnf(
            "qits-workspaces answered no entries to whether an agent works on %s; not known: %s",
            workId, response.body());
        return Optional.empty();
      }
      return Optional.of(
          agents(answer).stream()
              // The far side was asked about this one row; an entry naming another is not about it.
              .filter(agent -> agent.workId() == null || workId.equals(agent.workId()))
              .anyMatch(Agent::active));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      LOG.warnf("Interrupted while asking whether an agent works on %s; not known", workId);
      return Optional.empty();
    } catch (Exception e) {
      LOG.warnf(
          "Could not find out whether an agent works on %s, so it is not known: %s",
          workId, e.toString());
      return Optional.empty();
    }
  }

  /**
   * The one exchange both reads make: {@code GET …/agent-dispatches/references?<query>} with the
   * machine bearer and {@link #LOOKUP_TIMEOUT}. It throws, and each caller decides what a throw
   * means — an empty list for {@link #agentsFor}, "not known" for {@link #hasActiveAgent}.
   */
  private HttpResponse<String> references(String base, String authorization, String query)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(
                URI.create(base + "/workspaces/api/agent-dispatches/references?" + query))
            .timeout(LOOKUP_TIMEOUT)
            .header("Accept", "application/json")
            .header("Authorization", authorization)
            .GET()
            .build();
    return client.send(request, HttpResponse.BodyHandlers.ofString());
  }

  /**
   * The query string, with every blank and duplicate id dropped. Empty means there is nothing to
   * ask.
   */
  private static String query(Collection<String> workIds) {
    if (workIds == null) {
      return "";
    }
    return workIds.stream()
        .filter(id -> id != null && !id.isBlank())
        .distinct()
        .map(id -> "workId=" + URLEncoder.encode(id, StandardCharsets.UTF_8))
        .collect(Collectors.joining("&"));
  }

  /**
   * The entries, read as {@code Map}s like everything else this class exchanges. An entry with no
   * agent id or no repository id is skipped rather than carried as a half-row: nobody can link to it
   * or act on it.
   */
  private static List<Agent> agents(String responseBody) throws Exception {
    return agents(MAPPER.readValue(responseBody, Map.class));
  }

  /** {@link #agents(String)} over an answer already read; a null answer throws, as it always did. */
  private static List<Agent> agents(Map<?, ?> answer) {
    if (!(answer.get("entries") instanceof List<?> entries)) {
      return List.of();
    }
    List<Agent> agents = new ArrayList<>();
    for (Object entry : entries) {
      if (entry instanceof Map<?, ?> wrapper && wrapper.get("agent") instanceof Map<?, ?> row) {
        Agent agent = agent(row);
        if (agent != null) {
          agents.add(agent);
        }
      }
    }
    return List.copyOf(agents);
  }

  /** One {@code agent} object, or {@code null} when it lacks the agent or repository id. */
  private static Agent agent(Map<?, ?> row) {
    if (!(row.get("agentId") instanceof String agentId)
        || agentId.isBlank()
        || !(row.get("repositoryId") instanceof String repositoryId)) {
      return null;
    }
    List<Branch> branches = new ArrayList<>();
    if (row.get("branches") instanceof List<?> pushed) {
      for (Object item : pushed) {
        if (item instanceof Map<?, ?> branch
            && branch.get("repositoryId") instanceof String repo
            && branch.get("branch") instanceof String name
            && !name.isBlank()) {
          branches.add(new Branch(repo, name));
        }
      }
    }
    return new Agent(
        agentId,
        row.get("workspaceRowId") instanceof Number rowId ? rowId.longValue() : null,
        repositoryId,
        string(row.get("workId")),
        string(row.get("entityId")),
        string(row.get("wrapperBranch")),
        branches,
        string(row.get("state")),
        string(row.get("runState")),
        instant(row.get("createdAt")),
        instant(row.get("removedAt")));
  }

  private static String string(Object value) {
    return value instanceof String text ? text : null;
  }

  /**
   * An ISO instant, or {@code null}. Unparseable is null too: this read may never throw, and a time
   * nobody can read is worth less than the agent it hangs off.
   */
  private static Instant instant(Object value) {
    String text = string(value);
    if (text == null || text.isBlank()) {
      return null;
    }
    try {
      return Instant.parse(text);
    } catch (DateTimeParseException e) {
      LOG.debugf("Unreadable time on an agent, reporting none: %s", text);
      return null;
    }
  }

  /**
   * The new key, then the release path's. Blank counts as unset in both: the release key documents
   * blank as "switch the call off", and a switched-off address is not one to dispatch against.
   */
  private Optional<String> address() {
    return workspacesUrl
        .filter(value -> !value.isBlank())
        .or(() -> releaseWorkspacesUrl.filter(value -> !value.isBlank()));
  }

  /**
   * The answer, read as a {@code Map}. The agent's id is the one member the caller cannot do
   * without, so an answer carrying no readable agent is a failed dispatch and not a dispatch with a
   * hole in it. The workspace row id is null while the agent is QUEUED for a slot.
   */
  private Dispatch read(String branch, String responseBody) {
    Map<?, ?> answer;
    try {
      answer = MAPPER.readValue(responseBody, Map.class);
    } catch (Exception e) {
      throw failed(branch, "qits-workspaces answered something unreadable: " + e);
    }
    if (!(answer.get("agent") instanceof Map<?, ?> row)
        || !(row.get("agentId") instanceof String agentId)
        || agentId.isBlank()) {
      throw failed(branch, "qits-workspaces answered no agent id: " + responseBody);
    }
    Long workspaceRowId =
        row.get("workspaceRowId") instanceof Number id ? id.longValue() : null;
    boolean fresh = Boolean.TRUE.equals(answer.get("fresh"));
    Object launch = answer.get("agentLaunch");
    // qits-887: who the agent's own calls are made as. Null or absent reads as "named none", never
    // as a failed dispatch: the agent is real either way.
    Object identity = answer.get("agentIdentity");
    String agentIdentity = identity instanceof String text && !text.isBlank() ? text : null;
    LOG.infof(
        "qits-workspaces dispatched agent %s onto %s in workspace %s (fresh=%s, launch=%s,"
            + " identity=%s)",
        agentId, branch, workspaceRowId, fresh, launch, agentIdentity);
    return new Dispatch(
        agentId,
        workspaceRowId,
        string(row.get("repositoryId")),
        fresh,
        launch == null ? null : launch.toString(),
        agentIdentity);
  }

  private static DomainException failed(String branch, String reason) {
    return new DomainException(502, "Could not dispatch an agent onto " + branch + ": " + reason);
  }
}
