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

  /** Every delivery mint, by project (epic qits-1440). */
  private final List<String> mintedDelivery = new ArrayList<>();

  /** Every session mint, by project (qits-1482). */
  private final List<String> mintedSession = new ArrayList<>();

  /** Every desk-token mint, by desk id (qits-1482). */
  private final List<String> mintedForDesk = new ArrayList<>();

  /** Kinds whose mint fails while the others succeed (qits-1482). */
  private final java.util.Set<String> failingKinds = ConcurrentHashMap.newKeySet();

  private volatile boolean failMint;

  private volatile boolean failList;

  @Override
  public Minted mint(String deskId, String projectId) {
    if (failMint || failingKinds.contains(CONTEXT_KIND)) {
      throw new IllegalStateException("qits-idp unreachable minting a token for " + deskId);
    }
    String id = "t-" + UUID.randomUUID();
    String subject = "tok-" + UUID.randomUUID();
    live.put(id, new Live(id, CONTEXT_KIND, deskId, Instant.now().minusSeconds(3600)));
    synchronized (minted) {
      minted.add(projectId);
      mintedForDesk.add(deskId);
    }
    return new Minted(id, "qits_tok_" + id, subject);
  }

  @Override
  public Minted mintSession(String projectId) {
    if (failMint || failingKinds.contains(CONTEXT_KIND_SESSION)) {
      throw new IllegalStateException("qits-idp refused a desk-session token for " + projectId);
    }
    String id = "s-" + UUID.randomUUID();
    String subject = "tok-" + UUID.randomUUID();
    live.put(id, new Live(id, CONTEXT_KIND_SESSION, projectId, Instant.now().minusSeconds(3600)));
    synchronized (mintedSession) {
      mintedSession.add(projectId);
    }
    return new Minted(id, "qits_tok_" + id, subject);
  }

  @Override
  public boolean revokeSession(String tokenId) {
    return revoke(tokenId);
  }

  @Override
  public Minted mintDelivery(String projectId) {
    if (failMint || failingKinds.contains(CONTEXT_KIND_DELIVERY)) {
      throw new IllegalStateException("qits-idp unreachable minting a token for " + projectId);
    }
    String id = "d-" + UUID.randomUUID();
    String subject = "tok-" + UUID.randomUUID();
    live.put(id, new Live(id, CONTEXT_KIND_DELIVERY, projectId, Instant.now().minusSeconds(3600)));
    synchronized (mintedDelivery) {
      mintedDelivery.add(projectId);
    }
    return new Minted(id, "qits_tok_" + id, subject);
  }

  @Override
  public boolean revokeDelivery(String tokenId) {
    return revoke(tokenId);
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

  /** How many desk tokens were minted for the desk {@code deskId}. */
  public long mintsForDesk(String deskId) {
    synchronized (minted) {
      return mintedForDesk.stream().filter(deskId::equals).count();
    }
  }

  public long sessionMintsFor(String projectId) {
    synchronized (mintedSession) {
      return mintedSession.stream().filter(projectId::equals).count();
    }
  }

  /** Fail every mint of {@code kind} while the others succeed. */
  public void failKind(String kind, boolean fail) {
    if (fail) {
      failingKinds.add(kind);
    } else {
      failingKinds.remove(kind);
    }
  }

  /** The context id a live token was minted for, or null when it is not live. */
  public String contextOf(String tokenId) {
    Live token = live.get(tokenId);
    return token == null ? null : token.contextId();
  }

  public long deliveryMintsFor(String projectId) {
    synchronized (mintedDelivery) {
      return mintedDelivery.stream().filter(projectId::equals).count();
    }
  }

  /** The kind of a live token, or null when it is not live. */
  public String kindOf(String tokenId) {
    Live token = live.get(tokenId);
    return token == null ? null : token.contextKind();
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
      mintedForDesk.clear();
    }
    synchronized (mintedDelivery) {
      mintedDelivery.clear();
    }
    synchronized (mintedSession) {
      mintedSession.clear();
    }
    failingKinds.clear();
    failMint = false;
    failList = false;
  }
}
