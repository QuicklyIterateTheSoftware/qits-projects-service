package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.entity.BranchDeliveryNotice;
import eu.wohlben.qits.projects.entity.Repository;
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
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import org.jboss.logging.Logger;

/**
 * <b>A branch's owning agent hears when the branch fails its build or moves</b> (qits-1722).
 *
 * <p>The request-keyed notices of qits-1672 told whoever filed a release request or added a source
 * to it — {@code fyi: your <qualified id> failed its build}. Two things were wrong with that key. A
 * request is not what an agent works on: a release hands a late branch on to the next request and a
 * successor supersedes a red release, so the agent heard about a request its branch had already
 * left, and nothing at all when its branch moved. And the filer is not the owner: an agent that
 * adds somebody else's branch to a request is told of that branch's failures, while the agent whose
 * branch it is hears nothing. So this hears {@link BranchDeliveries}' steps instead, and speaks to
 * the branch's owner:
 *
 * <ul>
 *   <li><b>FAILED</b> with failure {@code CI} or {@code AUTOMATION}: {@code fyi: your <branch>
 *       failed its build in <qualifiedId>}. A {@code RELEASE} failure is no build of the branch's
 *       and is not said, as qits-1672 did not say it.
 *   <li><b>MOVED</b>: {@code fyi: your <branch> moved from <previousQualifiedId> to <qualifiedId>}.
 * </ul>
 *
 * <h2>Addressed by the git_refs rule, never by who filed</h2>
 *
 * <p>The owner is the ACTIVE agent of the request's project whose wrapper branch is the branch, or
 * whose {@code <wrapperBranch>-} the branch starts with — exactly the refs qits-workspaces lets that
 * agent push ({@code refs/heads/<wrapper>} and {@code refs/heads/<wrapper>-*}, its {@code
 * gitRefsFor}). Pushing the branch is what makes it the agent's; no request's {@code requester},
 * participants or source {@code addedBy} is read. Where two agents match (an epic's {@code
 * epic/qits-1} and a ticket wrapper {@code epic/qits-1-x} both prefix {@code epic/qits-1-x-y}), the
 * longest wrapper branch wins: it is the narrower claim. None matching is {@code NO_AGENT} and
 * nothing is said — a person's branch, a {@code maintenance/} bump that slipped the ledger's filter,
 * an agent since gone.
 *
 * <p>"Of the project" is read here, not asked of the far side: the agents are listed as {@code GET
 * /workspaces/api/agents?state=ACTIVE} — the request the consumer pact already pins — and narrowed
 * to those whose wrapper repository is a repository of the notice's project in this service's own
 * table. The far side's {@code projectId} filter would answer the same, at the cost of a pact
 * interaction whose provider state names no project.
 *
 * <h2>Owed in the step's transaction, resolved and sent after it</h2>
 *
 * <p>{@link #onStep} runs inside the transaction that took the step, under its row locks, so it
 * does one thing: inserts one {@link BranchDeliveryNotice} with {@code on conflict do nothing}. That
 * row is the outbox and the dedupe both. Its key is the notice — {@code
 * FAILED:<requestId>:<foldSha>:<repoId>:<branch>} or {@code
 * MOVED:<repoId>:<branch>:<fromRequestId>:<toRequestId>} — so a retried red run at the same fold, or
 * a replayed hand-on, inserts nothing and nothing is said twice.
 *
 * <p><b>The agent is not in the key, and is not known at the insert.</b> Who owns a branch is an
 * HTTP question to qits-workspaces, and a listener may not make one ({@link
 * BranchDeliveryStepListener}). So the row is owed with {@code agent_id} null, and the delivery after
 * the commit lists the agents, picks the owner, and records {@code agent_id} and {@code work_id}
 * beside the outcome. One owner per branch makes one row one message; the qits-1672 rows were one
 * per (request, fold, agent) because they had many addressees.
 *
 * <p><b>Not lost.</b> After the commit a worker delivers what the transaction owed; {@link
 * #deliverOwed}, swept by {@code ReleaseRequestSweep}, delivers whatever a crash or an unreachable
 * qits-workspaces left. A delivery first claims the row ({@link #CLAIM}), so the worker and the
 * sweep never both send it; an unreachable lookup or delivery is retried until {@link #MAX_ATTEMPTS},
 * and a notice owed for longer than {@link #TOO_OLD} is given up, since news a day late is noise.
 * Outcomes: {@code DELIVERED}, {@code LAUNCHED}, {@code RESUMED} when said; {@code NO_AGENT} (no
 * ACTIVE agent owns the branch, or its work item had none at delivery) and {@code GAVE_UP} when
 * not. {@code NOT_ACTIVE} is qits-1672's word and is still read by the desk, never written here.
 *
 * <p><b>Step-generic.</b> The text is decided at the insert and stored, so the worker knows no step;
 * qits-1725 owes CONFLICTED and further notices through {@link #owe} into the same table.
 *
 * <p>The table is also the audit — who was told, when, about which fold — and the desk's {@code
 * request-failed} trigger reads it to learn whether any agent was told of a failed fold.
 */
@ApplicationScoped
public class BranchDeliveryNotices implements BranchDeliveryStepListener {

  private static final Logger LOG = Logger.getLogger(BranchDeliveryNotices.class);

  /** How long a claim holds before another delivery may take the row. */
  static final Duration CLAIM = Duration.ofMinutes(2);

  /** Deliveries tried before the row gives up. */
  static final int MAX_ATTEMPTS = 10;

  /** How old a notice may be and still be sent. */
  static final Duration TOO_OLD = Duration.ofDays(1);

  /** The outcomes that mean an agent was told. */
  public static final Set<String> TOLD = Set.of("DELIVERED", "LAUNCHED", "RESUMED");

  private static final Object PENDING = BranchDeliveryNotices.class.getName() + ".pending";

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

  // ---------------------------------------------------------------------------------------------
  // The texts and keys, exactly
  // ---------------------------------------------------------------------------------------------

  /** {@code fyi: your <branch> failed its build in <qualifiedId>}. */
  public static String failedText(String branch, String qualifiedId) {
    return "fyi: your " + branch + " failed its build in " + qualifiedId;
  }

  /** {@code fyi: your <branch> moved from <previousQualifiedId> to <qualifiedId>}. */
  public static String movedText(String branch, String previousQualifiedId, String qualifiedId) {
    return "fyi: your " + branch + " moved from " + previousQualifiedId + " to " + qualifiedId;
  }

  /** {@code FAILED:<requestId>:<foldSha>:<repoId>:<branch>}: once per fold and branch. */
  public static String failedKey(String requestId, String foldSha, String repoId, String branch) {
    return "FAILED:" + requestId + ":" + foldSha + ":" + repoId + ":" + branch;
  }

  /** {@code MOVED:<repoId>:<branch>:<from>:<to>}: once per (branch, from, to). */
  public static String movedKey(String repoId, String branch, String fromId, String toId) {
    return "MOVED:" + repoId + ":" + branch + ":" + fromId + ":" + toId;
  }

  // ---------------------------------------------------------------------------------------------
  // Owing, inside the step's transaction
  // ---------------------------------------------------------------------------------------------

  /**
   * Owes the notice a step calls for: FAILED by a build ({@code CI} or {@code AUTOMATION}) at a
   * fold, or MOVED between two requests. Every other step is no news to the branch's agent. A
   * write to this datasource and nothing else, as the listener contract requires.
   */
  @Override
  public void onStep(BranchDeliveryStep step) {
    if (step == null || step.branch() == null || step.requestId() == null) {
      return;
    }
    switch (step.step()) {
      case "FAILED" -> {
        if (!("CI".equals(step.failure()) || "AUTOMATION".equals(step.failure()))
            || step.foldSha() == null) {
          return;
        }
        owe(
            step,
            failedKey(step.requestId(), step.foldSha(), step.repoId(), step.branch()),
            failedText(step.branch(), step.qualifiedId()));
      }
      case "MOVED" -> {
        if (step.previousRequestId() == null
            || step.previousRequestId().equals(step.requestId())) {
          return;
        }
        owe(
            step,
            movedKey(step.repoId(), step.branch(), step.previousRequestId(), step.requestId()),
            movedText(step.branch(), step.previousQualifiedId(), step.qualifiedId()));
      }
      default -> {
        // No news to the branch's agent.
      }
    }
  }

  /**
   * Owes {@code text} under {@code key} for {@code step}'s branch, in the caller's transaction,
   * once: a key already owed or settled inserts nothing. Delivered after the commit. The seam
   * qits-1725 owes its own steps through.
   *
   * @return whether a new notice was owed
   */
  public boolean owe(BranchDeliveryStep step, String key, String text) {
    boolean owed =
        BranchDeliveryNotice.getEntityManager()
                .createNativeQuery(
                    "insert into branch_delivery_notice (notice_key, project_id, repo_id, branch,"
                        + " step, request_id, fold_sha, text, attempts, created_at)"
                        + " values (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, 0, ?9)"
                        + " on conflict do nothing")
                .setParameter(1, key)
                .setParameter(2, step.projectId())
                .setParameter(3, step.repoId())
                .setParameter(4, step.branch())
                .setParameter(5, step.step())
                .setParameter(6, step.requestId())
                .setParameter(7, step.foldSha())
                .setParameter(8, text)
                .setParameter(9, step.changedAt() != null ? step.changedAt() : Instant.now())
                .executeUpdate()
            == 1;
    if (owed) {
      afterCommit(key);
    }
    return owed;
  }

  // ---------------------------------------------------------------------------------------------
  // Delivering, after the commit
  // ---------------------------------------------------------------------------------------------

  /** The sweep: delivers every notice still owed whose claim is free or stale. Never throws. */
  public void deliverOwed() {
    List<String> keys;
    try {
      keys =
          QuarkusTransaction.requiringNew()
              .call(
                  () ->
                      BranchDeliveryNotice.getEntityManager()
                          .createQuery(
                              "select n.noticeKey from BranchDeliveryNotice n"
                                  + " where n.outcome is null"
                                  + " and (n.claimedAt is null or n.claimedAt < ?1)"
                                  + " order by n.createdAt",
                              String.class)
                          .setParameter(1, Instant.now().minus(CLAIM))
                          .setMaxResults(100)
                          .getResultList());
    } catch (RuntimeException e) {
      LOG.warnf(e, "Could not read the branch-delivery notices still owed");
      return;
    }
    keys.forEach(this::deliver);
  }

  /** Delivers one notice if it is still owed. Never throws. */
  public void deliver(String key) {
    try {
      deliverOne(key);
    } catch (RuntimeException e) {
      LOG.warnf(e, "Could not deliver branch-delivery notice %s", key);
    }
  }

  private void deliverOne(String key) {
    Optional<Claim> claimed = claim(key);
    if (claimed.isEmpty()) {
      return;
    }
    Claim claim = claimed.get();
    if (claim.createdAt().isBefore(Instant.now().minus(TOO_OLD))) {
      finish(key, "GAVE_UP", null, null, false, "owed for longer than " + TOO_OLD);
      return;
    }
    Optional<List<WorkspaceAgentLookup.ActiveAgent>> active =
        lookup.isResolvable() ? lookup.get().active() : Optional.empty();
    if (active.isEmpty()) {
      retryOrGiveUp(key, claim, "could not list the ACTIVE agents");
      return;
    }
    Set<String> projectRepositories = repositoriesOf(claim.projectId());
    Optional<WorkspaceAgentLookup.ActiveAgent> owner =
        ownerOf(claim.branch(), active.get(), projectRepositories);
    if (owner.isEmpty()) {
      finish(
          key,
          "NO_AGENT",
          null,
          null,
          false,
          "no ACTIVE agent of the project owns " + claim.branch());
      return;
    }
    WorkspaceAgentLookup.ActiveAgent agent = owner.get();
    WorkspaceAgentTurns.Turn turn =
        turns.isResolvable()
            ? turns.get().deliver(agent.workId(), claim.text())
            : new WorkspaceAgentTurns.Turn(
                WorkspaceAgentTurns.Outcome.COULD_NOT, "no delivery is configured");
    if (turn.outcome() == WorkspaceAgentTurns.Outcome.COULD_NOT) {
      retryOrGiveUp(key, claim, turn.detail());
      return;
    }
    finish(
        key, turn.outcome().name(), agent.agentId(), agent.workId(), turn.spoken(), turn.detail());
  }

  /**
   * The ACTIVE agent that owns {@code branch} by the git_refs rule, among those whose wrapper
   * repository is one of {@code projectRepositories}: the longest matching wrapper branch, the
   * narrower claim. Empty when none does.
   */
  static Optional<WorkspaceAgentLookup.ActiveAgent> ownerOf(
      String branch,
      List<WorkspaceAgentLookup.ActiveAgent> active,
      Set<String> projectRepositories) {
    return active.stream()
        .filter(agent -> agent.repositoryId() != null)
        .filter(agent -> projectRepositories.contains(agent.repositoryId()))
        .filter(agent -> agent.owns(branch))
        .max(
            Comparator.comparingInt(
                    (WorkspaceAgentLookup.ActiveAgent agent) -> agent.wrapperBranch().length())
                .thenComparing(WorkspaceAgentLookup.ActiveAgent::agentId));
  }

  /** The ids of {@code projectId}'s repositories, read in a transaction of its own. */
  private static Set<String> repositoriesOf(String projectId) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                new LinkedHashSet<>(
                    Repository.getEntityManager()
                        .createQuery(
                            "select r.id from Repository r where r.project.id = ?1", String.class)
                        .setParameter(1, projectId)
                        .getResultList()));
  }

  /** The row as the claim found it. */
  private record Claim(
      String projectId, String branch, String text, Instant createdAt, int attempts) {}

  /** Takes the row if no other delivery holds it. Empty when another does, or it is settled. */
  private Optional<Claim> claim(String key) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              Instant now = Instant.now();
              int taken =
                  BranchDeliveryNotice.update(
                      "claimedAt = ?1, attempts = attempts + 1 where noticeKey = ?2"
                          + " and outcome is null and (claimedAt is null or claimedAt < ?3)",
                      now,
                      key,
                      now.minus(CLAIM));
              if (taken != 1) {
                return Optional.<Claim>empty();
              }
              BranchDeliveryNotice row = BranchDeliveryNotice.findById(key);
              return Optional.of(
                  new Claim(row.projectId, row.branch, row.text, row.createdAt, row.attempts));
            });
  }

  private void retryOrGiveUp(String key, Claim claim, String why) {
    if (claim.attempts() >= MAX_ATTEMPTS) {
      finish(key, "GAVE_UP", null, null, false, why);
      return;
    }
    LOG.infof("Could not yet say \"%s\" (attempt %d): %s", claim.text(), claim.attempts(), why);
  }

  private void finish(
      String key, String outcome, String agentId, String workId, boolean told, String detail) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              BranchDeliveryNotice row = BranchDeliveryNotice.findById(key);
              if (row == null) {
                return;
              }
              row.outcome = outcome;
              row.agentId = agentId;
              row.workId = workId;
              row.deliveredAt = told ? Instant.now() : null;
              row.detail = detail;
            });
    if (!told) {
      LOG.infof("Did not deliver branch-delivery notice %s: %s %s", key, outcome, detail);
    }
  }

  // ---- after the commit ------------------------------------------------------------------------

  private void afterCommit(String key) {
    TransactionSynchronizationRegistry registry = activeTransaction();
    if (registry == null) {
      submit(key);
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
                new ArrayList<>(captured).forEach(BranchDeliveryNotices.this::submit);
              }
            }
          });
    }
    pending.add(key);
  }

  private void submit(String key) {
    try {
      worker.submit(() -> deliver(key));
    } catch (RejectedExecutionException e) {
      LOG.debugf("Not delivering notice %s now: shutting down; the sweep will", key);
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
