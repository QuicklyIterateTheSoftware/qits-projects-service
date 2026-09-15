package eu.wohlben.qits.projects.security;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.servicemock.idp.MockIdp;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import java.util.Optional;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * The shipped key path with the tenant on and {@link MockIdp} in place of qits-platform-idp — and
 * the pin on {@code quarkus.oidc.jwks.resolve-early=false}, which is the line that keeps the idp
 * off this process's startup path.
 *
 * <p>What it pins is a behaviour rather than a property value: <b>nothing but a bearer ever reaches
 * the idp</b>. Boot makes no call, so the HTTP listener opens whether the idp is answering or not;
 * a request carrying no token makes no call either; the first bearer fetches the key by its {@code
 * kid} and is accepted; later bearers are served from the cache. Re-adding {@code
 * resolve-early=true} — or dropping the line, since {@code true} is the default — fails the first
 * method with a fetch the boot made.
 *
 * <p>Why that matters here and not only in principle: this service shipped {@code
 * quarkus.oidc.connection-delay=30S} beside an early resolve, which put a thirty-second retry
 * against the idp in front of the listener, and a deploy was rolled back on 2026-09-14 because the
 * health probe got connection refused. See the block in {@code application.properties}.
 *
 * <p>The order is load-bearing: only a method that runs before any bearer can say "no fetch yet".
 */
@QuarkusTest
@WithTestResource(MockIdpTenant.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BearerJwksTest {

  /** Unguarded on purpose: what is under test is who the request became, never a status code. */
  private static final String IDENTITY = "/projects/api/test-identity";

  /** A platform service's subject, as the idp writes it into {@code sub}. */
  private static final String SUBJECT = "qits-ci";

  @Test
  @Order(1)
  void neitherBootNorHeaderTrafficEverReachesTheIdp() {
    // The listener is up and serving, and the mock idp has been asked for nothing at all: with
    // resolve-early=false the tenant is created without a network call, which is the whole point —
    // a deployment's health probe is answered while the idp is still starting.
    given()
        .header("X-Qits-User", "alice")
        .header("X-Qits-Roles", "qits:admin")
        .when()
        .get(IDENTITY)
        .then()
        .statusCode(200)
        .body("principal", equalTo("alice"));
    given().when().get(IDENTITY).then().statusCode(200).body("anonymous", equalTo(true));

    assertEquals(0, jwksFetches(), "only a bearer may reach the idp");
  }

  @Test
  @Order(2)
  void theFirstBearerFetchesTheKeyByItsKidAndBecomesTheIdentity() {
    MockIdp idp = MockIdp.attach();
    String token = idp.token().subject(SUBJECT).audience("qits-platform").groups("qits:system").mint();

    given()
        .header("Authorization", "Bearer " + token)
        .when()
        .get(IDENTITY)
        .then()
        .statusCode(200)
        .body("anonymous", equalTo(false))
        .body("principal", equalTo(SUBJECT))
        .body("roles", contains("qits:system"));

    // Exactly one, and it happened now rather than at boot: the token is what drove it.
    assertEquals(1, jwksFetches(), "the arriving bearer is what fetches the key");
  }

  @Test
  @Order(3)
  void laterBearersAreServedFromTheCachedKey() {
    MockIdp idp = MockIdp.attach();

    for (int i = 0; i < 3; i++) {
      String token =
          idp.token().subject(SUBJECT).audience("qits-platform").groups("qits:system").mint();
      given().header("Authorization", "Bearer " + token).when().get(IDENTITY).then().statusCode(200);
    }

    assertEquals(1, jwksFetches(), "deferring the fetch must not turn it into a fetch per request");
  }

  @Test
  @Order(4)
  void aTokenSignedByAKeyTheJwksNeverCarriedIsRefused() {
    // The flip side of trusting the published keys, and the proof that the deferred fetch really is
    // validating: same kid, same audience, a signature from a key the mock never published.
    String strangers =
        MockIdp.attach()
            .token()
            .subject(SUBJECT)
            .audience("qits-platform")
            .groups("qits:system")
            .signedByUnknownKey()
            .mint();

    given().header("Authorization", "Bearer " + strangers).when().get(IDENTITY).then().statusCode(401);
  }

  /**
   * The one assertion about a config key, and it is here because the behaviour above cannot see
   * this one: {@code connection-delay} only ever governs the connection the tenant makes while it
   * is being created, so with {@code resolve-early=false} re-adding it changes no observable
   * behaviour in this suite while putting the idp back on the startup path of a deployment that
   * boots before it. That asymmetry is exactly what got rolled back, so it is asserted rather than
   * left to a reviewer.
   */
  @Test
  @Order(5)
  void theIdpIsNotOnTheStartupPath() {
    assertTrue(
        ConfigProvider.getConfig()
            .getOptionalValue("quarkus.oidc.jwks.resolve-early", Boolean.class)
            .filter(early -> !early)
            .isPresent(),
        "the key fetch must stay off boot");
    assertEquals(
        Optional.empty(),
        ConfigProvider.getConfig().getOptionalValue("quarkus.oidc.connection-delay", String.class),
        "a connection delay is a wait for the idp taken before the HTTP listener opens");
  }

  /** How many times this process has asked the idp for its keys since the mock was cleared. */
  private static long jwksFetches() {
    return MockIdp.attach().recordedRequests().stream()
        .filter(request -> "/idp/jwks".equals(request.path()))
        .count();
  }
}
