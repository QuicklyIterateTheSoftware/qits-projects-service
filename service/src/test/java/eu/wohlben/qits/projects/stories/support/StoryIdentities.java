package eu.wohlben.qits.projects.stories.support;

import eu.wohlben.qits.servicemock.idp.MockIdp;
import io.restassured.specification.RequestSpecification;

/**
 * The two identity tracks qits-projects accepts, one helper each — because a story that presented
 * the wrong one would be documenting a door that does not exist.
 *
 * <h2>A machine is a bearer</h2>
 *
 * <p>{@link #platformService(RequestSpecification)} presents an RS256 token minted by {@link
 * MockIdp} against the very JWKS the launched process fetches when a bearer first arrives: {@code
 * aud=qits-platform} (what {@code quarkus.oidc.token.audience} pins as a literal in
 * {@code application.properties} — the one audience qits-idp puts on every token it mints) and
 * {@code groups=[qits:system]} — qits-idp copies a client's
 * roles into that claim and quarkus-oidc reads it as roles with no configuration at all.
 *
 * <h2>A person is a bearer too</h2>
 *
 * <p>{@link #person(RequestSpecification, String)} presents a person's token from the same idp:
 * the person's name in {@code sub}, the platform audience and {@code qits:admin} in {@code groups}.
 * Forward-auth — the identity header pair the edge used to assert —
 * leaves qits-auth-core with qits-1338 (epic qits-543), so a signed-in person reaches this service
 * the way a machine does, and the roles are what tell them apart. Using it is what makes a story
 * say "a product owner" honestly rather than dressing a person up as a machine.
 *
 * <p><b>The synthetic {@code %test} dev user is not available here, and that is the point.</b>
 * {@code qits.auth.forward.dev-user} is {@code %test}-scoped, and a launched artifact runs in
 * {@code NORMAL} mode — so an anonymous request really is anonymous and the roles below are the
 * only thing opening these doors. Every refusal in {@code stories.refusals} is a claim only a
 * packaged run can make: inside a {@code @QuarkusTest} the dev identity holds all four platform
 * roles and no route here would refuse anybody.
 *
 * <h2>A verified person is neither of those (qits-887, qits-891)</h2>
 *
 * <p>{@link #person(RequestSpecification, String)}'s bearer is enough to open any {@code
 * qits:admin} route, but it is deliberately <b>not</b> enough to satisfy {@code PersonCheck} — the
 * door a scheduling move (REFINED -&gt; READY_FOR_DEV) stands behind, which accepts a person's CLI
 * token only: it carries no {@code credential_type=cli}. {@link
 * #personsCli(RequestSpecification, String)} is the second of qits-891's two proofs, a person's
 * {@code qits} CLI bearer: idp-signed, {@code credential_type=cli}, no {@code context_kind}, {@code
 * qits:admin} in its groups — the marks a service client and a commissioned agent never carry. A
 * story that needs to schedule something presents this, not {@link #person}.
 */
public final class StoryIdentities {

  /** The audience this service enforces — a literal, because {@code application.properties} pins it. */
  public static final String AUDIENCE = "qits-platform";

  /** The machine role a platform peer holds: the bootstrap's doors and the four shared reads. */
  public static final String MACHINE_ROLE = "qits:system";

  /** The human role a signed-in admin holds. */
  public static final String HUMAN_ROLE = "qits:admin";

  private StoryIdentities() {}

  /**
   * A platform peer's bearer.
   *
   * <p>Minted fresh per call rather than cached: a token is a credential, and a helper that handed
   * the same string to two stories would make {@link
   * eu.wohlben.qits.userflows.report.ReportAssertions#assertNotLeaked} a weaker claim than it reads
   * as. The {@code sub} names which peer, because the audit log and a reader both care.
   */
  public static String platformToken(String subject) {
    return MockIdp.attach()
        .token()
        .subject(subject)
        .audience(AUDIENCE)
        .groups(MACHINE_ROLE)
        .mint();
  }

  /** {@code given()} with a platform peer's bearer on it. */
  public static RequestSpecification platformService(
      RequestSpecification request, String subject) {
    return request.header("Authorization", "Bearer " + platformToken(subject));
  }

  /**
   * A signed-in admin's bearer: {@code subject} in {@code sub} (the name the audit rows record),
   * {@code qits:admin} in its groups, and no {@code credential_type} — a person, not a person's CLI.
   * Minted fresh per call like {@link #platformToken}.
   */
  public static String personToken(String subject) {
    return MockIdp.attach()
        .token()
        .subject(subject)
        .audience(AUDIENCE)
        .groups(HUMAN_ROLE)
        .mint();
  }

  /** {@code given()} with a signed-in admin's bearer on it. */
  public static RequestSpecification person(RequestSpecification request, String user) {
    return request.header("Authorization", "Bearer " + personToken(user));
  }

  /**
   * A person's {@code qits} CLI bearer — qits-891's second proof, minted fresh per call like {@link
   * #platformToken}. {@code changedBy} ends up holding {@code subject}: {@code PersonCheck} reads
   * the verified person's name off the token itself ({@code JsonWebToken#getName()}, which falls
   * back to {@code sub} since this mock sets no {@code upn}/{@code preferred_username}).
   */
  public static String personsCliToken(String subject) {
    return MockIdp.attach()
        .token()
        .subject(subject)
        .audience(AUDIENCE)
        .groups(HUMAN_ROLE)
        .claim("credential_type", "cli")
        .mint();
  }

  /** {@code given()} with a person's qits CLI bearer on it — what a scheduling move needs. */
  public static RequestSpecification personsCli(RequestSpecification request, String subject) {
    return request.header("Authorization", "Bearer " + personsCliToken(subject));
  }
}
