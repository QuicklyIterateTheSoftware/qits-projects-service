package eu.wohlben.qits.projects.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.control.DeskRunners;
import eu.wohlben.qits.projects.dto.DeskRunnerDto;
import eu.wohlben.qits.projects.deskhost.DeskRunnerAddresses;
import eu.wohlben.qits.projects.deskhost.DeskRunnerAddressesFixture;
import eu.wohlben.qits.projects.deskhost.FakeDeskRunnerDesks;
import eu.wohlben.qits.projects.entity.DeskRunner;
import eu.wohlben.qits.projects.idphost.IdpRunnerCommissioner;
import eu.wohlben.qits.projects.idphost.IdpRunnerCommissionerFixture;
import eu.wohlben.qits.projects.idphost.RoutingIdpServer;
import eu.wohlben.qits.projects.persistence.DeskRunnerRepository;
import eu.wohlben.qits.projects.deskhost.DeskRunnerMockIdpTenant;
import eu.wohlben.qits.projects.security.MockIdpTenant;
import eu.wohlben.qits.projectsdeskrunner.protocol.DeskRunnerBinary;
import eu.wohlben.qits.servicemock.idp.MockIdp;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code /projects/api/runners} (qits-767): who may press which door, what create and rotate hand
 * out once, the register door's refusals and its answer, the install script, and the refusals that
 * name themselves.
 *
 * <p><b>Under the machine gate</b> ({@link MockIdpTenant}), because the register door reads the
 * {@code sub} off a validated bearer and with the gate off there is none. Bearers are RS256 JWTs the
 * mock idp signs — the shape of the edge's JWT for a registration token; a person's roles arrive as
 * the edge asserts them, in {@code X-Qits-Roles}.
 *
 * <p>qits-idp is a routing stub over real HTTP ({@link RoutingIdpServer}) and the public domain is
 * {@link DeskRunnerAddressesFixture#DOMAIN}, both installed per test with {@link QuarkusMock}.
 */
@QuarkusTest
@WithTestResource(DeskRunnerMockIdpTenant.class)
class DeskRunnerControllerTest {

  private static final String RUNNERS = "/projects/api/runners";

  private static final String DOMAIN = DeskRunnerAddressesFixture.DOMAIN;

  private static final String TOKEN = "qits_tok_registration-value";

  private static final String TOKENS = "/idp/api/tokens";

  private static final String CLIENTS = "/idp/api/clients";

  @Inject DeskRunnerRepository rows;

  @Inject FakeDeskRunnerDesks desks;

  private RoutingIdpServer idp;

  @BeforeEach
  void stubTheIdpAndTheDomain() throws Exception {
    QuarkusTransaction.requiringNew().run(() -> rows.deleteAll());
    desks.reset();
    idp = new RoutingIdpServer();
    idp.on("POST " + TOKENS, 201, token("t-1", TOKEN, "sub-1"))
        .on("POST " + TOKENS, 201, token("t-2", "qits_tok_second", "sub-2"));
    idp.on("POST " + CLIENTS, 201, "{\"clientId\":\"dr-1\",\"secret\":\"s3cr3t\"}");
    QuarkusMock.installMockForType(
        IdpRunnerCommissionerFixture.pointedAt(idp.url()), IdpRunnerCommissioner.class);
    QuarkusMock.installMockForType(
        DeskRunnerAddressesFixture.withDomain(DOMAIN), DeskRunnerAddresses.class);
  }

  @AfterEach
  void stopTheIdp() {
    QuarkusTransaction.requiringNew().run(() -> rows.deleteAll());
    idp.close();
  }

  private static String token(String id, String value, String subject) {
    return "{\"tokenId\":\"" + id + "\",\"token\":\"" + value + "\",\"subject\":\"" + subject + "\"}";
  }

  private static RequestSpecification as(String role) {
    return given().header("X-Qits-User", "someone").header("X-Qits-Roles", role);
  }

  private static RequestSpecification bearer(String sub, String role) {
    String jwt =
        MockIdp.attach().token().subject(sub).audience("qits-platform").groups(role).mint();
    return given().header("Authorization", "Bearer " + jwt);
  }

  private JsonPath create(RequestSpecification who, String name, int slots) {
    return who.contentType(ContentType.JSON)
        .body(Map.of("name", name, "slots", slots))
        .when()
        .post(RUNNERS)
        .then()
        .statusCode(201)
        .extract()
        .jsonPath();
  }

  private DeskRunner row(String id) {
    return QuarkusTransaction.requiringNew().call(() -> rows.findById(UUID.fromString(id)));
  }

  // --- create, rotate, patch, delete --------------------------------------------------------------

  @Test
  void theSystemRoleCreatesAndIsAnsweredTheInstallLineOnce() {
    JsonPath created = create(bearer("qits-maintenance", "qits:system"), "attic", 2);

    String id = created.getString("runner.id");
    assertEquals(TOKEN, created.getString("registrationToken"));
    String line = created.getString("installLine");
    assertTrue(
        line.startsWith(
            "curl -fsSL -H 'Authorization: Bearer "
                + TOKEN
                + "' https://projects.qits."
                + DOMAIN
                + "/projects/api/runners/install.sh | sudo env "),
        line);
    assertTrue(line.contains(id), line);
    assertEquals("attic", created.getString("runner.name"));
    assertEquals(2, created.getInt("runner.slots"));
    assertFalse(created.getBoolean("runner.registered"));
    assertEquals(DeskRunnerBinary.VERSION, created.getString("runner.pinnedVersion"));
    assertEquals(Boolean.FALSE, created.get("runner.connected"));

    // The token went to qits-idp as the registration kind, named by the runner.
    String asked = idp.received("POST", TOKENS).get(0).body();
    assertTrue(asked.contains("\"contextKind\":\"desk-runner-registration\""), asked);
    assertTrue(asked.contains("\"contextId\":\"" + id + "\""), asked);
    DeskRunner stored = row(id);
    assertEquals("t-1", stored.registrationTokenId);
    assertEquals("sub-1", stored.registrationTokenSubject);

    // Once: no read carries the token or the line again.
    String listed = as("qits:admin").when().get(RUNNERS).then().statusCode(200).extract().asString();
    assertFalse(listed.contains(TOKEN), listed);
    String one =
        as("qits:admin").when().get(RUNNERS + "/" + id).then().statusCode(200).extract().asString();
    assertFalse(one.contains(TOKEN), one);
    assertFalse(one.contains("installLine"), one);
  }

  @Test
  void theDefaultSlotsAreEightAndZeroIsRefused() {
    JsonPath created =
        as("qits:admin")
            .contentType(ContentType.JSON)
            .body(Map.of("name", "default"))
            .when()
            .post(RUNNERS)
            .then()
            .statusCode(201)
            .extract()
            .jsonPath();
    assertEquals(DeskRunners.DEFAULT_SLOTS, created.getInt("runner.slots"));

    as("qits:admin")
        .contentType(ContentType.JSON)
        .body(Map.of("name", "drained", "slots", 0))
        .when()
        .post(RUNNERS)
        .then()
        .statusCode(400);
    as("qits:admin")
        .contentType(ContentType.JSON)
        .body(Map.of("name", "Not A Name", "slots", 1))
        .when()
        .post(RUNNERS)
        .then()
        .statusCode(400);
  }

  @Test
  void aTakenNameIs409AndNothingIsMinted() {
    create(as("qits:admin"), "attic", 1);
    int minted = idp.received("POST", TOKENS).size();

    as("qits:admin-agent")
        .contentType(ContentType.JSON)
        .body(Map.of("name", "attic", "slots", 1))
        .when()
        .post(RUNNERS)
        .then()
        .statusCode(409);

    assertEquals(minted, idp.received("POST", TOKENS).size(), "refused before any token");
  }

  @Test
  void anAdminPatchesRotatesAndDeletes() {
    String id = create(as("qits:admin"), "attic", 1).getString("runner.id");
    create(as("qits:admin"), "cellar", 1);

    as("qits:admin")
        .contentType(ContentType.JSON)
        .body(Map.of("name", "loft", "description", "under the roof", "slots", 4))
        .when()
        .patch(RUNNERS + "/" + id)
        .then()
        .statusCode(200)
        .body("name", equalTo("loft"))
        .body("description", equalTo("under the roof"))
        .body("slots", is(4));
    as("qits:admin")
        .contentType(ContentType.JSON)
        .body(Map.of("name", "cellar"))
        .when()
        .patch(RUNNERS + "/" + id)
        .then()
        .statusCode(409);

    // A rotation answers the second token and line, and gives the first back.
    JsonPath rotated =
        as("qits:system")
            .when()
            .post(RUNNERS + "/" + id + "/registration-token")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
    assertEquals("qits_tok_second", rotated.getString("registrationToken"));
    assertTrue(rotated.getString("installLine").contains("qits_tok_second"));
    assertEquals("sub-2", row(id).registrationTokenSubject);
    assertEquals(1, idp.received("DELETE", TOKENS + "/t-1").size(), "the replaced token is gone");

    as("qits:admin").when().delete(RUNNERS + "/" + id).then().statusCode(204);
    assertNull(row(id));
    assertEquals(1, idp.received("DELETE", TOKENS + "/t-2").size(), "its token went back too");
    as("qits:admin").when().get(RUNNERS + "/" + id).then().statusCode(404);
  }

  @Test
  void anAgentReadsAndCannotWrite() {
    String id = create(as("qits:admin"), "attic", 1).getString("runner.id");

    as("qits:agent").when().get(RUNNERS).then().statusCode(200).body("size()", is(1));
    as("qits:agent").when().get(RUNNERS + "/" + id).then().statusCode(200);
    as("qits:agent").when().get(RUNNERS + "/" + id + "/health").then().statusCode(204);
    as("qits:agent").when().get(RUNNERS + "/install.sh").then().statusCode(200);

    as("qits:agent")
        .contentType(ContentType.JSON)
        .body(Map.of("name", "other", "slots", 1))
        .when()
        .post(RUNNERS)
        .then()
        .statusCode(403);
    as("qits:agent")
        .contentType(ContentType.JSON)
        .body(Map.of("slots", 3))
        .when()
        .patch(RUNNERS + "/" + id)
        .then()
        .statusCode(403);
    as("qits:agent").when().post(RUNNERS + "/" + id + "/registration-token").then().statusCode(403);
    as("qits:agent").when().delete(RUNNERS + "/" + id).then().statusCode(403);
    as("qits:agent").when().post(RUNNERS + "/" + id + "/greenlight").then().statusCode(403);
    as("qits:agent").when().post(RUNNERS + "/" + id + "/login-check").then().statusCode(403);
    given().when().get(RUNNERS).then().statusCode(401);
  }

  @Test
  void theSystemRoleCannotGreenlightAnAdminCan() {
    String id = create(as("qits:system"), "attic", 1).getString("runner.id");
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              DeskRunner runner = rows.findById(UUID.fromString(id));
              runner.quarantinedAt = java.time.Instant.now();
              runner.quarantineReason = "a reason";
            });

    as("qits:system").when().post(RUNNERS + "/" + id + "/greenlight").then().statusCode(403);
    as("qits:admin")
        .when()
        .post(RUNNERS + "/" + id + "/greenlight")
        .then()
        .statusCode(200)
        .body("quarantined", is(false))
        .body("quarantineReason", nullValue());
  }

  /**
   * A runner holding a desk is not deleted: 409 {@code RUNNER_OWNS_DESKS}, and nothing is given
   * back. The desks come through the {@code DeskRunnerDesks} port, placed here by {@link
   * FakeDeskRunnerDesks} as the front-desk task's implementation will place them.
   */
  @Test
  void aRunnerThatHoldsADeskIsNotDeleted() {
    String id = create(as("qits:admin"), "attic", 1).getString("runner.id");
    UUID runnerId = UUID.fromString(id);
    desks.hold(runnerId, List.of(new DeskRunnerDto.DeskRunnerDesk("project-1", "alpha", "RUNNING")));

    as("qits:agent")
        .when()
        .get(RUNNERS + "/" + id)
        .then()
        .statusCode(200)
        .body("desks[0].projectId", equalTo("project-1"))
        .body("desks[0].slug", equalTo("alpha"));
    as("qits:admin")
        .when()
        .delete(RUNNERS + "/" + id)
        .then()
        .statusCode(409)
        .body("error", equalTo(DeskRunners.RUNNER_OWNS_DESKS))
        .body("message", containsString("project-1"));

    assertNotNull(row(id), "the row stays");
    assertTrue(idp.received("DELETE", TOKENS + "/t-1").isEmpty(), "nothing was given back");
  }

  /** No socket yet: every door that needs a live runner says so, coded. */
  @Test
  void theChecksOfARunnerThatIsNotConnectedAre409() {
    String id = create(as("qits:admin"), "attic", 1).getString("runner.id");

    as("qits:agent")
        .when()
        .post(RUNNERS + "/" + id + "/healthcheck")
        .then()
        .statusCode(409)
        .body("error", equalTo("RUNNER_UNAVAILABLE"));
    as("qits:admin")
        .when()
        .post(RUNNERS + "/" + id + "/login-check")
        .then()
        .statusCode(409)
        .body("error", equalTo("RUNNER_UNAVAILABLE"));
    as("qits:admin")
        .when()
        .post(RUNNERS + "/" + UUID.randomUUID() + "/healthcheck")
        .then()
        .statusCode(404);
  }

  // --- the install script and the unconfigured plane ----------------------------------------------

  @Test
  void aRegistrationTokenReadsTheInstallScript() {
    String script =
        bearer("sub-anyone", "qits:desk-runner-registration")
            .when()
            .get(RUNNERS + "/install.sh")
            .then()
            .statusCode(200)
            .contentType(containsString("text/plain"))
            .extract()
            .asString();

    assertTrue(
        script.contains(
            "registry.qits." + DOMAIN + "/qits/qits-projects-desk-runner:" + DeskRunnerBinary.VERSION),
        "the pinned image on this deployment's registry");
    assertFalse(script.contains("qits_tok_"));
  }

  @Test
  void withNoPublicDomainCreateAndTheScriptAre503AndNothingIsMinted() {
    QuarkusMock.installMockForType(
        DeskRunnerAddressesFixture.withDomain("localhost"), DeskRunnerAddresses.class);

    as("qits:admin")
        .contentType(ContentType.JSON)
        .body(Map.of("name", "attic", "slots", 1))
        .when()
        .post(RUNNERS)
        .then()
        .statusCode(503)
        .body("error", equalTo(DeskRunnerAddresses.RUNNER_PLANE_UNCONFIGURED));
    as("qits:admin")
        .when()
        .get(RUNNERS + "/install.sh")
        .then()
        .statusCode(503)
        .body("error", equalTo(DeskRunnerAddresses.RUNNER_PLANE_UNCONFIGURED));

    assertTrue(idp.received().isEmpty(), "nothing was minted");
  }

  // --- the register door --------------------------------------------------------------------------

  @Test
  void theRightRegistrationTokenRegistersOnceAndIsAnsweredItsClient() {
    String id = create(as("qits:admin"), "attic", 1).getString("runner.id");

    JsonPath registered =
        bearer("sub-1", "qits:desk-runner-registration")
            .contentType(ContentType.JSON)
            .body(Map.of("capabilities", Map.of("version", "1.2.3")))
            .when()
            .post(RUNNERS + "/" + id + "/register")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();

    assertEquals("dr-1", registered.getString("clientId"));
    assertEquals("s3cr3t", registered.getString("secret"));
    assertEquals("https://idp.qits." + DOMAIN + "/idp/token", registered.getString("tokenUrl"));
    assertEquals("qits-platform", registered.getString("audience"));
    assertEquals(
        "wss://projects.qits." + DOMAIN + "/projects/runners/socket",
        registered.getString("socketUrl"));

    String asked = idp.received("POST", CLIENTS).get(0).body();
    assertEquals("{\"contextKind\":\"desk-runner\",\"contextId\":\"" + id + "\"}", asked);
    DeskRunner stored = row(id);
    assertEquals("dr-1", stored.clientId);
    assertNull(stored.registrationTokenId, "the spent token is off the row");
    assertNotNull(stored.registeredAt);
    assertEquals(DeskRunners.AWAITING_FIRST_HEALTH_CHECK, stored.quarantineReason);
    assertEquals(1, idp.received("DELETE", TOKENS + "/t-1").size(), "the spent token is deleted");

    as("qits:agent")
        .when()
        .get(RUNNERS + "/" + id)
        .then()
        .statusCode(200)
        .body("registered", is(true))
        .body("version", equalTo("1.2.3"))
        .body("quarantined", is(true))
        .body("registeredAt", notNullValue());

    // A replay is 409, and the secret is never answered twice.
    bearer("sub-1", "qits:desk-runner-registration")
        .contentType(ContentType.JSON)
        .body(Map.of())
        .when()
        .post(RUNNERS + "/" + id + "/register")
        .then()
        .statusCode(409);
    // A registered runner has spent its registration: no rotation either.
    as("qits:admin").when().post(RUNNERS + "/" + id + "/registration-token").then().statusCode(409);
  }

  @Test
  void aRegistrationTokenOfAnotherRunnerIs403AndAnUnknownRunner404() {
    String id = create(as("qits:admin"), "attic", 1).getString("runner.id");

    bearer("sub-someone-else", "qits:desk-runner-registration")
        .contentType(ContentType.JSON)
        .body(Map.of())
        .when()
        .post(RUNNERS + "/" + id + "/register")
        .then()
        .statusCode(403);
    bearer("sub-1", "qits:desk-runner-registration")
        .contentType(ContentType.JSON)
        .body(Map.of())
        .when()
        .post(RUNNERS + "/" + UUID.randomUUID() + "/register")
        .then()
        .statusCode(404);

    assertTrue(idp.received("POST", CLIENTS).isEmpty(), "no client was minted");
    assertNull(row(id).clientId);
  }

  @Test
  void aRegistrationTokenOpensOnlyTheRegisterDoorAndTheScript() {
    String id = create(as("qits:admin"), "attic", 1).getString("runner.id");

    bearer("sub-1", "qits:desk-runner-registration").when().get(RUNNERS).then().statusCode(403);
    bearer("sub-1", "qits:desk-runner-registration")
        .when()
        .get(RUNNERS + "/" + id)
        .then()
        .statusCode(403);
    bearer("sub-1", "qits:desk-runner-registration")
        .when()
        .delete(RUNNERS + "/" + id)
        .then()
        .statusCode(403);
    // And nobody else registers, an admin included: the door is the token's alone.
    as("qits:admin")
        .contentType(ContentType.JSON)
        .body(Map.of())
        .when()
        .post(RUNNERS + "/" + id + "/register")
        .then()
        .statusCode(403);
  }

  @Test
  void capabilitiesThatAreNotAnObjectAre400() {
    String id = create(as("qits:admin"), "attic", 1).getString("runner.id");

    bearer("sub-1", "qits:desk-runner-registration")
        .contentType(ContentType.JSON)
        .body("{\"capabilities\":[1,2]}")
        .when()
        .post(RUNNERS + "/" + id + "/register")
        .then()
        .statusCode(400);
  }
}
