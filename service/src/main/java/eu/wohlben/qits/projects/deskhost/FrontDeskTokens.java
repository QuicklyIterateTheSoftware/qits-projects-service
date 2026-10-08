package eu.wohlben.qits.projects.deskhost;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * A front desk's own {@code qits_tok_} at qits-idp (qits-767): minted when the desk's row is created,
 * revoked when the desk is deleted (by its door, with its project, or on the runner's {@code removed}
 * of an unplaced row), and reconciled against the rows by {@link FrontDeskTokenReconcile}. Stop never
 * revokes it: the spec carries it, and a stopped desk starts again with the same one.
 *
 * <p>A seam rather than {@code IdpTokens} itself so a suite can stand in for qits-idp; {@code
 * idphost/IdpFrontDeskTokens} is the {@code @DefaultBean}.
 */
public interface FrontDeskTokens {

  /** The idp context kind of a desk token; {@code CommissionRoles} maps it to {@code qits:agent}. */
  String CONTEXT_KIND = "agent-container";

  /** A minted token: its id, its value (a credential) and the {@code sub} it introspects as. */
  record Minted(String tokenId, String token, String subject) {
    @Override
    public String toString() {
      return "Minted[tokenId=" + tokenId + ", subject=" + subject + "]";
    }
  }

  /** One live token this service commissioned. */
  record Live(String tokenId, String contextKind, String contextId, Instant createdAt) {}

  /**
   * Mint the desk token of {@code projectId}: {@code {contextKind: agent-container, contextId:
   * <projectId>, claims: {project: <projectId>}, gitRefs: []}}. Throws when it cannot.
   */
  Minted mint(String projectId);

  /** Revoke a token; a 404 counts as done. False when it could not be asked. */
  boolean revoke(String tokenId);

  /** Every live token this service commissioned, or empty when the listing failed. */
  Optional<List<Live>> list();
}
