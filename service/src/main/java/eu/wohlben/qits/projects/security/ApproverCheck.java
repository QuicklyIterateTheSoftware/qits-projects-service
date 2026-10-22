package eu.wohlben.qits.projects.security;

import eu.wohlben.qits.projects.control.approval.Approver;
import eu.wohlben.qits.projects.error.DomainException;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.security.identity.SecurityIdentity;
import io.vertx.core.http.Cookie;
import io.vertx.core.http.HttpServerRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.JsonString;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * <b>Which approval roles the caller holds, proved by this service itself</b> (epic qits-1038) —
 * {@link PersonCheck}'s sibling for the release-request approve and decline doors, which since the
 * epic admit agents as well as people. It answers an {@link Approver}: the name the decision is
 * recorded under and the roles held. <b>Never from headers</b>: an asserted {@code X-Qits-*} pair is
 * what anything on the network can send, and it proves nothing here.
 *
 * <ul>
 *   <li><b>{@link PersonCheck}'s proof</b> — a {@code qits-session} cookie this service introspects,
 *       or a person's {@code qits} CLI bearer — is {@code person}, under the proof's name.
 *   <li><b>A validated idp JWT of an agent</b> is {@code agent} of the project its {@code project}
 *       claim names: {@code qits:agent} in its {@code groups}, a {@code context_kind} of {@code
 *       workspace} or {@code workspace-admin} (what qits-workspaces' commissioner mints for an
 *       agent), no {@code clients/…} group other than its own and a non-blank {@code project} claim.
 *       The groups are read off the token, not off the identity, so an augmentor that added a role
 *       cannot make a machine look like an agent.
 *       <p><b>Its own {@code clients/<sub>} is not a service client's mark.</b> qits-idp's {@code
 *       ClientRoles.mintedFor} adds {@code clients/<client id>} to the groups of <em>every</em> client
 *       token it mints, a commissioned agent's included, with the client id as {@code sub}; refusing
 *       any {@code clients/…} group would refuse every real agent. What sets a service client apart
 *       is that it carries no {@code context_kind}; a second {@code clients/…} group still refuses.
 *       <p><b>Its name is the token's principal</b> ({@link JsonWebToken#getName()}) — the name
 *       every other door, and so {@code ReleaseRequestSource.addedBy}, records for it — with its
 *       {@code sub} as an alias where the two differ, so an excluded author is excluded under
 *       either. (qits-idp sets neither {@code upn} nor {@code preferred_username} on a client token
 *       today, so the two are the same client id.)
 *   <li><b>A validated idp JWT of the project's delivery desk</b> (epic qits-1440) is {@code desk}
 *       and {@code agent} of the project its {@code project} claim names, and never {@code person}:
 *       {@code qits:agent} <em>and</em> {@code qits:desk} in its {@code groups}, a {@code
 *       context_kind} of {@value #DESK_CONTEXT_KIND} (what this service commissions for the desk's
 *       delivery token), no {@code clients/…} group other than its own — the agent's rule, for the
 *       agent's reason — and a non-blank {@code project} claim. <b>Its name is its {@code sub}</b>,
 *       the delivery token's subject, with the principal as an alias where the two differ.
 *   <li><b>Anything else</b> — asserted headers, a service client, {@code qits:system}, CI, a
 *       workstation token — holds nothing.
 * </ul>
 *
 * <p>An agent is <b>not</b> bound to the request's project here: a reviewer of project A approves in
 * project B. Which requirement it may satisfy is the domain's question, asked with these roles.
 */
@ApplicationScoped
public class ApproverCheck {

  /** The {@code context_kind}s qits-workspaces mints for an agent. */
  static final Set<String> AGENT_CONTEXT_KINDS = Set.of("workspace", "workspace-admin");

  static final String PROJECT_CLAIM = "project";

  /** The {@code context_kind} of the delivery desk's token (epic qits-1440). */
  static final String DESK_CONTEXT_KIND = "desk-delivery";

  /** The role the delivery desk's token carries beside {@code qits:agent}. */
  static final String DESK_ROLE = "qits:desk";

  @Inject PersonCheck persons;

  @Inject SecurityIdentity identity;

  @Inject HttpServerRequest request;

  /**
   * The caller as an approver, or a 403 saying what would have counted.
   *
   * @throws DomainException 403 when this service could prove no approval role
   */
  public Approver requireApprover() {
    return approver()
        .orElseThrow(
            () ->
                new DomainException(
                    403,
                    "Only a verified approver may make this decision: a person (a browser session"
                        + " or a person's qits CLI, holding qits:admin), an agent (its own"
                        + " workspace token, carrying its project) or the project's delivery desk"
                        + " (its delivery token). Asserted identity headers,"
                        + " service clients, CI and workstation tokens do not count."));
  }

  /** The caller as an approver, or empty where it holds no approval role. */
  public Optional<Approver> approver() {
    Cookie cookie = request.getCookie(PersonCheck.SESSION_COOKIE);
    return decide(identity, cookie == null ? null : cookie.getValue(), LaunchMode.current());
  }

  /** The whole decision with the request's parts handed in — what the unit test drives. */
  Optional<Approver> decide(SecurityIdentity caller, String sessionCookie, LaunchMode mode) {
    Optional<String> person = persons.verify(caller, sessionCookie, mode);
    if (person.isPresent()) {
      return Optional.of(Approver.person(person.get()));
    }
    if (caller != null && caller.getPrincipal() instanceof JsonWebToken token) {
      Optional<Approver> desk = desk(token);
      return desk.isPresent() ? desk : agent(token);
    }
    return Optional.empty();
  }

  /**
   * A validated bearer that is the delivery desk's own token, as the desk of its project (and
   * nothing else); else empty.
   */
  static Optional<Approver> desk(JsonWebToken token) {
    if (!DESK_CONTEXT_KIND.equals(text(token.getClaim(PersonCheck.CONTEXT_KIND_CLAIM)))) {
      return Optional.empty();
    }
    String subject = token.getSubject();
    List<String> groups = groups(token);
    if (subject == null
        || subject.isBlank()
        || !groups.contains(AgentAccess.AGENT_ROLE)
        || !groups.contains(DESK_ROLE)
        || foreignClientGroup(groups, subject)) {
      return Optional.empty();
    }
    String project = text(token.getClaim(PROJECT_CLAIM));
    if (project == null || project.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(Approver.desk(subject, token.getName(), project.trim()));
  }

  /** Whether {@code groups} carries a {@code clients/…} group other than {@code subject}'s own. */
  private static boolean foreignClientGroup(List<String> groups, String subject) {
    String ownGroup = subject == null ? null : PersonCheck.CLIENT_GROUP_PREFIX + subject;
    return groups.stream()
        .anyMatch(
            group -> group.startsWith(PersonCheck.CLIENT_GROUP_PREFIX) && !group.equals(ownGroup));
  }

  /** A validated bearer that is an agent's own token, as the agent of its project; else empty. */
  static Optional<Approver> agent(JsonWebToken token) {
    String kind = text(token.getClaim(PersonCheck.CONTEXT_KIND_CLAIM));
    if (kind == null || !AGENT_CONTEXT_KINDS.contains(kind)) {
      return Optional.empty();
    }
    String subject = token.getSubject();
    List<String> groups = groups(token);
    if (!groups.contains(AgentAccess.AGENT_ROLE) || foreignClientGroup(groups, subject)) {
      return Optional.empty();
    }
    String project = text(token.getClaim(PROJECT_CLAIM));
    String principal = token.getName();
    String name = principal == null || principal.isBlank() ? subject : principal;
    if (project == null || project.isBlank() || name == null || name.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(Approver.agent(name, subject, project.trim()));
  }

  /** {@code groups}, as quarkus-oidc (strings) or smallrye-jwt (JSON strings) hands it back. */
  private static List<String> groups(JsonWebToken token) {
    List<String> groups = new ArrayList<>();
    if (token.getClaim("groups") instanceof Collection<?> values) {
      for (Object value : values) {
        String group = text(value);
        if (group != null) {
          groups.add(group);
        }
      }
    }
    return groups;
  }

  private static String text(Object value) {
    if (value instanceof JsonString json) {
      return json.getString();
    }
    return value == null ? null : value.toString();
  }
}
