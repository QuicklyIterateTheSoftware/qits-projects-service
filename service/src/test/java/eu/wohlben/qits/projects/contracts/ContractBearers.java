package eu.wohlben.qits.projects.contracts;

import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.SecurityIdentityAugmentor;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.vertx.http.runtime.security.HttpSecurityUtils;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * <b>The bearers a provider state hands its consumer</b>, for the doors that judge the caller's
 * token rather than its role (the person-CLI bearer of the approve and decline doors; the
 * desk-runner register door that first needed it went with the runners, qits-1551).
 * qits-ci-service's {@code ContractBearers}, in this provider's package (qits-1163).
 *
 * <p>The {@code %test} suite runs no idp and no edge: every request is the forwarded dev user. A
 * consumer, though, sends {@code Authorization: Bearer <token>}. A state registers the bearer it
 * returns as its {@code authorization} param ({@link #grant}), and this augmentor turns a request
 * carrying it into the machine identity the edge would forward for that token: a JWT principal with
 * its {@code sub}, the platform audience and its roles. So the golden master and the pact both send
 * the header a real consumer sends.
 *
 * <p>{@link #refuseUnknown} answers a bearer no state granted 401, as a token no idp issued is in a
 * deployment; no state arms it yet.
 *
 * <p><b>Idle unless a state armed it</b>: with nothing granted and the gate not armed it returns the
 * identity unchanged, so no other suite sees it. {@link ProviderStates#cleanUp()} disarms it again.
 */
@ApplicationScoped
public class ContractBearers implements SecurityIdentityAugmentor {

  static final String PLATFORM_AUDIENCE = "qits-platform";

  /** What a granted bearer stands for: its subject, its roles, and claims beyond sub and aud. */
  record Grant(String subject, Set<String> roles, Map<String, Object> claims) {}

  private final Map<String, Grant> grants = new ConcurrentHashMap<>();

  private volatile boolean refuseUnknown;

  /** A request with {@code Authorization: <authorization>} is {@code subject} holding {@code roles}. */
  public void grant(String authorization, String subject, String... roles) {
    grants.put(authorization, new Grant(subject, Set.of(roles), Map.of()));
  }

  /**
   * {@link #grant}, with claims beyond sub and aud — a service client's {@code clients/<id>} group,
   * an agent's {@code context_kind} and {@code project} — for a suite that drives a machine's bearer
   * at a person's door (qits-914's {@code UpstreamOverrideDoorTest}).
   */
  public void grant(
      String authorization, String subject, Map<String, Object> claims, String... roles) {
    grants.put(authorization, new Grant(subject, Set.of(roles), Map.copyOf(claims)));
  }

  /**
   * A request with {@code Authorization: <authorization>} is a person's {@code qits} CLI token for
   * {@code subject} holding {@code qits:admin}: {@code credential_type=cli} and the admin group,
   * the marks {@code PersonCheck} accepts as its second proof of a person (the approve and decline
   * doors). What qits-idp mints for a person's {@code qits login}.
   */
  public void grantPerson(String authorization, String subject) {
    grants.put(
        authorization,
        new Grant(
            subject,
            Set.of("qits:admin"),
            Map.of("credential_type", "cli", "groups", Set.of("qits:admin"))));
  }

  /** A bearer no state granted is refused 401. */
  public void refuseUnknown() {
    refuseUnknown = true;
  }

  public void reset() {
    grants.clear();
    refuseUnknown = false;
  }

  @Override
  public Uni<SecurityIdentity> augment(
      SecurityIdentity identity, AuthenticationRequestContext context) {
    return Uni.createFrom().item(identity);
  }

  @Override
  public Uni<SecurityIdentity> augment(
      SecurityIdentity identity,
      AuthenticationRequestContext context,
      Map<String, Object> attributes) {
    if (grants.isEmpty() && !refuseUnknown) {
      return Uni.createFrom().item(identity);
    }
    RoutingContext routing = HttpSecurityUtils.getRoutingContextAttribute(attributes);
    String authorization = routing == null ? null : routing.request().getHeader("Authorization");
    if (authorization == null || authorization.isBlank()) {
      return Uni.createFrom().item(identity);
    }
    Grant grant = grants.get(authorization);
    if (grant == null) {
      if (refuseUnknown) {
        return Uni.createFrom().failure(new AuthenticationFailedException("unknown bearer"));
      }
      return Uni.createFrom().item(identity);
    }
    QuarkusSecurityIdentity.Builder machine =
        QuarkusSecurityIdentity.builder().setPrincipal(new Token(grant.subject(), grant.claims()));
    grant.roles().forEach(machine::addRole);
    return Uni.createFrom().item(machine.build());
  }

  /** The JWT the edge would forward: a subject, the platform audience and the grant's claims. */
  private record Token(String subject, Map<String, Object> claims) implements JsonWebToken {

    @Override
    public String getName() {
      return subject;
    }

    @Override
    public String getSubject() {
      return subject;
    }

    @Override
    public Set<String> getAudience() {
      return Set.of(PLATFORM_AUDIENCE);
    }

    @Override
    public Set<String> getClaimNames() {
      Set<String> names = new java.util.TreeSet<>(claims.keySet());
      names.add("sub");
      names.add("aud");
      return names;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getClaim(String claimName) {
      return switch (claimName) {
        case "sub" -> (T) subject;
        case "aud" -> (T) Set.of(PLATFORM_AUDIENCE);
        default -> (T) claims.get(claimName);
      };
    }
  }
}
