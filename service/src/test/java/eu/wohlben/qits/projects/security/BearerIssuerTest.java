package eu.wohlben.qits.projects.security;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;

import eu.wohlben.qits.entities.api.PersonGateIdpTenant;
import eu.wohlben.qits.servicemock.idp.MockIdp;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/**
 * <b>Only the issuer qits-idp publishes is accepted</b> (qits-708). The shipped tenant has discovery
 * on and no issuer key at all, so the issuer quarkus-oidc checks every bearer's {@code iss} against
 * is the {@code issuer} of the discovery document it reads. Before qits-708 discovery was off and
 * no issuer was configured, and quarkus-oidc then checks no {@code iss} at all: a token signed by a
 * published key and addressed to {@code qits-platform} was accepted whoever claimed to have issued
 * it.
 *
 * <p>The far side is qits-service-mock's {@link MockIdp}: it serves a discovery document whose
 * {@code issuer} is the {@code iss} it mints, and a JWKS at the {@code jwks_uri} that document
 * names. The tenant is the shipped one with the gate on and {@code auth-server-url} moved to the
 * mock ({@link PersonGateIdpTenant}, whose application this class shares rather than booting one
 * more). That the boot read the discovery document is {@link BearerJwksTest}'s first method, the
 * one place that sees the boot's traffic on its own.
 */
@QuarkusTest
@WithTestResource(PersonGateIdpTenant.class)
class BearerIssuerTest {

  /** qits-ci's trigger catalogue: names qits:system beside qits:admin, and dials no peer. */
  private static final String GUARDED_ROUTE = "/projects/api/repositories";

  /** The one platform audience, which the shipped tenant enforces. */
  private static final String AUDIENCE = "qits-platform";

  /** Another environment's idp address: an issuer this service trusts under no name. */
  private static final String FOREIGN_ISSUER = "http://dev-qits-idp:8080/idp";

  @Test
  void aBearerFromThePublishedIssuerIsAccepted() {
    String token =
        MockIdp.attach().token().subject("qits-ci").audience(AUDIENCE).groups("qits:system").mint();
    given()
        .header("Authorization", "Bearer " + token)
        .when()
        .get(GUARDED_ROUTE)
        .then()
        .statusCode(200)
        .body("repositories", notNullValue());
  }

  @Test
  void aBearerFromAnotherIssuerIsRefused() {
    // Correctly signed with the published key, the right audience, not expired: only `iss` is wrong.
    String token =
        MockIdp.attach()
            .token()
            .subject("qits-ci")
            .audience(AUDIENCE)
            .groups("qits:system")
            .claim("iss", FOREIGN_ISSUER)
            .mint();
    given().header("Authorization", "Bearer " + token).when().get(GUARDED_ROUTE).then().statusCode(401);
  }
}
