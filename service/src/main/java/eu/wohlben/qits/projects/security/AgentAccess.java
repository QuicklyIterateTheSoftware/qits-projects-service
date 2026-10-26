package eu.wohlben.qits.projects.security;

import eu.wohlben.qits.auth.MachineIdentity;
import eu.wohlben.qits.auth.QitsClaims;
import eu.wohlben.qits.projects.error.DomainException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.json.JsonString;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * What a caller that comes in as an agent may reach (plan phase 4, in the superproject's {@code
 * principal-bound-git-refs-plan.md}).
 *
 * <p>An agent works on one domain object with its own token, and that token says what the object
 * is: the {@code project} claim, the {@code git_refs} claim (the refs it may push) and its {@code
 * sub} (the commissioned client). A door that admits {@code qits:agent} binds such a caller to those
 * claims; every other caller is judged exactly as before.
 *
 * <p><b>The claims are read off the token here, never through {@code MachineAuth}.</b> {@code
 * MachineAuth} lets every caller through while {@code qits.auth.machine.required} is off. An agent
 * role that arrives without a token (a forwarded header) carries no claims, so every check below
 * answers "no" for it: it is refused, not waved through.
 */
public final class AgentAccess {

  /** The role an agent's own token carries. */
  public static final String AGENT_ROLE = "qits:agent";

  /** Platform services. */
  public static final String SYSTEM_ROLE = "qits:system";

  /** People. */
  public static final String ADMIN_ROLE = "qits:admin";

  /**
   * An admin workspace's agent (qits-628 follow-up): the coding agent running inside a workspace
   * that holds the host's docker socket. For now it may use everything {@link #ADMIN_ROLE} may
   * use — stated explicitly beside every {@code qits:admin} check rather than implied by an
   * augmentor or a prefix match, so an endpoint that must stay human-only can have it removed one
   * at a time later.
   */
  public static final String ADMIN_AGENT_ROLE = "qits:admin-agent";

  /**
   * A project's delivery desk (epic qits-1440): its {@code desk-delivery} token carries this beside
   * {@link #AGENT_ROLE}. Since qits-1596 it opens the coordinator's ({@link #ADMIN_AGENT_ROLE})
   * writes, bound to the token's {@code project} ({@link #isDesk}), except the commits and pushes
   * and the doors that would change the desk's own rights. It is <b>never</b> one of the wider roles
   * {@link #isBoundAgent} is handed: the desk holds {@code qits:agent} too, and on a door that
   * admits an agent it is bound like one.
   */
  public static final String DESK_ROLE = "qits:desk";

  /**
   * The delivery desk's coordinator rights (epic qits-1621): its {@code desk-delivery} token carries
   * this beside {@link #AGENT_ROLE} and {@link #DESK_ROLE}, and every door that admits {@link
   * #ADMIN_AGENT_ROLE} admits it too — except the commits and pushes and the doors that would change
   * the desk's own rights. It is <b>not</b> a wider role: the desk still holds {@link #DESK_ROLE},
   * so {@link #isDesk} keeps binding it to its token's {@code project} wherever a door binds the
   * desk, and it is never handed to {@link #isBoundAgent} as one of the roles that unbind an agent.
   */
  public static final String DESK_ADMIN_ROLE = "qits:desk-admin";

  /** The claim that lists the Git refs a token may push (plan contract C1). */
  public static final String GIT_REFS_CLAIM = "git_refs";

  private static final String HEADS = "refs/heads/";

  private AgentAccess() {}

  /**
   * True when the caller comes in as an agent: it holds {@link #AGENT_ROLE} and none of {@code
   * wider}, the roles the door admitted before agents could reach it. Only such a caller is bound;
   * a caller that holds one of the wider roles is judged as before.
   */
  public static boolean isBoundAgent(SecurityIdentity identity, String... wider) {
    if (identity == null || identity.isAnonymous() || !identity.hasRole(AGENT_ROLE)) {
      return false;
    }
    for (String role : wider) {
      if (identity.hasRole(role)) {
        return false;
      }
    }
    return true;
  }

  /**
   * True when the caller comes in as a project's delivery desk (qits-1596): it holds {@link
   * #DESK_ROLE} and none of {@code qits:admin}, {@code qits:admin-agent} or {@code qits:system}. Such
   * a caller is bound to its token's {@code project} claim wherever a desk is admitted; a caller
   * holding a wider role is judged by that role, as {@link #isBoundAgent} judges an agent.
   */
  public static boolean isDesk(SecurityIdentity identity) {
    return identity != null
        && !identity.isAnonymous()
        && identity.hasRole(DESK_ROLE)
        && !identity.hasRole(ADMIN_ROLE)
        && !identity.hasRole(ADMIN_AGENT_ROLE)
        && !identity.hasRole(SYSTEM_ROLE);
  }

  /**
   * Refuses, with a 403, a {@linkplain #isDesk delivery desk} whose token's {@code project} claim
   * does not cover {@code projectId}; every other caller passes untouched (qits-1596). Called with
   * the project resolved from the row the write names, so an id naming nothing still answers 404
   * first.
   */
  public static void requireDeskProject(SecurityIdentity identity, String projectId) {
    if (isDesk(identity) && !coversProject(identity, projectId)) {
      throw new DomainException(
          403, "The delivery desk may act only in its own project, and this belongs to another.");
    }
  }

  /** True when the token's {@code project} claim covers this project. */
  public static boolean coversProject(SecurityIdentity identity, String projectId) {
    return MachineIdentity.claimMatches(identity, QitsClaims.PROJECT, projectId);
  }

  /** True when the token was issued to this client: its {@code sub} is the client id. */
  public static boolean isClient(SecurityIdentity identity, String clientId) {
    if (clientId == null || clientId.isBlank()) {
      return false;
    }
    return identity != null
        && identity.getPrincipal() instanceof JsonWebToken jwt
        && clientId.equals(jwt.getSubject());
  }

  /**
   * True when the token may push {@code branch}: {@code refs/heads/<branch>} equals an entry of its
   * {@code git_refs}, or starts with what precedes an entry's one trailing {@code *} and is longer
   * than it (qits-1152). {@code refs/heads/external/*} covers what lies under {@code external/};
   * {@code refs/heads/ticket/qits-1-*} covers {@code ticket/qits-1-fix}, the names an agent picks
   * beside its wrapper branch. A {@code *} anywhere else makes the entry cover nothing. No claim
   * covers nothing.
   */
  public static boolean coversBranch(SecurityIdentity identity, String branch) {
    if (branch == null || branch.isBlank()) {
      return false;
    }
    String ref = HEADS + branch.trim();
    for (String entry : gitRefs(identity)) {
      int star = entry.indexOf('*');
      if (star < 0) {
        if (entry.equals(ref)) {
          return true;
        }
        continue;
      }
      if (star != entry.length() - 1) {
        continue; // a `*` that is not the last character is not a pattern this reads
      }
      String prefix = entry.substring(0, star);
      if (ref.length() > prefix.length() && ref.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }

  /**
   * The token's {@code git_refs}, empty when there is no token or no such claim. quarkus-oidc and
   * smallrye-jwt both hand a JSON array back as a collection of JSON strings.
   */
  static List<String> gitRefs(SecurityIdentity identity) {
    if (identity == null || !(identity.getPrincipal() instanceof JsonWebToken jwt)) {
      return List.of();
    }
    if (!(jwt.getClaim(GIT_REFS_CLAIM) instanceof Collection<?> values)) {
      return List.of();
    }
    List<String> refs = new ArrayList<>();
    for (Object value : values) {
      if (value instanceof JsonString json) {
        refs.add(json.getString());
      } else if (value instanceof String text) {
        refs.add(text);
      }
    }
    return refs;
  }
}
