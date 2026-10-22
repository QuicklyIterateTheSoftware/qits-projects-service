package eu.wohlben.qits.projects.refinementhost;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A TEST-SCOPE qits-idp for the refinement tokens (qits-1482): mints {@code tok-…} subjects,
 * remembers what is live, what was minted (with its claims) and what was revoked, and can be told
 * to fail a mint or a listing. {@code @Alternative @Priority}, because the port returns a result
 * (AGENTS.md "Tests"), so no suite reaches the {@code @DefaultBean} HTTP adapter.
 */
@ApplicationScoped
@Alternative
@Priority(1)
public class FakeRefinementTokens implements RefinementTokens {

  /** One mint, as {@link RefinementService} asked for it. */
  public record Mint(String tokenId, long refinementId, String projectId, String entityId) {}

  private final Map<String, Live> live = new ConcurrentHashMap<>();

  private final List<String> revoked = new ArrayList<>();

  private final List<Mint> minted = new ArrayList<>();

  private volatile boolean failMint;

  private volatile boolean failList;

  @Override
  public Minted mint(long refinementId, String projectId, String entityId) {
    if (failMint) {
      throw new IllegalStateException("qits-idp unreachable minting a token for " + refinementId);
    }
    String id = "r-" + UUID.randomUUID();
    String subject = "tok-" + UUID.randomUUID();
    live.put(id, new Live(id, Long.toString(refinementId), Instant.now().minusSeconds(3600)));
    synchronized (minted) {
      minted.add(new Mint(id, refinementId, projectId, entityId));
    }
    return new Minted(id, "qits_tok_" + id, subject);
  }

  @Override
  public boolean revoke(String tokenId) {
    live.remove(tokenId);
    synchronized (revoked) {
      revoked.add(tokenId);
    }
    return true;
  }

  @Override
  public Optional<List<Live>> list() {
    return failList ? Optional.empty() : Optional.of(List.copyOf(live.values()));
  }

  /** A token qits-idp holds that nothing here minted (or whose row is gone). */
  public void plant(Live token) {
    live.put(token.tokenId(), token);
  }

  public boolean isLive(String tokenId) {
    return live.containsKey(tokenId);
  }

  public List<String> revoked() {
    synchronized (revoked) {
      return List.copyOf(revoked);
    }
  }

  /** Every mint for this refinement row, oldest first. */
  public List<Mint> mintsFor(long refinementId) {
    synchronized (minted) {
      return minted.stream().filter(m -> m.refinementId() == refinementId).toList();
    }
  }

  public void failMint(boolean fail) {
    this.failMint = fail;
  }

  public void failList(boolean fail) {
    this.failList = fail;
  }

  public void reset() {
    live.clear();
    synchronized (revoked) {
      revoked.clear();
    }
    synchronized (minted) {
      minted.clear();
    }
    failMint = false;
    failList = false;
  }
}
