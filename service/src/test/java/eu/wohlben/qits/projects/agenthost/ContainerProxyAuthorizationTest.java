package eu.wohlben.qits.projects.agenthost;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.entities.api.PersonGateIdpTenant;
import eu.wohlben.qits.servicemock.idp.MockIdp;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.UpgradeRejectedException;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketClient;
import io.vertx.core.http.WebSocketConnectOptions;
import java.net.URI;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Who may reach a project's daemon through {@code /projects/container/{projectId}/*} (qits-1575).
 *
 * <p>Under the tenant-on posture of {@link PersonGateIdpTenant}, because it is the one application
 * here with no dev user: a header-less request is anonymous, and every other caller is a real bearer
 * minted by {@link MockIdp}, whose roles and {@code project} claim come out of a validated token
 * rather than a test identity. The daemon is a stub HTTP server behind a stubbed {@link
 * AgentTunnels} that counts what reached it, so every refusal is also an assertion that nothing was
 * forwarded — the tunnel itself is {@link AgentTunnelProxyTest}'s subject, not this one's.
 */
@QuarkusTest
@WithTestResource(PersonGateIdpTenant.class)
class ContainerProxyAuthorizationTest {

  private static final long TIMEOUT_SECONDS = 10;

  @TestHTTPResource("/")
  URI base;

  private Vertx daemonVertx;
  private HttpClient daemonClient;
  private int daemonPort;
  private final AtomicInteger daemonHits = new AtomicInteger();

  private String project;
  private String other;

  /** A stub daemon on loopback, reached for any project through a stubbed tunnel lookup. */
  @BeforeEach
  void aStubDaemon() throws Exception {
    daemonVertx = Vertx.vertx();
    HttpServer server =
        daemonVertx
            .createHttpServer()
            .webSocketHandler(ws -> daemonHits.incrementAndGet())
            .requestHandler(
                req -> {
                  daemonHits.incrementAndGet();
                  req.response().end("daemon:" + req.path());
                })
            .listen(0, "127.0.0.1")
            .toCompletionStage()
            .toCompletableFuture()
            .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    daemonPort = server.actualPort();
    daemonClient = daemonVertx.createHttpClient();
    QuarkusMock.installMockForType(new StubTunnels(), AgentTunnels.class);
    daemonHits.set(0);

    String suffix = UUID.randomUUID().toString().substring(0, 8);
    project = "proxy-own-" + suffix;
    other = "proxy-other-" + suffix;
  }

  /**
   * Stops the stub. A close that outlasts the timeout is not a failure: the upgrade test leaves a
   * WebSocket open through the proxy back into this very Vert.x, and its close handshake can hang
   * the stub's shutdown — which is not what any test here asserts (qits-1559 saw it fail two release
   * folds of qits-projects-service-rr-395 in a row).
   */
  @AfterEach
  void stopTheStubDaemon() throws Exception {
    if (daemonVertx != null) {
      try {
        daemonVertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
      } catch (java.util.concurrent.TimeoutException slow) {
        // Abandoned, not awaited: the next test stands up a Vert.x of its own.
      } finally {
        daemonVertx = null;
      }
    }
  }

  /** Every project has a live daemon, and it is this stub. */
  private class StubTunnels extends AgentTunnels {
    @Override
    public Optional<TunnelOrigin> originFor(String projectId) {
      return Optional.of(new TunnelOrigin(daemonClient, daemonPort));
    }
  }

  private static String bearer(String projectClaim, String... roles) {
    var token =
        MockIdp.attach()
            .token()
            .subject("caller-" + UUID.randomUUID().toString().substring(0, 8))
            .audience("qits-platform")
            .groups(roles);
    if (projectClaim != null) {
      token = token.claim("project", projectClaim);
    }
    return token.mint();
  }

  private static RequestSpecification as(String token) {
    return given().header("Authorization", "Bearer " + token);
  }

  private String path(String projectId) {
    return ContainerProxyPath.base(projectId) + "front-desk/last-answer";
  }

  private void forwarded(String token, String projectId) {
    as(token)
        .when()
        .get(path(projectId))
        .then()
        .statusCode(200)
        .body(equalTo("daemon:" + path(projectId)));
    assertEquals(1, daemonHits.get(), "an admitted request reaches the daemon, path verbatim");
  }

  private void refused(String token, String projectId) {
    as(token).when().get(path(projectId)).then().statusCode(403).body("message", notNullValue());
    assertEquals(0, daemonHits.get(), "a refusal forwards nothing");
  }

  // --- refused ----------------------------------------------------------------------------------

  @Test
  void anAnonymousCallerIsA401() {
    given().when().get(path(project)).then().statusCode(401);
    assertEquals(0, daemonHits.get(), "a refusal forwards nothing");
  }

  /** The owner's ruling on qits-1575: an agent does not reach the desk's daemon. */
  @Test
  void anAgentIsRefusedEvenInItsOwnProject() {
    refused(bearer(project, "qits:agent"), project);
  }

  @Test
  void aPlainDeskIsRefused() {
    refused(bearer(project, "qits:agent", "qits:desk"), project);
  }

  @Test
  void aCiRunIsRefused() {
    refused(bearer(project, "qits:ci-run"), project);
  }

  @Test
  void aTokenWithNoRoleIsRefused() {
    refused(bearer(project), project);
  }

  @Test
  void theDeskAdminIsRefusedAnotherProjectsDaemon() {
    refused(bearer(project, "qits:agent", "qits:desk", "qits:desk-admin"), other);
  }

  @Test
  void anAgentsUpgradeIsRefusedAndNeverSwitchesProtocols() throws Exception {
    WebSocketClient client = daemonVertx.createWebSocketClient();
    WebSocketConnectOptions options =
        new WebSocketConnectOptions()
            .setHost(base.getHost())
            .setPort(base.getPort())
            .setURI(ContainerProxyPath.base(project) + "terminal/commands/c-1")
            .addHeader("Authorization", "Bearer " + bearer(project, "qits:agent"));

    ExecutionException failure =
        assertThrows(
            ExecutionException.class,
            () ->
                client
                    .connect(options)
                    .toCompletionStage()
                    .toCompletableFuture()
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    assertTrue(
        failure.getCause() instanceof UpgradeRejectedException,
        "expected a rejected upgrade, got " + failure.getCause());
    assertEquals(403, ((UpgradeRejectedException) failure.getCause()).getStatus());
    assertEquals(0, daemonHits.get(), "a refused upgrade forwards nothing");
  }

  // --- admitted ---------------------------------------------------------------------------------

  @Test
  void theDeskAdminReachesItsOwnProjectsDaemon() {
    forwarded(bearer(project, "qits:agent", "qits:desk", "qits:desk-admin"), project);
  }

  @Test
  void anAdminReachesAnyProject() {
    forwarded(bearer(null, "qits:admin"), project);
  }

  @Test
  void theAdminAgentReachesAnyProject() {
    forwarded(bearer(null, "qits:admin-agent"), project);
  }

  @Test
  void aPlatformServiceReachesAnyProject() {
    forwarded(bearer(null, "qits:system"), project);
  }

  @Test
  void anAdminsUpgradeIsForwarded() throws Exception {
    WebSocketClient client = daemonVertx.createWebSocketClient();
    WebSocket socket =
        client
            .connect(
                new WebSocketConnectOptions()
                    .setHost(base.getHost())
                    .setPort(base.getPort())
                    .setURI(ContainerProxyPath.base(project) + "terminal/commands/c-1")
                    .addHeader("Authorization", "Bearer " + bearer(null, "qits:admin")))
            .toCompletionStage()
            .toCompletableFuture()
            .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    try {
      assertEquals(1, daemonHits.get(), "the upgrade reached the daemon");
    } finally {
      socket.close();
    }
  }
}
