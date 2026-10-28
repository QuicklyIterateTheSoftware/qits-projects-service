package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.entity.ReleaseRequest;
import eu.wohlben.qits.projects.entity.ReleaseRequestFailureNotice;
import eu.wohlben.qits.projects.entity.ReleaseRequestSource;
import eu.wohlben.qits.projects.persistence.ReleaseRequestParticipantRepository;
import eu.wohlben.qits.projects.persistence.ReleaseRequestSourceRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jboss.logging.Logger;

/**
 * <b>An agent hears when its release request fails its build</b> (qits-1672).
 *
 * <p>When a fold is REJECTED because a CI run or a release-request automation FAILED, every agent
 * that filed the request or added a source to it gets one message: {@code fyi: your <qualified id>
 * failed its build}. Persons, the desk and qits-maintenance get nothing: only a workspace agent's
 * token subject names an agent.
 *
 * <p><b>Once per fold.</b> {@link #owe} runs in the transaction that rejects the fold and inserts
 * one {@link ReleaseRequestFailureNotice} per (request, fold, agent) with {@code on conflict do
 * nothing}. A retry that fails the same fold again inserts nothing, so nothing is said twice.
 *
 * <p><b>Not lost.</b> The row is the outbox. After the commit a worker delivers it; the sweep ({@link
 * #deliverOwed}) delivers whatever a crash or an unreachable qits-workspaces left. A delivery first
 * claims the row, so the worker and the sweep never both send it.
 *
 * <p><b>Agent from subject.</b> An agent's token subject is {@code
 * tok-workspace-agent-<first three groups of its id>-<random>} (qits-idp cuts the context {@code
 * agent-<id>} to 24 characters). The key is matched against the ACTIVE agents qits-workspaces
 * lists. An agent that is not ACTIVE (OBSOLETE, REMOVED or unknown) is skipped, since delivery
 * reaches only a work item's ACTIVE agent. The message goes through the same API as {@code qits
 * workspaces agents prompt}: queued, said at once if the agent is idle, else after its turn.
 */
@ApplicationScoped
public class ReleaseRequestFailureNotices {

  private static final Logger LOG = Logger.getLogger(ReleaseRequestFailureNotices.class);

  /** A workspace agent's token subject, and the agent key it carries. */
  static final Pattern AGENT_SUBJECT =
      Pattern.compile("^tok-workspace-agent-([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4})-.+$");

  /** How long a claim holds before another delivery may take the row. */
  static final Duration CLAIM = Duration.ofMinutes(2);

  /** Deliveries tried before the row gives up. */
  static final int MAX_ATTEMPTS = 10;

  /** How old a notice may be and still be sent. */
  static final Duration TOO_OLD = Duration.ofDays(1);

  private static final Object PENDING = ReleaseRequestFailureNotices.class.getName() + ".pending";

  @Inject ReleaseRequestSourceRepository sources;

  @Inject ReleaseRequestParticipantRepository participants;

  @Inject Instance<WorkspaceAgentLookup> lookup;

  @Inject Instance<WorkspaceAgentTurns> turns;

  @Inject Instance<TransactionSynchronizationRegistry> transactions;

  private ExecutorService worker;

  @PostConstruct
  void start() {
    worker = Executors.newVirtualThreadPerTaskExecutor();
  }

  @PreDestroy
  void stop() {
    worker.shutdownNow();
  }

  /** The message, exactly. */
  public static String message(String qualifiedId) {
    return "fyi: your " + qualifiedId + " failed its build";
  }

  /** The agent key in {@code subject}, or empty when it names no workspace agent. */
  static Optional<String> agentKeyOf(String subject) {
    if (subject == null) {
      return Optional.empty();
    }
    Matcher matcher = AGENT_SUBJECT.matcher(subject);
    return matcher.matches() ? Optional.of(matcher.group(1)) : Optional.empty();
  }

  /**
   * Owes the notice for {@code row}'s current fold to every agent behind it: its participants and
   * the authors of its sources (qits-1673), each an authenticated principal — never a name a body
   * stated, so no agent can make another one hear of its failures. Call it inside the transaction
   * that rejects the fold for a failed build; the delivery runs after the commit.
   */
  public void owe(ReleaseRequest row) {
    if (row == null || row.mergedSha == null) {
      return;
    }
    Set<String> subjects = new LinkedHashSet<>(participants.principalsOf(row.id));
    for (ReleaseRequestSource source : sources.listByRequest(row.id)) {
      subjects.add(source.addedBy);
    }
    Map<String, String> agents = new LinkedHashMap<>();
    for (String subject : subjects) {
      agentKeyOf(subject).ifPresent(key -> agents.putIfAbsent(key, subject));
    }
    boolean owed = false;
    Instant now = Instant.now();
    for (Map.Entry<String, String> agent : agents.entrySet()) {
      owed |=
          ReleaseRequestFailureNotice.getEntityManager()
                  .createNativeQuery(
                      "insert into release_request_failure_notice (request_id, merged_sha,"
                          + " agent_key, subject, owed_at) values (?1, ?2, ?3, ?4, ?5)"
                          + " on conflict do nothing")
                  .setParameter(1, row.id)
                  .setParameter(2, row.mergedSha)
                  .setParameter(3, agent.getKey())
                  .setParameter(4, agent.getValue())
                  .setParameter(5, now)
                  .executeUpdate()
              == 1;
    }
    if (owed) {
      afterCommit(row.id);
    }
  }

  /** The sweep: delivers every notice still owed whose claim is free or stale. Never throws. */
  public void deliverOwed() {
    List<String> requestIds;
    try {
      requestIds =
          QuarkusTransaction.requiringNew()
              .call(
                  () ->
                      ReleaseRequestFailureNotice.getEntityManager()
                          .createQuery(
                              "select distinct n.requestId from ReleaseRequestFailureNotice n"
                                  + " where n.outcome is null"
                                  + " and (n.claimedAt is null or n.claimedAt < ?1)",
                              String.class)
                          .setParameter(1, Instant.now().minus(CLAIM))
                          .setMaxResults(100)
                          .getResultList());
    } catch (RuntimeException e) {
      LOG.warnf(e, "Could not read the build-failure notices still owed");
      return;
    }
    requestIds.forEach(this::deliver);
  }

  /** Delivers every notice of one request still owed. Never throws. */
  public void deliver(String requestId) {
    List<ReleaseRequestFailureNotice> owed;
    try {
      owed =
          QuarkusTransaction.requiringNew()
              .call(
                  () ->
                      ReleaseRequestFailureNotice.<ReleaseRequestFailureNotice>list(
                          "requestId = ?1 and outcome is null", requestId));
    } catch (RuntimeException e) {
      LOG.warnf(e, "Could not read the build-failure notices of release request %s", requestId);
      return;
    }
    for (ReleaseRequestFailureNotice notice : owed) {
      try {
        deliverOne(notice);
      } catch (RuntimeException e) {
        LOG.warnf(
            e,
            "Could not tell agent %s that release request %s failed its build",
            notice.agentKey,
            requestId);
      }
    }
  }

  private void deliverOne(ReleaseRequestFailureNotice notice) {
    Optional<Claim> claimed = claim(notice);
    if (claimed.isEmpty()) {
      return;
    }
    Claim claim = claimed.get();
    if (claim.qualifiedId() == null) {
      finish(notice, "GAVE_UP", null, null, false, "the release request is gone");
      return;
    }
    if (claim.owedAt().isBefore(Instant.now().minus(TOO_OLD))) {
      finish(notice, "GAVE_UP", null, null, false, "owed for longer than " + TOO_OLD);
      return;
    }
    Optional<List<WorkspaceAgentLookup.ActiveAgent>> active =
        lookup.isResolvable() ? lookup.get().active() : Optional.empty();
    if (active.isEmpty()) {
      retryOrGiveUp(notice, claim, "could not list the ACTIVE agents");
      return;
    }
    List<WorkspaceAgentLookup.ActiveAgent> matches =
        active.get().stream()
            .filter(agent -> agent.agentId().startsWith(notice.agentKey + "-"))
            .toList();
    if (matches.size() != 1) {
      finish(
          notice,
          "NOT_ACTIVE",
          null,
          null,
          false,
          matches.isEmpty()
              ? "no ACTIVE agent has an id starting " + notice.agentKey
              : matches.size() + " ACTIVE agents have an id starting " + notice.agentKey);
      return;
    }
    WorkspaceAgentLookup.ActiveAgent agent = matches.get(0);
    WorkspaceAgentTurns.Turn turn =
        turns.isResolvable()
            ? turns.get().deliver(agent.workId(), message(claim.qualifiedId()))
            : new WorkspaceAgentTurns.Turn(
                WorkspaceAgentTurns.Outcome.COULD_NOT, "no delivery is configured");
    if (turn.outcome() == WorkspaceAgentTurns.Outcome.COULD_NOT) {
      retryOrGiveUp(notice, claim, turn.detail());
      return;
    }
    finish(
        notice,
        turn.outcome().name(),
        agent.agentId(),
        agent.workId(),
        turn.spoken(),
        turn.detail());
  }

  /** The row as the claim found it. */
  private record Claim(String qualifiedId, Instant owedAt, int attempts) {}

  /** Takes the row if no other delivery holds it. Empty when another does, or it is settled. */
  private Optional<Claim> claim(ReleaseRequestFailureNotice notice) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              Instant now = Instant.now();
              int taken =
                  ReleaseRequestFailureNotice.update(
                      "claimedAt = ?1, attempts = attempts + 1 where requestId = ?2"
                          + " and mergedSha = ?3 and agentKey = ?4 and outcome is null"
                          + " and (claimedAt is null or claimedAt < ?5)",
                      now,
                      notice.requestId,
                      notice.mergedSha,
                      notice.agentKey,
                      now.minus(CLAIM));
              if (taken != 1) {
                return Optional.<Claim>empty();
              }
              ReleaseRequestFailureNotice row = find(notice);
              ReleaseRequest request = ReleaseRequest.findById(notice.requestId);
              return Optional.of(
                  new Claim(
                      request == null ? null : request.qualifiedId, row.owedAt, row.attempts));
            });
  }

  private void retryOrGiveUp(ReleaseRequestFailureNotice notice, Claim claim, String why) {
    if (claim.attempts() >= MAX_ATTEMPTS) {
      finish(notice, "GAVE_UP", null, null, false, why);
      return;
    }
    LOG.infof(
        "Could not yet tell agent %s that %s failed its build (attempt %d): %s",
        notice.agentKey, claim.qualifiedId(), claim.attempts(), why);
  }

  private void finish(
      ReleaseRequestFailureNotice notice,
      String outcome,
      String agentId,
      String workId,
      boolean told,
      String detail) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              ReleaseRequestFailureNotice row = find(notice);
              if (row == null) {
                return;
              }
              row.outcome = outcome;
              row.agentId = agentId;
              row.workId = workId;
              row.toldAt = told ? Instant.now() : null;
              row.detail = detail;
            });
    if (!told) {
      LOG.infof(
          "Did not tell agent %s that release request %s failed its build: %s %s",
          notice.agentKey, notice.requestId, outcome, detail);
    }
  }

  private static ReleaseRequestFailureNotice find(ReleaseRequestFailureNotice notice) {
    ReleaseRequestFailureNotice.Key key = new ReleaseRequestFailureNotice.Key();
    key.requestId = notice.requestId;
    key.mergedSha = notice.mergedSha;
    key.agentKey = notice.agentKey;
    return ReleaseRequestFailureNotice.findById(key);
  }

  // ---- after the commit ------------------------------------------------------------------------

  private void afterCommit(String requestId) {
    TransactionSynchronizationRegistry registry = activeTransaction();
    if (registry == null) {
      submit(requestId);
      return;
    }
    @SuppressWarnings("unchecked")
    Set<String> pending = (Set<String>) registry.getResource(PENDING);
    if (pending == null) {
      pending = new LinkedHashSet<>();
      registry.putResource(PENDING, pending);
      Set<String> captured = pending;
      registry.registerInterposedSynchronization(
          new Synchronization() {
            @Override
            public void beforeCompletion() {}

            @Override
            public void afterCompletion(int status) {
              if (status == Status.STATUS_COMMITTED) {
                new ArrayList<>(captured).forEach(ReleaseRequestFailureNotices.this::submit);
              }
            }
          });
    }
    pending.add(requestId);
  }

  private void submit(String requestId) {
    try {
      worker.submit(() -> deliver(requestId));
    } catch (RejectedExecutionException e) {
      LOG.debugf("Not delivering the notices of %s now: shutting down; the sweep will", requestId);
    }
  }

  private TransactionSynchronizationRegistry activeTransaction() {
    if (!transactions.isResolvable()) {
      return null;
    }
    try {
      TransactionSynchronizationRegistry registry = transactions.get();
      int status = registry.getTransactionStatus();
      return status == Status.STATUS_ACTIVE || status == Status.STATUS_MARKED_ROLLBACK
          ? registry
          : null;
    } catch (RuntimeException e) {
      return null;
    }
  }
}
