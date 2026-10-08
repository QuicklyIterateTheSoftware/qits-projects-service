package eu.wohlben.qits.projects.deskhost;

import eu.wohlben.qits.projects.entity.DeskRunner;
import eu.wohlben.qits.projects.idphost.IdpRunnerCommissioner;
import eu.wohlben.qits.projects.idphost.IdpTokens;
import eu.wohlben.qits.projects.persistence.DeskRunnerRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * Gives back the front-desk runner credentials no runner row holds any more (qits-767): the runner
 * arms of qits-workspaces-service's {@code CommissionReconciler}, as a reconcile of their own beside
 * {@code agenthost/AgentCredentialReconcile} and {@code refinementhost/RefinementCommissionReconcile}
 * — one per commissioned kind, which is how this service is shaped.
 *
 * <p><b>Two arms, judged by the runner table.</b> A {@code desk-runner} client belongs to the
 * runner its {@code contextId} names, and is decommissioned when that row is gone or names a
 * different client; a row with no client yet spares every client of its runner, because that is a
 * registration between the commission and the write. A {@code desk-runner-registration} token is
 * deleted when no row's {@code registration_token_id} names it: the runner is gone, has registered
 * (the register door clears the id) or holds a newer token. A token younger than {@link
 * #TOKEN_GRACE} is spared, because a create and a rotation commission it before the row names it.
 *
 * <p><b>It only ever deletes what the issuer just listed</b>, filtered to its two kinds; a listing,
 * or a runner table, that could not be read reaps nothing.
 *
 * <p>At boot (off the startup path, packaged runs only) and hourly. Never throws.
 */
@ApplicationScoped
public class DeskRunnerCommissionReconcile {

  private static final Logger LOG = Logger.getLogger(DeskRunnerCommissionReconcile.class);

  /**
   * How young a registration token has to be to be spared whatever the runner table says. A create
   * and a rotation commission the token first and write the row after; ten minutes is that moment
   * with a great deal of room. qits-workspaces' and qits-ci's value.
   */
  static final Duration TOKEN_GRACE = Duration.ofMinutes(10);

  @Inject IdpRunnerCommissioner commissioner;

  @Inject DeskRunnerRepository runners;

  void onStart(@Observes StartupEvent event) {
    if (LaunchMode.current() != LaunchMode.NORMAL) {
      return;
    }
    Thread.ofVirtual().name("qits-desk-runner-commission-reconcile").start(this::reconcile);
  }

  @Scheduled(
      every = "{qits.projects.desk-runner.commission-reconcile-interval}",
      concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
  void scheduled() {
    if (LaunchMode.current() != LaunchMode.NORMAL) {
      return;
    }
    reconcile();
  }

  /** One pass. Answers how many credentials it gave back — the number the tests read. */
  int reconcile() {
    try {
      if (!commissioner.enabled()) {
        return 0;
      }
      Instant now = Instant.now();
      return reapClients() + reapRegistrationTokens(now);
    } catch (RuntimeException e) {
      LOG.warnf("The desk runner commission reconcile did not complete: %s", e.toString());
      return 0;
    }
  }

  /** The {@code desk-runner} arm; see the class javadoc. */
  int reapClients() {
    Optional<List<IdpRunnerCommissioner.LiveClient>> live = commissioner.liveRunnerClients();
    if (live.isEmpty() || live.get().isEmpty()) {
      return 0;
    }
    Map<String, RunnerCredentials> rows = runnerCredentials();
    if (rows == null) {
      return 0;
    }
    int reaped = 0;
    for (IdpRunnerCommissioner.LiveClient client : live.get()) {
      RunnerCredentials row = rows.get(client.contextId());
      if (row != null && (row.clientId() == null || row.clientId().equals(client.clientId()))) {
        continue;
      }
      LOG.infof(
          "Decommissioning desk runner client %s of runner %s, which %s",
          client.clientId(),
          client.contextId(),
          row == null ? "is gone" : "is registered as " + row.clientId());
      commissioner.decommissionClient(client.clientId());
      reaped++;
    }
    return reaped;
  }

  /** The {@code desk-runner-registration} arm; see the class javadoc. */
  int reapRegistrationTokens(Instant now) {
    Optional<List<IdpTokens.LiveToken>> live = commissioner.liveTokens();
    if (live.isEmpty()
        || live.get().stream()
            .noneMatch(t -> IdpRunnerCommissioner.REGISTRATION_KIND.equals(t.contextKind()))) {
      return 0;
    }
    Map<String, RunnerCredentials> rows = runnerCredentials();
    if (rows == null) {
      return 0;
    }
    Set<String> referenced = new HashSet<>();
    rows.values().stream()
        .map(RunnerCredentials::registrationTokenId)
        .filter(Objects::nonNull)
        .forEach(referenced::add);
    int reaped = 0;
    for (IdpTokens.LiveToken token : live.get()) {
      if (!IdpRunnerCommissioner.REGISTRATION_KIND.equals(token.contextKind())
          || referenced.contains(token.tokenId())) {
        continue;
      }
      if (token.createdAt() != null && token.createdAt().isAfter(now.minus(TOKEN_GRACE))) {
        continue;
      }
      LOG.infof(
          "Deleting desk runner registration token %s of runner %s: no runner row names it",
          token.tokenId(), token.contextId());
      commissioner.deleteToken(token.tokenId());
      reaped++;
    }
    return reaped;
  }

  /** What one runner row says it holds at qits-idp. {@code clientId} null is unregistered. */
  record RunnerCredentials(String clientId, String registrationTokenId) {}

  /**
   * Every runner's credentials, keyed by the runner id as qits-idp spells a context id, or null when
   * the table could not be read, which reaps nothing.
   */
  private Map<String, RunnerCredentials> runnerCredentials() {
    try {
      return QuarkusTransaction.requiringNew()
          .call(
              () -> {
                Map<String, RunnerCredentials> rows = new HashMap<>();
                for (DeskRunner runner : runners.listAll()) {
                  rows.put(
                      runner.id.toString(),
                      new RunnerCredentials(runner.clientId, runner.registrationTokenId));
                }
                return rows;
              });
    } catch (RuntimeException e) {
      LOG.warnf("Could not read the desk runners, so no runner credential is reaped: %s", e.toString());
      return null;
    }
  }
}
