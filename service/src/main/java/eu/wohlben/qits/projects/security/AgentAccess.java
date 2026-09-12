package eu.wohlben.qits.projects.security;

import eu.wohlben.qits.auth.MachineIdentity;
import eu.wohlben.qits.auth.QitsClaims;
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
   * {@code git_refs}, or lies under an entry that ends in {@code /*}. No claim covers nothing.
   */
  public static boolean coversBranch(SecurityIdentity identity, String branch) {
    if (branch == null || branch.isBlank()) {
      return false;
    }
    String ref = HEADS + branch.trim();
    for (String entry : gitRefs(identity)) {
      if (entry.equals(ref)) {
        return true;
      }
      if (entry.endsWith("/*")) {
        String prefix = entry.substring(0, entry.length() - 1);
        if (ref.length() > prefix.length() && ref.startsWith(prefix)) {
          return true;
        }
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
