package eu.wohlben.qits.entities.api;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.json.JsonString;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * Resolves the audit "changed-by" value from the request identity (null when anonymous), and the
 * author a comment is stamped with.
 *
 * <p><b>Public because a second package in this module stamps through it.</b> {@code
 * projects.api.EntityDispatch} writes a comment onto a ticket's thread and has to stamp it
 * from the caller exactly as the {@code /work} comment door does; a copy of these five lines over there
 * would be a second answer to "who is calling", free to drift from this one. It is still module-
 * internal — nothing outside {@code service} sees it, and the {@code entities} jar carries no identity.
 *
 * <p><b>Who wrote a remark and which credential made the write are different facts</b> (qits-1448).
 * An agent's bearer carries an {@link #AGENT_CLAIM} (qits-1213): its comment is authored by that
 * agent ({@link #commentAuthor}), while the audit log's {@code changedBy} stays the principal name.
 */
public final class EntitiesPrincipal {

  /**
   * The claim qits-workspaces mints on an agent's own credential only ({@code
   * IdpCredentialCommissioner}): the agent's uuid. The CLI's MCP server reads the same name ({@code
   * ToolRunner.AGENT_CLAIM}).
   */
  public static final String AGENT_CLAIM = "agent";

  /**
   * The claim a refinement room's own token carries (qits-1482, {@code RefinementTokens}): the id
   * of the one entity whose refinement it may reach. {@code EntitiesAgentAccess.requireEntity}
   * binds to it.
   */
  public static final String ENTITY_CLAIM = "entity";

  /**
   * The claim qits-idp stamps on every token of a commissioned context: the commission's kind —
   * {@code agent-container}, {@code desk-delivery}, {@code project-agent} and so on.
   */
  public static final String CONTEXT_KIND_CLAIM = "context_kind";

  /**
   * A long-running project agent's {@link #CONTEXT_KIND_CLAIM} (qits-2518): qits-agents-service
   * commissions its token under the fixed subject {@value #PROJECT_AGENT_SUBJECT_PREFIX}{@code
   * <projectSlug>-<name>}, with a {@code project} claim naming the project's id.
   */
  public static final String PROJECT_AGENT_CONTEXT_KIND = "project-agent";

  /** What a project agent's token subject starts with, before {@code <projectSlug>-<name>}. */
  public static final String PROJECT_AGENT_SUBJECT_PREFIX = "tok-project-agent-";

  private EntitiesPrincipal() {}

  /**
   * The bearer's {@code entity} claim, trimmed; empty for a caller without a token or without the
   * claim. Not parsed as a uuid: a malformed claim must still bind (and so match nothing), never
   * read as "unbound".
   */
  public static Optional<String> entityOf(SecurityIdentity identity) {
    if (identity == null || !(identity.getPrincipal() instanceof JsonWebToken jwt)) {
      return Optional.empty();
    }
    Object claim = jwt.getClaim(ENTITY_CLAIM);
    if (claim == null) {
      return Optional.empty();
    }
    String value =
        claim instanceof JsonString json
            ? json.getString()
            : claim instanceof String text ? text : claim.toString();
    return Optional.of(value.trim());
  }

  /**
   * The bearer's {@code context_kind} claim, trimmed, read as {@link #entityOf} reads {@code
   * entity}; null for a caller without a token or without the claim.
   */
  public static String contextKindOf(SecurityIdentity identity) {
    if (identity == null || !(identity.getPrincipal() instanceof JsonWebToken jwt)) {
      return null;
    }
    Object claim = jwt.getClaim(CONTEXT_KIND_CLAIM);
    if (claim == null) {
      return null;
    }
    String value =
        claim instanceof JsonString json
            ? json.getString()
            : claim instanceof String text ? text : claim.toString();
    return value.trim();
  }

  public static String changedBy(SecurityIdentity identity) {
    if (identity == null || identity.isAnonymous() || identity.getPrincipal() == null) {
      return null;
    }
    return identity.getPrincipal().getName();
  }

  /**
   * The bearer's {@code agent} claim when it is a uuid; empty for a person, a machine, a forwarded
   * header pair (no token, so no claim) and anything that does not parse.
   */
  public static Optional<String> agentOf(SecurityIdentity identity) {
    if (identity == null || !(identity.getPrincipal() instanceof JsonWebToken jwt)) {
      return Optional.empty();
    }
    Object claim = jwt.getClaim(AGENT_CLAIM);
    String value =
        claim instanceof JsonString json
            ? json.getString()
            : claim instanceof String text ? text : null;
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }
    try {
      UUID.fromString(value.trim());
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
    return Optional.of(value.trim());
  }

  /** Who a comment is by: the caller's agent when it is one, otherwise {@link #changedBy}. */
  public static String commentAuthor(SecurityIdentity identity) {
    return agentOf(identity).orElseGet(() -> changedBy(identity));
  }
}
