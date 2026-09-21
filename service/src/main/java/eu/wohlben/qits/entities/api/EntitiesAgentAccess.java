package eu.wohlben.qits.entities.api;

import eu.wohlben.qits.entities.error.ForbiddenException;
import eu.wohlben.qits.projects.security.AgentAccess;
import io.quarkus.security.identity.SecurityIdentity;

/**
 * <b>What an agent may write on the entity surface: the rows of its own project, and nothing
 * else.</b>
 *
 * <p>The membership rule is not invented here — it reads off the MCP surface. A write route in this
 * package admits {@code qits:agent} exactly where the {@code repository} MCP server already exposes
 * a tool performing that same write, because that server serves an agent with no credential at all:
 * refusing at the REST door what is handed over unauthenticated one package away was an
 * inconsistency, not a boundary. Every write no tool serves — the two lifecycle transitions of an
 * epic, and the four deletes — stays {@code qits:admin}.
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
 *
 * <p><b>The claims are read off the token, never through {@code MachineAuth}.</b> {@code MachineAuth}
 * passes every caller while {@code qits.auth.machine.required} is off, and an agent role that
 * arrived on a forwarded {@code X-Qits-Roles} header carries no token and therefore no {@code
 * project} claim at all. Such a caller matches no project, so it is <em>refused</em> every write
 * here rather than waved through — the same stance {@code AgentAccess} itself argues for.
 */
final class EntitiesAgentAccess {

  /** A caller holding one of these is judged as before, even if it also holds the agent role. */
  private static final String[] WIDER = {AgentAccess.ADMIN_ROLE, AgentAccess.SYSTEM_ROLE};

  private EntitiesAgentAccess() {}

  /**
   * Refuses a bound agent whose token's {@code project} claim does not cover {@code projectId}.
   * Called as the first statement of a granted write, with the project resolved from the row the
   * write names — so an id naming nothing answers 404 from that resolution, exactly as it does for
   * an admin, and never a 403 that would report on rows the caller cannot see.
   */
  static void requireProject(SecurityIdentity identity, String projectId) {
    if (AgentAccess.isBoundAgent(identity, WIDER)
        && !AgentAccess.coversProject(identity, projectId)) {
      throw new ForbiddenException("An agent may write only the entities of its own project.");
    }
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
