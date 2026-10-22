package eu.wohlben.qits.projects.refinementhost;

import eu.wohlben.qits.projects.entity.Refinement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * A refinement room's own {@code qits_tok_} at qits-idp (qits-1482): the successor of the
 * commissioned client ({@link RefinementCredentials}, {@link RefinementCommissions}) that qits-770
 * retires, so the room's session — a desk session keyed by the entity id once qits-770 lands — runs
 * on a principal of its own rather than on the desk's.
 *
 * <p>Kind {@value #CONTEXT_KIND} ({@code CommissionRoles} maps it to {@code qits:agent} and nothing
 * else), contextId the row id, claims {@code {project, entity}}, {@code gitRefs []}. The {@code
 * entity} claim is what {@code EntitiesAgentAccess.requireEntity} binds the bearer to: such a token
 * reaches only its own entity's refinement.
 *
 * <p><b>Lifecycle.</b> Minted when the row is created (and on a later open of a row that holds
 * none), revoked by {@link RefinementService#discard} before the row goes — which is also the path
 * {@link EntityResolutions} takes — and reconciled both ways by {@link RefinementTokenReconcile}. A
 * failed mint never fails the open: the row is created without one and the reconcile mints it.
 *
 * <p>A seam rather than {@code IdpTokens} itself so a suite can stand in for qits-idp; {@code
 * idphost/IdpRefinementTokens} is the {@code @DefaultBean}.
 */
public interface RefinementTokens {

  /** The idp context kind of a refinement token (the same word the retired client kind used). */
  String CONTEXT_KIND = "refinement";

  /** The claim naming the one entity a refinement token may reach. */
  String ENTITY_CLAIM = "entity";

  /** A minted token: its id, its value (a credential) and the {@code sub} it introspects as. */
  record Minted(String tokenId, String token, String subject) {
    @Override
    public String toString() {
      return "Minted[tokenId=" + tokenId + ", subject=" + subject + "]";
    }
  }

  /** One live token of kind {@value #CONTEXT_KIND} this service commissioned. Never a value. */
  record Live(String tokenId, String contextId, Instant createdAt) {}

  /**
   * What a refinement's session runs on, keyed the way the daemon's {@code sessionCredentials}
   * frame keys it: the entity id (qits-770's session key). Not wired to anything yet.
   */
  record SessionCredential(String key, String token, String subject) {
    @Override
    public String toString() {
      return "SessionCredential[key=" + key + ", subject=" + subject + "]";
    }
  }

  /**
   * Mint the token of refinement {@code refinementId}: {@code {contextKind: refinement, contextId:
   * <refinementId>, claims: {project: <projectId>, entity: <entityId>}, gitRefs: []}}. Throws when
   * it cannot.
   */
  Minted mint(long refinementId, String projectId, String entityId);

  /** Revoke a token; a 404 counts as done. False when it could not be asked. */
  boolean revoke(String tokenId);

  /** Every live token of kind {@value #CONTEXT_KIND}, or empty when the listing failed. */
  Optional<List<Live>> list();

  /** The credential a row holds for its session, or empty while it holds none. */
  static Optional<SessionCredential> credentialOf(Refinement row) {
    if (row == null
        || row.tokenValue == null
        || row.tokenValue.isBlank()
        || row.tokenSubject == null
        || row.tokenSubject.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(new SessionCredential(row.entityId, row.tokenValue, row.tokenSubject));
  }
}
