package eu.wohlben.qits.projects.contracts;

import io.quarkus.arc.Arc;
import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.SecurityIdentityAugmentor;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.vertx.http.runtime.security.HttpSecurityUtils;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * <b>The bearers a provider state hands its consumer</b>, for the doors that judge the caller's
 * token rather than its role (the person-CLI bearer of the approve and decline doors; the
 * desk-runner register door that first needed it went with the runners, qits-1551).
 * qits-ci-service's {@code ContractBearers}, in this provider's package (qits-1163).
 *
 * <p>The {@code %test} suite runs no idp and no edge. A consumer, though, sends {@code
 * Authorization: Bearer <token>}. A state registers the bearer it returns as its {@code
 * authorization} param ({@link #grant}), and this augmentor turns a request carrying it into the
 * machine identity the edge would forward for that token: a JWT principal with its {@code sub}, the
 * platform audience and its roles. So the golden master and the pact both send the header a real
 * consumer sends.
 *
 * <p>{@link #refuseUnknown} answers a bearer no state granted 401, as a token no idp issued is in a
 * deployment; no state arms it yet.
 *
 * <p><b>Idle unless a state armed it</b>: with nothing granted and the gate not armed it returns the
 * identity unchanged, so no other suite sees it. {@link ProviderStates#cleanUp()} disarms it again.
 *
 * <p><b>It is also how a test with several callers names each of them</b> (epic qits-543,
 * qits-1369): forward-auth and its {@code %test} dev user leave qits-auth-core with qits-1338, so a
 * test that used to assert the forwarded identity header pair sends {@link #caller}'s value
 * in {@code Authorization} instead, which {@link SuiteCallerMechanism} answers. That identity is
 * what forward-auth made of the pair — a plain name holding the roles, <b>no token</b>, so no {@code
 * project}, {@code context_kind} or {@code credential_type} claim and {@code PersonCheck} still
 * reads a session cookie beside it — and its scheme is not {@code Bearer}, so a class that turns
 * the OIDC tenant on can use it beside real {@code MockIdp} bearers. A test with one caller uses
 * {@code @TestSecurity}.
 */
@ApplicationScoped
public class ContractBearers implements SecurityIdentityAugmentor {

  static final String PLATFORM_AUDIENCE = "qits-platform";

  /** What a granted bearer stands for: its subject, its roles, and claims beyond sub and aud. */
  record Grant(String subject, Set<String> roles, Map<String, Object> claims) {}

  private final Map<String, Grant> grants = new ConcurrentHashMap<>();

  /** The {@link #caller} values granted, answered by {@link SuiteCallerMechanism}. */
  private final Map<String, Grant> callers = new ConcurrentHashMap<>();

  private volatile boolean refuseUnknown;

  /** The scheme of a {@link #caller} value: not {@code Bearer}, so no OIDC tenant reads it. */
  static final String CALLER_SCHEME = "Suite ";

  /**
   * The {@code Authorization} value of a caller this suite names: {@code subject} holding {@code
   * roles} (each may be a comma-separated list), as a plain principal with no token — the identity
   * the forwarded header pair used to establish. Granted afresh on every call on the running
   * application's instance, so a {@link #reset} between tests never strands it. No roles is a named
   * caller holding none.
   */
  public static String caller(String subject, String... roles) {
    String[] each =
        Arrays.stream(roles)
            .flatMap(role -> Arrays.stream(role.split(",")))
            .map(String::trim)
            .filter(role -> !role.isEmpty())
            .distinct()
            .toArray(String[]::new);
    String authorization = CALLER_SCHEME + subject + "." + String.join("+", each);
    // Through a method, never the field: the instance is a client proxy, whose own fields are not
    // the bean's.
    Arc.container().instance(ContractBearers.class).get().grantCaller(authorization, subject, each);
    return authorization;
  }

  /** {@link #caller}'s grant, on the bean itself. */
  void grantCaller(String authorization, String subject, String... roles) {
    callers.put(authorization, new Grant(subject, Set.of(roles), Map.of()));
  }

  /**
   * The identity {@link SuiteCallerMechanism} answers for an {@code Authorization} value: a {@link
   * #caller}'s plain principal, or a {@link #grant}ed bearer's token; empty for anything else, which
   * the shipped mechanisms then decide. With no forward-auth dev user a request carrying only a
   * granted bearer is otherwise anonymous, and an augmentor never sees it.
   *
   * @throws AuthenticationFailedException for a bearer no state granted while {@link
   *     #refuseUnknown} is armed
   */
  Optional<SecurityIdentity> identityFor(String authorization) {
    Grant named = callers.get(authorization);
    if (named != null) {
      QuarkusSecurityIdentity.Builder identity =
          QuarkusSecurityIdentity.builder().setPrincipal(new QuarkusPrincipal(named.subject()));
      named.roles().forEach(identity::addRole);
      return Optional.of(identity.build());
    }
    Grant grant = grants.get(authorization);
    if (grant == null) {
      if (refuseUnknown && authorization.startsWith("Bearer ")) {
        throw new AuthenticationFailedException("unknown bearer");
      }
      return Optional.empty();
    }
    QuarkusSecurityIdentity.Builder machine =
        QuarkusSecurityIdentity.builder().setPrincipal(new Token(grant.subject(), grant.claims()));
    grant.roles().forEach(machine::addRole);
    return Optional.of(machine.build());
  }

  /**
   * The caller of a contract interaction that carries no credential of its own ({@link
   * GoldenMasterRecordingTest}, {@link ConsumerPactVerificationTest}): {@code dev} holding {@code
   * qits:admin} and {@code qits:system}, exactly the forward-auth dev user those recordings were made
   * as, so one recording serves a person's consumer and a machine's alike and no committed answer
   * moves. Named here because that user leaves qits-auth-core with qits-1338 (epic qits-543).
   */
  public static String consumerCaller() {
    return caller("dev", "qits:admin", "qits:system");
  }

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
    callers.clear();
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
