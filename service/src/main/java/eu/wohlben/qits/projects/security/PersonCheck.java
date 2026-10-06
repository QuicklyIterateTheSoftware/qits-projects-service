package eu.wohlben.qits.projects.security;

import eu.wohlben.qits.projects.error.DomainException;
import io.quarkus.arc.Arc;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.vertx.core.http.Cookie;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.JsonString;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * <b>The one definition of "a person" in this service</b> (qits-891): the caller is somebody
 * holding {@code qits:admin} <em>and this process has verified that for itself</em>, rather than
 * believing a header. Every door whose decision only a person may make asks here — today the
 * release-request approve and decline and the campaign APPROVAL criterion; qits-887 (the
 * READY_FOR_DEV approval) and qits-897 (the pre-run override) must reuse it rather than growing a
 * second spelling.
 *
 * <p><b>Why {@code @RolesAllowed("qits:admin")} is not enough.</b> The forward-auth identity is
 * whatever {@code X-Qits-User}/{@code X-Qits-Roles} assert. The edge strips and re-asserts them, but
 * anything that reaches this service by its wire alias can assert them too, and an approval is the
 * one place where "anything on the network" must not be able to sign a person's name. So the role
 * check stays as the first, cheap filter and this is the second, and it accepts exactly two proofs:
 *
 * <ol>
 *   <li><b>A browser session</b>: the {@code qits-session} cookie, introspected at qits-idp by this
 *       service with its own service client ({@link SessionIntrospection}). The edge keeps the
 *       cookie on every request whose identity it built from it, so it is here whenever the
 *       forwarded headers are. The session's roles must hold {@code qits:admin}.
 *   <li><b>A person's {@code qits} CLI</b>: a bearer the oidc tenant has already validated
 *       (idp-signed, audience {@code qits-platform}) with {@code credential_type=cli}, no {@code
 *       context_kind}, no {@code clients/…} group and {@code qits:admin} in its groups. Those are
 *       the marks qits-idp puts on the token it mints for a person's CLI and on nothing else: a
 *       service client carries {@code clients/<id>}, a commissioned agent or workspace carries
 *       {@code context_kind}, and a workstation token says {@code workstation} and holds no admin.
 * </ol>
 *
 * <p>A bearer is <b>the</b> credential when there is one: a caller that presents a token is that
 * token, and a browser cookie riding alongside it does not turn a machine into a person (the edge
 * removes the cookie from such a request anyway).
 *
 * <p><b>The name recorded is the proof's, never the caller's.</b> For a session it is idp's {@code
 * username} — the same name the edge would have forwarded. For a CLI token it is the principal
 * quarkus-oidc derived from the token, which is what every other door records for that caller; the
 * idp writes the user's id into {@code sub} and no name claim today, so until it does that is the
 * id.
 *
 * <p><b>Dev mode only</b> ({@link LaunchMode#DEVELOPMENT} — never {@code NORMAL}, and not {@code
 * TEST}): with no edge and no idp in front of {@code quarkus:dev}, a forwarded {@code qits:admin}
 * identity — qits-auth-core's dev user — counts, so the doors can be clicked locally. A test sees
 * the deployed rule.
 */
@ApplicationScoped
public class PersonCheck {

  /** The cookie qits-idp sets and the edge introspects: a contract with both, not a setting. */
  public static final String SESSION_COOKIE = "qits-session";

  static final String CREDENTIAL_TYPE_CLAIM = "credential_type";
  static final String CLI_CREDENTIAL = "cli";
  static final String CONTEXT_KIND_CLAIM = "context_kind";
  static final String CLIENT_GROUP_PREFIX = "clients/";

  @Inject SessionIntrospection sessions;

  @Inject SecurityIdentity identity;

  @Inject HttpServerRequest request;

  /** The current HTTP request, if any, for {@link #verifiedAdmin(SecurityIdentity)}. */
  @Inject CurrentVertxRequest currentRequest;

  /**
   * The verified person's name, or a 403 saying what would have counted.
   *
   * @throws DomainException 403 when the caller is not a person this service could verify
   */
  public String requireAdmin() {
    return verifiedAdmin()
        .orElseThrow(
            () ->
                new DomainException(
                    403,
                    "Only a person may make this decision, verified here: a browser session or a"
                        + " person's qits CLI, holding qits:admin. Asserted identity headers,"
                        + " service clients, agents, CI and workstation tokens do not count."));
  }

  /** The verified person's name, or empty when the caller is not one. */
  public Optional<String> verifiedAdmin() {
    Cookie cookie = request.getCookie(SESSION_COOKIE);
    return verify(identity, cookie == null ? null : cookie.getValue(), LaunchMode.current());
  }

  /**
   * The verified person behind {@code caller}, or empty — {@link #verifiedAdmin()} for a door that
   * is handed its caller's identity rather than injecting it (qits-887's lifecycle doors, which a
   * suite also drives in process). The same decision: the session cookie is read off the current
   * HTTP request when there is one, and a call with no request in flight has none to offer.
   */
  public Optional<String> verifiedAdmin(SecurityIdentity caller) {
    return verify(caller, currentSessionCookie(), LaunchMode.current());
  }

  /** The {@code qits-session} cookie of the HTTP request in flight, or null when there is none. */
  private String currentSessionCookie() {
    if (!Arc.container().requestContext().isActive()) {
      return null;
    }
    RoutingContext context = currentRequest.getCurrent();
    if (context == null) {
      return null;
    }
    Cookie cookie = context.request().getCookie(SESSION_COOKIE);
    return cookie == null ? null : cookie.getValue();
  }

  /** The whole decision, with the request's parts handed in — what the unit test drives. */
  Optional<String> verify(SecurityIdentity caller, String sessionCookie, LaunchMode mode) {
    if (caller != null && caller.getPrincipal() instanceof JsonWebToken token) {
      return cliPerson(token);
    }
    if (sessionCookie != null && !sessionCookie.isBlank()) {
      Optional<String> person =
          sessions
              .introspect(sessionCookie)
              .filter(session -> session.roles().contains(AgentAccess.ADMIN_ROLE))
              .map(SessionIntrospection.Session::username)
              .filter(name -> !name.isBlank());
      if (person.isPresent()) {
        return person;
      }
    }
    if (mode == LaunchMode.DEVELOPMENT
        && caller != null
        && !caller.isAnonymous()
        && caller.hasRole(AgentAccess.ADMIN_ROLE)) {
      return Optional.of(caller.getPrincipal().getName());
    }
    return Optional.empty();
  }

  /**
   * A validated bearer that is a person's CLI holding {@code qits:admin}, or empty. The groups are
   * read off the token itself, not off the identity, so an augmentor that added a role cannot make
   * a machine look like a person.
   */
  static Optional<String> cliPerson(JsonWebToken token) {
    if (!CLI_CREDENTIAL.equals(text(token.getClaim(CREDENTIAL_TYPE_CLAIM)))) {
      return Optional.empty();
    }
    if (token.getClaim(CONTEXT_KIND_CLAIM) != null) {
      return Optional.empty();
    }
    List<String> groups = groups(token);
    if (groups.stream().anyMatch(group -> group.startsWith(CLIENT_GROUP_PREFIX))
        || !groups.contains(AgentAccess.ADMIN_ROLE)) {
      return Optional.empty();
    }
    return Optional.ofNullable(token.getName()).filter(name -> !name.isBlank());
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
