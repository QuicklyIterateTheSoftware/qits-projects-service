package eu.wohlben.qits.projects.releasehost;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.sun.net.httpserver.HttpServer;
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
 * {@link HttpCiRunLineage} against a local server standing in for qits-ci — plain JUnit over a
 * directly-constructed bean, {@link HttpPipelinePhaseRerunsTest}'s shape. The rule under test is
 * that every non-answer is one answer, {@code Optional.empty()}, and none of them throws: the port
 * is called inside the ledger's write, on the bus consumption.
 */
class HttpCiRunLineageTest {

  private HttpServer server;
  private final List<String> paths = new CopyOnWriteArrayList<>();
  private final List<String> roles = new CopyOnWriteArrayList<>();
  private final AtomicInteger status = new AtomicInteger(200);
  private final AtomicReference<String> body = new AtomicReference<>("{}");

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  private String startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          paths.add(exchange.getRequestURI().getPath());
          roles.add(String.valueOf(exchange.getRequestHeaders().getFirst("X-Qits-Roles")));
          byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(status.get(), bytes.length == 0 ? -1 : bytes.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
          }
        });
    server.start();
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  private HttpCiRunLineage against(String base) {
    HttpCiRunLineage lineage = new HttpCiRunLineage();
    lineage.ciUrl = Optional.ofNullable(base);
    lineage.bearer = new IdpCiBearer();
    return lineage;
  }

  @Test
  void readsTheParentOffTheRun() throws IOException {
    body.set("{\"id\":\"run-b\",\"status\":\"FAILED\",\"retryOfRunId\":\"run-a\",\"steps\":[]}");
    HttpCiRunLineage lineage = against(startServer());

    assertEquals(Optional.of("run-a"), lineage.retryOfRunId("run-b"));
    assertEquals(List.of("/ci/api/runs/run-b"), paths);
    assertEquals(List.of("qits:system"), roles, "the forwarded pair while the named client is off");
  }

  @Test
  void aRunThatIsNoRetryHasNoParent() throws IOException {
    body.set("{\"id\":\"run-a\",\"retryOfRunId\":null}");
    assertEquals(Optional.empty(), against(startServer()).retryOfRunId("run-a"));
  }

  @Test
  void aRunQitsCiDoesNotKnowIsEmpty() throws IOException {
    status.set(404);
    body.set("{\"message\":\"no such run\"}");
    assertEquals(Optional.empty(), against(startServer()).retryOfRunId("run-gone"));
  }

  @Test
  void aRefusalOrAnUnreadableBodyIsEmpty() throws IOException {
    String base = startServer();
    status.set(503);
    assertEquals(Optional.empty(), against(base).retryOfRunId("run-b"));
    status.set(200);
    body.set("not json");
    assertEquals(Optional.empty(), against(base).retryOfRunId("run-b"));
  }

  @Test
  void anUnsetOrUnreachableQitsCiIsEmpty() throws IOException {
    assertEquals(Optional.empty(), against(null).retryOfRunId("run-b"));
    String base = startServer();
    server.stop(0);
    server = null;
    assertEquals(Optional.empty(), against(base).retryOfRunId("run-b"));
  }
}
