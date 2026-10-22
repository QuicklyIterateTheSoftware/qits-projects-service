package eu.wohlben.qits.projects.deskhost;

import eu.wohlben.qits.projects.entity.DeskRole;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * Reaps desk tokens no desk claims (qits-767): at boot and every {@code
 * qits.projects.agent-credentials.reconcile-interval}, NORMAL launch mode only, it lists this
 * service's tokens at qits-idp and revokes every one of a desk kind that no row claims — a DELETE
 * whose revoke could not reach the idp, a lost mint race, a desk dropped with its project. A failed
 * listing reaps nothing, and a token minted within {@link #MINT_GRACE} is left alone: it may be a
 * mint whose row write has not landed yet.
 *
 * <p>Each kind is claimed by its own column, per row (qits-1482):
 *
 * <ul>
 *   <li>{@code agent-container} — any desk's {@code token_id}, FRONT or DELIVERY;
 *   <li>{@code desk-delivery} (epic qits-1440) — a DELIVERY desk's {@code delivery_token_id} only. A
 *       live one no DELIVERY row claims is reaped, which takes the one a FRONT desk held before the
 *       loop had a desk of its own, and one whose revoke on a disabled loop could not reach the idp;
 *   <li>{@code desk-session} — a FRONT desk's {@code session_token_id} only.
 * </ul>
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
    Map<String, Set<String>> claimed = new HashMap<>();
    claimed.put(FrontDeskTokens.CONTEXT_KIND, new HashSet<>());
    claimed.put(FrontDeskTokens.CONTEXT_KIND_DELIVERY, new HashSet<>());
    claimed.put(FrontDeskTokens.CONTEXT_KIND_SESSION, new HashSet<>());
    for (FrontDesk row : QuarkusTransaction.requiringNew().call(() -> desks.listAll())) {
      if (row.tokenId != null) {
        claimed.get(FrontDeskTokens.CONTEXT_KIND).add(row.tokenId);
      }
      if (row.role() == DeskRole.DELIVERY && row.deliveryTokenId != null) {
        claimed.get(FrontDeskTokens.CONTEXT_KIND_DELIVERY).add(row.deliveryTokenId);
      }
      if (row.role() == DeskRole.FRONT && row.sessionTokenId != null) {
        claimed.get(FrontDeskTokens.CONTEXT_KIND_SESSION).add(row.sessionTokenId);
      }
    }
    int reaped = 0;
    for (FrontDeskTokens.Live token : listed.get()) {
      Set<String> ofKind = claimed.get(token.contextKind());
      if (ofKind == null
          || ofKind.contains(token.tokenId())
          || (token.createdAt() != null && token.createdAt().isAfter(now.minus(MINT_GRACE)))) {
        continue;
      }
      if (revoke(token)) {
        LOG.infof(
            "Revoked %s token %s (context %s): no desk claims it",
            token.contextKind(), token.tokenId(), token.contextId());
        reaped++;
      }
    }
    return reaped;
  }

  private boolean revoke(FrontDeskTokens.Live token) {
    return switch (token.contextKind()) {
      case FrontDeskTokens.CONTEXT_KIND_DELIVERY -> tokens.revokeDelivery(token.tokenId());
      case FrontDeskTokens.CONTEXT_KIND_SESSION -> tokens.revokeSession(token.tokenId());
      default -> tokens.revoke(token.tokenId());
    };
  }
}
