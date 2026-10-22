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
 * <p><b>A desk whose project runs the delivery loop holds a second one</b> (epic qits-1440), of kind
 * {@link #CONTEXT_KIND_DELIVERY}: minted while {@code delivery_enabled} is true and the row holds
 * none, revoked and cleared when the loop is disabled or the desk goes, and reconciled the same way.
 * Only the desk's {@code project.delivery} session carries it.
 *
 * <p><b>Every desk also holds a session token</b> (qits-1482), of kind {@link
 * #CONTEXT_KIND_SESSION}: minted beside the desk token, revoked with the row, reconciled the same
 * way, and carried in no environment — the daemon is to be handed it over the control socket.
 *
 * <p>A seam rather than {@code IdpTokens} itself so a suite can stand in for qits-idp; {@code
 * idphost/IdpFrontDeskTokens} is the {@code @DefaultBean}.
 */
public interface FrontDeskTokens {

  /** The idp context kind of a desk token; {@code CommissionRoles} maps it to {@code qits:agent}. */
  String CONTEXT_KIND = "agent-container";

  /**
   * The idp context kind of a delivery token (epic qits-1440); {@code CommissionRoles} maps it to
   * {@code qits:agent} and {@code qits:desk}.
   */
  String CONTEXT_KIND_DELIVERY = "desk-delivery";

  /**
   * The idp context kind of a desk's session token (qits-1482); {@code CommissionRoles} maps it to
   * {@code qits:agent}.
   */
  String CONTEXT_KIND_SESSION = "desk-session";

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

  /**
   * Mint the delivery token of {@code projectId}: {@code {contextKind: desk-delivery, contextId:
   * <projectId>, claims: {project: <projectId>}, gitRefs: []}}. Throws when it cannot.
   */
  Minted mintDelivery(String projectId);

  /** Revoke a delivery token; a 404 counts as done. False when it could not be asked. */
  boolean revokeDelivery(String tokenId);

  /**
   * Mint the session token of {@code projectId}'s desk: {@code {contextKind: desk-session,
   * contextId: <projectId>, claims: {project: <projectId>}, gitRefs: []}}. Throws when it cannot.
   */
  Minted mintSession(String projectId);

  /** Revoke a session token; a 404 counts as done. False when it could not be asked. */
  boolean revokeSession(String tokenId);

  /** Every live token this service commissioned, or empty when the listing failed. */
  Optional<List<Live>> list();
}
