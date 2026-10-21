package eu.wohlben.qits.projects.maintenancehost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import eu.wohlben.qits.http.QitsHttpClients;
import eu.wohlben.qits.projects.control.AutomationKinds.Kind;
import io.opentelemetry.api.OpenTelemetry;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link HttpAutomationKinds} against a local stand-in for qits-maintenance (qits-1046): the path,
 * the credential, the catalogue read, and every failure — including a kind whose role list is
 * missing, which must not read as "needs no approval" — as "could not ask", never thrown.
 */
class HttpAutomationKindsTest {

  private record Received(String path, String auth, String roles) {}

  private HttpServer server;
  private final List<Received> received = new CopyOnWriteArrayList<>();
  private final AtomicInteger status = new AtomicInteger(200);
  private final AtomicReference<String> body = new AtomicReference<>("{}");
  private final List<HttpAutomationKinds> ports = new CopyOnWriteArrayList<>();

  @AfterEach
  void stop() {
    ports.forEach(HttpAutomationKinds::close);
    if (server != null) {
      server.stop(0);
    }
  }

  private String start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          received.add(
              new Received(
                  exchange.getRequestURI().getRawPath(),
                  exchange.getRequestHeaders().getFirst("Authorization"),
                  exchange.getRequestHeaders().getFirst("X-Qits-Roles")));
          byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status.get(), bytes.length == 0 ? -1 : bytes.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
          }
        });
    server.start();
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  private HttpAutomationKinds against(String base, Optional<String> authorization) {
    HttpAutomationKinds port = new HttpAutomationKinds(QitsHttpClients.of(OpenTelemetry.noop(), null));
    port.maintenanceUrl = Optional.ofNullable(base);
    port.bearer = () -> authorization;
    ports.add(port);
    return port;
  }

  @Test
  void theCatalogueIsReadWithEachKindsPathsAndRoles() throws Exception {
    String base = start();
    body.set(
        """
        {"kinds":[
          {"kind":"dependency-bump","label":"Dependency bump","committablePaths":[":(glob)**/pom.xml"],"approvalRoles":[]},
          {"kind":"screenshot-baselines","label":"Screenshot baselines",
           "committablePaths":[":(glob)**/__screenshots__/**",":(glob)**/testing/browser/renderer.txt"],
           "approvalRoles":["agent","person"]}]}
        """);

    Optional<List<Kind>> answer = against(base, Optional.of("Bearer machine-token")).kinds();

    assertEquals("/maintenance/api/automations/kinds", received.get(0).path());
    assertEquals("Bearer machine-token", received.get(0).auth());
    assertEquals(
        Optional.of(
            List.of(
                new Kind("dependency-bump", "Dependency bump", List.of(":(glob)**/pom.xml"), List.of()),
                new Kind(
                    "screenshot-baselines",
                    "Screenshot baselines",
                    List.of(":(glob)**/__screenshots__/**", ":(glob)**/testing/browser/renderer.txt"),
                    List.of("agent", "person")))),
        answer);
  }

  @Test
  void withoutTheNamedClientItForwardsTheSystemPair() throws Exception {
    String base = start();
    body.set("{\"kinds\":[]}");

    assertEquals(Optional.of(List.of()), against(base, Optional.empty()).kinds());
    assertNull(received.get(0).auth());
    assertEquals("qits:system", received.get(0).roles());
  }

  @Test
  void configuredIsAnAddressThatIsNotBlank() {
    assertTrue(against("http://127.0.0.1:1", Optional.empty()).configured());
    assertFalse(against(null, Optional.empty()).configured());
    assertFalse(against(" ", Optional.empty()).configured());
    assertEquals(Optional.empty(), against(null, Optional.empty()).kinds());
  }

  @Test
  void everyFailureIsCouldNotAsk() throws Exception {
    String base = start();
    status.set(503);
    assertEquals(Optional.empty(), against(base, Optional.empty()).kinds());

    status.set(200);
    body.set("not json");
    assertEquals(Optional.empty(), against(base, Optional.empty()).kinds());
    body.set("{\"kinds\":{}}");
    assertEquals(Optional.empty(), against(base, Optional.empty()).kinds());
    body.set("{\"kinds\":[{\"label\":\"no id\",\"committablePaths\":[],\"approvalRoles\":[]}]}");
    assertEquals(Optional.empty(), against(base, Optional.empty()).kinds());
    body.set("{\"kinds\":[{\"kind\":\"entity-diagram\",\"committablePaths\":[\"docs\"]}]}");
    assertEquals(
        Optional.empty(),
        against(base, Optional.empty()).kinds(),
        "a missing role list is not an empty one");

    assertEquals(Optional.empty(), against("http://127.0.0.1:1", Optional.empty()).kinds());
  }
}
