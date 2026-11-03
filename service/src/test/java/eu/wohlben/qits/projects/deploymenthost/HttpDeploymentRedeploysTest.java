package eu.wohlben.qits.projects.deploymenthost;

import com.sun.net.httpserver.HttpServer;
import eu.wohlben.qits.http.QitsHttpClients;
import eu.wohlben.qits.projects.control.DeploymentRedeploys.Redeploy;
import eu.wohlben.qits.projects.error.DomainException;
import io.opentelemetry.api.OpenTelemetry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HttpDeploymentRedeploys}' credential against a local server standing in for
 * qits-deployments (qits-1332): the machine bearer and no forwarded pair, and with no bearer nothing
 * is sent and the person who pressed the button gets a 503.
 */
class HttpDeploymentRedeploysTest {

  private record Received(String path, String auth, String user, String roles) {}

  private static final Redeploy ASK = new Redeploy("repo-1", "p-1", "thing", "thing", "2026.1103.1");

  private HttpServer server;
  private final List<Received> received = new CopyOnWriteArrayList<>();
  private final List<HttpDeploymentRedeploys> ports = new CopyOnWriteArrayList<>();

  @AfterEach
  void stop() {
    ports.forEach(HttpDeploymentRedeploys::close);
    if (server != null) {
      server.stop(0);
    }
  }

  private String startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          received.add(
              new Received(
                  exchange.getRequestURI().getPath(),
                  exchange.getRequestHeaders().getFirst("Authorization"),
                  exchange.getRequestHeaders().getFirst("X-Qits-User"),
                  exchange.getRequestHeaders().getFirst("X-Qits-Roles")));
          exchange.sendResponseHeaders(202, -1);
          exchange.getResponseBody().close();
        });
    server.start();
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  private HttpDeploymentRedeploys against(String base, IdpDeploymentsBearer bearer) {
    HttpDeploymentRedeploys port =
        new HttpDeploymentRedeploys(QitsHttpClients.of(OpenTelemetry.noop(), null));
    port.deploymentsUrl = Optional.ofNullable(base);
    port.bearer = bearer;
    ports.add(port);
    return port;
  }

  @Test
  void theBearerIsTheCredentialAndNoPairTravelsBesideIt() throws Exception {
    against(startServer(), TestDeploymentsBearers.machine()).deployAgain(ASK);

    Received asked = received.get(0);
    assertEquals("/deployments/api/events/software-released", asked.path());
    assertEquals(TestDeploymentsBearers.TOKEN, asked.auth());
    assertNull(asked.user());
    assertNull(asked.roles());
  }

  @Test
  void noBearerIs503AndAsksNobody() throws Exception {
    String base = startServer();

    DomainException refused =
        assertThrows(
            DomainException.class,
            () -> against(base, TestDeploymentsBearers.none()).deployAgain(ASK));

    assertEquals(503, refused.statusCode());
    assertTrue(received.isEmpty(), "nothing may be sent without the bearer");
  }
}
