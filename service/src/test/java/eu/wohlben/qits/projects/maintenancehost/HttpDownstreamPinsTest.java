package eu.wohlben.qits.projects.maintenancehost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.sun.net.httpserver.HttpServer;
import eu.wohlben.qits.http.QitsHttpClients;
import eu.wohlben.qits.projects.control.DownstreamPins.Downstream;
import eu.wohlben.qits.projects.control.DownstreamPins.Pin;
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
 * {@link HttpDownstreamPins} against a local stand-in for qits-maintenance (qits-1291): the two
 * paths and the credential, a 404 for a repository it has no row for read as "pins nothing", and
 * every other failure as "could not ask", never thrown.
 */
class HttpDownstreamPinsTest {

  private record Received(String path, String auth, String roles) {}

  private HttpServer server;
  private final List<Received> received = new CopyOnWriteArrayList<>();
  private final AtomicInteger status = new AtomicInteger(200);
  private final AtomicReference<String> body = new AtomicReference<>("{}");
  private final List<HttpDownstreamPins> ports = new CopyOnWriteArrayList<>();

  @AfterEach
  void stop() {
    ports.forEach(HttpDownstreamPins::close);
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

  private HttpDownstreamPins against(String base, Optional<String> authorization) {
    HttpDownstreamPins port = new HttpDownstreamPins(QitsHttpClients.of(OpenTelemetry.noop(), null));
    port.maintenanceUrl = Optional.ofNullable(base);
    port.bearer = () -> authorization;
    ports.add(port);
    return port;
  }

  @Test
  void theClosureCarriesEachEntrysCatalogIdAndDepth() throws Exception {
    String base = start();
    body.set(
        """
        {"repository":"qits-thing-service","catalogId":"repo-1","downstream":[
          {"repository":"telemetry-service","catalogId":"repo-2","archetype":"SERVICE","depth":1,"via":[]},
          {"repository":"telemetry-app","catalogId":null,"archetype":"APP","depth":2,"via":[]}]}
        """);

    Optional<List<Downstream>> answer =
        against(base, Optional.of("Bearer machine-token")).downstreamOf("repo-1");

    assertEquals("/maintenance/api/repositories/repo-1/downstream", received.get(0).path());
    assertEquals("Bearer machine-token", received.get(0).auth());
    assertEquals(
        Optional.of(
            List.of(
                new Downstream("telemetry-service", "repo-2", 1),
                new Downstream("telemetry-app", null, 2))),
        answer);
  }

  @Test
  void aConsumersPinsAreReadByItsName() throws Exception {
    String base = start();
    body.set(
        """
        {"name":"telemetry-service","pins":[
          {"ecosystem":"maven","name":"eu.wohlben.qits:qits-thing-golden-masters","version":"1"},
          {"ecosystem":"npm","name":"@qits/thing-golden-masters","version":"1"}]}
        """);

    Optional<List<Pin>> answer = against(base, Optional.empty()).pinsOf("telemetry-service");

    assertEquals("/maintenance/api/repositories/telemetry-service", received.get(0).path());
    assertNull(received.get(0).auth());
    assertEquals("qits:system", received.get(0).roles());
    assertEquals(
        Optional.of(
            List.of(
                new Pin("maven", "eu.wohlben.qits:qits-thing-golden-masters"),
                new Pin("npm", "@qits/thing-golden-masters"))),
        answer);
  }

  @Test
  void aRepositoryQitsMaintenanceDoesNotKnowPinsNothing() throws Exception {
    String base = start();
    status.set(404);
    body.set("{\"message\":\"no such repository\"}");

    assertEquals(Optional.of(List.of()), against(base, Optional.empty()).pinsOf("unscanned"));
  }

  @Test
  void everyOtherFailureIsCouldNotAsk() throws Exception {
    String base = start();
    status.set(503);
    assertEquals(Optional.empty(), against(base, Optional.empty()).downstreamOf("repo-1"));
    assertEquals(Optional.empty(), against(base, Optional.empty()).pinsOf("telemetry-service"));

    status.set(200);
    body.set("not json");
    assertEquals(Optional.empty(), against(base, Optional.empty()).downstreamOf("repo-1"));
    body.set("{\"downstream\":{}}");
    assertEquals(Optional.empty(), against(base, Optional.empty()).downstreamOf("repo-1"));

    assertEquals(Optional.empty(), against(null, Optional.empty()).downstreamOf("repo-1"));
    assertEquals(
        Optional.empty(), against("http://127.0.0.1:1", Optional.empty()).pinsOf("telemetry-service"));
  }
}
