package eu.wohlben.qits.projects.deskhost;

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
 * A TEST-SCOPE qits-idp for the desk tokens (qits-767): mints {@code tok-…} subjects, remembers
 * what is live and what was revoked, and can be told to fail a mint or a listing. {@code
 * @Alternative @Priority}, so no suite reaches the {@code @DefaultBean} HTTP adapter.
 */
@ApplicationScoped
@Alternative
@Priority(1)
public class FakeFrontDeskTokens implements FrontDeskTokens {

  private final Map<String, Live> live = new ConcurrentHashMap<>();

  private final List<String> revoked = new ArrayList<>();

  private final List<String> minted = new ArrayList<>();

  private volatile boolean failMint;

  private volatile boolean failList;

  @Override
  public Minted mint(String projectId) {
    if (failMint) {
      throw new IllegalStateException("qits-idp unreachable minting a token for " + projectId);
    }
    String id = "t-" + UUID.randomUUID();
    String subject = "tok-" + UUID.randomUUID();
    live.put(id, new Live(id, CONTEXT_KIND, projectId, Instant.now().minusSeconds(3600)));
    synchronized (minted) {
      minted.add(projectId);
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

  /** A token qits-idp holds that nothing here minted. */
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

  public long mintsFor(String projectId) {
    synchronized (minted) {
      return minted.stream().filter(projectId::equals).count();
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
