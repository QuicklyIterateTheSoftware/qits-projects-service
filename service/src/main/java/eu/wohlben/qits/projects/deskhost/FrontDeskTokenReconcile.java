package eu.wohlben.qits.projects.deskhost;

import eu.wohlben.qits.projects.entity.FrontDesk;
import eu.wohlben.qits.projects.persistence.FrontDeskRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * Reaps desk tokens no desk claims (qits-767): at boot and every {@code
 * qits.projects.agent-credentials.reconcile-interval}, NORMAL launch mode only, it lists this
 * service's tokens of kind {@code agent-container} at qits-idp and revokes every one no {@code
 * front_desk.token_id} names — a DELETE whose revoke could not reach the idp, a lost mint race, a
 * desk dropped with its project. A failed listing reaps nothing, and a token minted within {@link
 * #MINT_GRACE} is left alone: it may be a mint whose row write has not landed yet.
 */
@ApplicationScoped
public class FrontDeskTokenReconcile {

  private static final Logger LOG = Logger.getLogger(FrontDeskTokenReconcile.class);

  /** How young an unclaimed token may be and still be left for its row to claim. */
  static final Duration MINT_GRACE = Duration.ofMinutes(5);

  @Inject FrontDeskTokens tokens;

  @Inject FrontDeskRepository desks;

  void onStart(@Observes StartupEvent event) {
    if (LaunchMode.current() != LaunchMode.NORMAL) {
      return;
    }
    Thread.ofVirtual().name("qits-front-desk-token-reconcile").start(this::reconcileQuietly);
  }

  @Scheduled(
      every = "${qits.projects.agent-credentials.reconcile-interval:1h}",
      concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
  void onInterval() {
    if (LaunchMode.current() != LaunchMode.NORMAL) {
      return;
    }
    reconcileQuietly();
  }

  void reconcileQuietly() {
    try {
      reconcile(Instant.now());
    } catch (RuntimeException e) {
      LOG.warn("The desk token reconcile failed — retried on the next interval.", e);
    }
  }

  /** One pass; answers how many tokens were revoked. */
  public int reconcile(Instant now) {
    Optional<List<FrontDeskTokens.Live>> listed = tokens.list();
    if (listed.isEmpty()) {
      return 0;
    }
    Set<String> claimed = new HashSet<>();
    for (FrontDesk row : QuarkusTransaction.requiringNew().call(() -> desks.listAll())) {
      if (row.tokenId != null) {
        claimed.add(row.tokenId);
      }
    }
    int reaped = 0;
    for (FrontDeskTokens.Live token : listed.get()) {
      if (!FrontDeskTokens.CONTEXT_KIND.equals(token.contextKind())
          || claimed.contains(token.tokenId())
          || (token.createdAt() != null && token.createdAt().isAfter(now.minus(MINT_GRACE)))) {
        continue;
      }
      if (tokens.revoke(token.tokenId())) {
        LOG.infof(
            "Revoked desk token %s (project %s): no front desk claims it",
            token.tokenId(), token.contextId());
        reaped++;
      }
    }
    return reaped;
  }
}
