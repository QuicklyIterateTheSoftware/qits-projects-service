package eu.wohlben.qits.entities.api;

import eu.wohlben.qits.entities.error.ForbiddenException;
import eu.wohlben.qits.projects.security.AgentAccess;
import io.quarkus.security.identity.SecurityIdentity;
import java.util.Optional;

/**
 * <b>What an agent may write on the entity surface: the rows of its own project, and nothing
 * else.</b>
 *
 * <p>A write route in this package admits {@code qits:agent} where an agent's work needs that
 * write: the ones the retired {@code repository} MCP server served, which the central {@code qits}
 * server now reaches through these doors with the agent's own bearer (qits-1174). That includes an
 * epic's status move and the refinement design writes (in {@code RefinementDesignController}).
 * Every other write — an epic's supersede, and the deletes of an epic or a ticket — stays {@code
 * qits:admin}.
 *
 * <p><b>One shared helper rather than a copy per controller.</b> Fourteen routes across nine classes
 * bind the same claim to the same question, and the binding is three lines of which the middle one
 * is the whole decision; spelling it out per controller would mean nine chances for one of them to
 * drift — to compare the wrong id, to forget the wider roles, or to answer 404 where the others
 * answer 403. {@code ReleaseRequestController} keeps its own triplet because its binding is that
 * controller's alone (a repository, a request and a branch); this one is the same sentence
 * everywhere, so it is written once.
 *
 * <p><b>A person's session is judged exactly as before.</b> {@link AgentAccess#isBoundAgent} answers
 * false for any caller holding {@code qits:admin} or {@code qits:system}, so an admin — and a
 * platform service that also came in as an agent — passes through here untouched and pays no lookup.
 * That is also what lets the five generic doors that admit {@code qits:system} (qits-667: create,
 * read, comments, patch, status) serve a machine caller in any project: a platform service client
 * carries no {@code project} claim, and is never asked for one.
 *
 * <p><b>The claims are read off the token, never through {@code MachineAuth}.</b> {@code MachineAuth}
 * passes every caller while {@code qits.auth.machine.required} is off, and an agent role that
 * arrived on a forwarded {@code X-Qits-Roles} header carries no token and therefore no {@code
 * project} claim at all. Such a caller matches no project, so it is <em>refused</em> every write
 * here rather than waved through — the same stance {@code AgentAccess} itself argues for.
 */
public final class EntitiesAgentAccess {

  /**
   * A caller holding one of these is judged as before, even if it also holds the agent role.
   * {@code qits:admin-agent} is named explicitly beside {@code qits:admin} (qits-628 follow-up)
   * rather than implied, so it can be removed here without touching the others if this binding
   * must ever apply to it.
   */
  private static final String[] WIDER = {
    AgentAccess.ADMIN_ROLE, AgentAccess.ADMIN_AGENT_ROLE, AgentAccess.SYSTEM_ROLE
  };

  private EntitiesAgentAccess() {}

  /**
   * Refuses a bound agent whose token's {@code project} claim does not cover {@code projectId}.
   * Called as the first statement of a granted write, with the project resolved from the row the
   * write names — so an id naming nothing answers 404 from that resolution, exactly as it does for
   * an admin, and never a 403 that would report on rows the caller cannot see.
   */
  public static void requireProject(SecurityIdentity identity, String projectId) {
    if (AgentAccess.isBoundAgent(identity, WIDER)
        && !AgentAccess.coversProject(identity, projectId)) {
      throw new ForbiddenException("An agent may write only the entities of its own project.");
    }
  }

  /**
   * Refuses a bearer bound to one entity — its token carries an {@link EntitiesPrincipal#ENTITY_CLAIM}
   * claim, as a refinement room's own token does (qits-1482) — when that entity is not {@code
   * entityId}. A bearer with no such claim (a person, a platform service, a workspace agent, the
   * desk) passes untouched: the claim only ever narrows. Called beside {@link #requireProject} on
   * every door that names a refinement, with the row's {@code entityId}, after the row is resolved
   * so an unknown id still answers 404.
   *
   * <p>Unlike {@link #requireProject} it applies whatever roles the caller holds, because nothing
   * wider than {@code qits:agent} is ever minted with an entity claim, and a token that somehow
   * were would still have been minted to reach one room.
   */
  public static void requireEntity(SecurityIdentity identity, String entityId) {
    Optional<String> bound = EntitiesPrincipal.entityOf(identity);
    if (bound.isPresent() && !bound.get().equals(entityId)) {
      throw new ForbiddenException(
          "This credential is bound to entity "
              + bound.get()
              + " and may not reach the refinement of entity "
              + entityId
              + ".");
    }
  }

  /**
   * True when this caller may see the refinement of {@code entityId}: the listing's filter, the same
   * rule as {@link #requireEntity} answered rather than thrown.
   */
  public static boolean seesEntity(SecurityIdentity identity, String entityId) {
    Optional<String> bound = EntitiesPrincipal.entityOf(identity);
    return bound.isEmpty() || bound.get().equals(entityId);
  }

  /**
   * True when this caller is an agent bound by the rule above. Exposed so a route whose binding
   * costs a query — the batched transition, which has to resolve every id in the request — can skip
   * that work for the callers the binding never applies to, without re-spelling the wider roles.
   */
  static boolean bound(SecurityIdentity identity) {
    return AgentAccess.isBoundAgent(identity, WIDER);
  }
}
