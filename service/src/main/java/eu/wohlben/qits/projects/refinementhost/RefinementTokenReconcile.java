package eu.wohlben.qits.projects.refinementhost;

import eu.wohlben.qits.projects.entity.Refinement;
import eu.wohlben.qits.projects.persistence.RefinementRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.jboss.logging.Logger;

/**
 * Keeps the refinement tokens (qits-1482) and the rows in step, both ways: at boot and every {@code
 * qits.projects.refinement-credentials.reconcile-interval}, NORMAL launch mode only — the shape of
 * {@code deskhost/FrontDeskTokenReconcile}.
 *
 * <ul>
 *   <li><b>Reap</b>: a live token of kind {@code refinement} that no {@code refinement.token_id}
 *       names is revoked — a discard whose revoke could not reach qits-idp, a lost mint race, a row
 *       whose store failed. That covers a token whose contextId names no row at all, and a stale
 *       second token of a row that holds another. A failed listing reaps nothing, and a token minted
 *       within {@link #MINT_GRACE} is left alone: it may be a mint whose row write has not landed.
 *   <li><b>Mint</b>: every row holding no token is given one ({@link
 *       RefinementService#mintTokenIfMissing}) — a room opened while qits-idp was away, and every
 *       room that predates V50.
 * </ul>
 *
 * <p>Its own kind keeps it invisible to the desk tokens' reconcile, and that to it. It reads the
 * <em>token</em> listing only: the retired containers' commissioned <em>clients</em> carried the
 * same kind word on a different listing, and {@link LegacyRefinementContainers} deletes those.
 */
@ApplicationScoped
public class RefinementTokenReconcile {

  private static final Logger LOG = Logger.getLogger(RefinementTokenReconcile.class);

  /** How young an unclaimed token may be and still be left for its row to claim. */
  static final Duration MINT_GRACE = Duration.ofMinutes(5);

  /** What one pass did. */
  public record Pass(int revoked, int minted) {}

  @Inject RefinementTokens tokens;

  @Inject RefinementRepository refinements;

  @Inject RefinementService service;

  void onStart(@Observes StartupEvent event) {
    if (LaunchMode.current() != LaunchMode.NORMAL) {
      return;
    }
    Thread.ofVirtual().name("qits-refinement-token-reconcile").start(this::reconcileQuietly);
  }

  @Scheduled(
      every = "${qits.projects.refinement-credentials.reconcile-interval:1h}",
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
      LOG.warn("The refinement token reconcile failed — retried on the next interval.", e);
    }
  }

  /** One pass: reap the unclaimed tokens, then mint for the rows that hold none. */
  public Pass reconcile(Instant now) {
    return new Pass(reap(now), mintMissing());
  }

  private int reap(Instant now) {
    Optional<List<RefinementTokens.Live>> listed = tokens.list();
    if (listed.isEmpty()) {
      return 0;
    }
    Set<String> claimed =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    refinements.holdingAToken().stream()
                        .map(row -> row.tokenId)
                        .collect(Collectors.toSet()));
    int reaped = 0;
    for (RefinementTokens.Live token : listed.get()) {
      if (claimed.contains(token.tokenId())
          || (token.createdAt() != null && token.createdAt().isAfter(now.minus(MINT_GRACE)))) {
        continue;
      }
      if (tokens.revoke(token.tokenId())) {
        LOG.infof(
            "Revoked refinement token %s (refinement %s): no refinement row claims it",
            token.tokenId(), token.contextId());
        reaped++;
      }
    }
    return reaped;
  }

  private int mintMissing() {
    List<Refinement> bare =
        QuarkusTransaction.requiringNew().call(() -> refinements.holdingNoToken());
    int minted = 0;
    for (Refinement row : bare) {
      if (service.mintTokenIfMissing(row).tokenId != null) {
        minted++;
      }
    }
    return minted;
  }
}
