package eu.wohlben.qits.projects.releasehost;

import com.sun.net.httpserver.HttpServer;
import eu.wohlben.qits.http.QitsHttpClients;
import eu.wohlben.qits.projects.control.CiRunLineage;
import eu.wohlben.qits.projects.control.UncoveredOperations;
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
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link HttpCiRunLineage} against a local server standing in for qits-ci — plain JUnit over a
 * directly-constructed bean, {@link HttpPipelinePhaseRerunsTest}'s shape. The rule under test is
 * that every non-answer is one answer, {@code Optional.empty()}, and none of them throws: the port
 * is called inside the ledger's write, on the bus consumption.
 */
class HttpCiRunLineageTest {

  private HttpServer server;
  private final List<String> paths = new CopyOnWriteArrayList<>();
  /** The Authorization each request carried. */
  private final List<String> roles = new CopyOnWriteArrayList<>();
  private final AtomicInteger status = new AtomicInteger(200);
  private final AtomicReference<String> body = new AtomicReference<>("{}");

  /** Every lineage a test built, shut down after it so the fork keeps no idle client threads. */
  private final List<HttpCiRunLineage> lineages = new CopyOnWriteArrayList<>();

  @AfterEach
  void closeClients() {
    lineages.forEach(HttpCiRunLineage::close);
  }

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
          roles.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
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
    return against(base, TestCiBearers.machine());
  }

  private HttpCiRunLineage against(String base, IdpCiBearer bearer) {
    HttpCiRunLineage lineage = new HttpCiRunLineage(QitsHttpClients.of(OpenTelemetry.noop(), null));
    lineage.ciUrl = Optional.ofNullable(base);
    lineage.bearer = bearer;
    lineages.add(lineage);
    return lineage;
  }

  @Test
  void readsTheParentOffTheRun() throws IOException {
    body.set("{\"id\":\"run-b\",\"status\":\"FAILED\",\"retryOfRunId\":\"run-a\",\"steps\":[]}");
    HttpCiRunLineage lineage = against(startServer());

    assertEquals(Optional.of("run-a"), lineage.retryOfRunId("run-b"));
    assertEquals(List.of("/ci/api/runs/run-b"), paths);
    assertEquals(List.of(TestCiBearers.TOKEN), roles, "the machine bearer and nothing else");
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

  /** No bearer fails closed (qits-1332): nothing is sent and the chain stops. */
  @Test
  void withNoBearerItAsksNobody() throws IOException {
    body.set("{\"id\":\"run-b\",\"retryOfRunId\":\"run-a\"}");

    assertEquals(
        Optional.empty(), against(startServer(), TestCiBearers.none()).retryOfRunId("run-b"));
    assertEquals(List.of(), paths, "nothing may be sent without the bearer");
  }

  // ---- qits-1920: a red run's stale outputs, off listRunReports --------------------------------

  @Test
  void readsTheStaleKindsOffTheStaleOutputsReport() throws IOException {
    body.set(
        "{\"runId\":\"run-b\",\"reports\":["
            + "{\"kind\":\"test-results\",\"highlights\":[{\"text\":\"stale outputs: nope\"}]},"
            + "{\"kind\":\"stale-outputs\",\"highlights\":["
            + "{\"severity\":\"bad\",\"text\":\"stale outputs: cli-tapes\"},"
            + "{\"severity\":\"info\",\"text\":\"regenerate with: qits release-request\"},"
            + "{\"severity\":\"bad\",\"text\":\"stale outputs: entity-diagram\"},"
            + "{\"severity\":\"bad\",\"text\":\"stale outputs: cli-tapes\"}]}]}");
    HttpCiRunLineage lineage = against(startServer());

    assertEquals(List.of("cli-tapes", "entity-diagram"), lineage.findings("run-b").staleKinds());
    assertEquals(List.of("/ci/api/runs/run-b/reports"), paths);
    assertEquals(List.of(TestCiBearers.TOKEN), roles, "the machine bearer and nothing else");
  }

  @Test
  void aRunWithNoStaleOutputsReportNamesNothing() throws IOException {
    body.set("{\"runId\":\"run-b\",\"reports\":[{\"kind\":\"coverage\",\"highlights\":[]}]}");
    assertEquals(CiRunLineage.RunFindings.NONE, against(startServer()).findings("run-b"));
  }

  @Test
  void everyFailedReportsReadNamesNothing() throws IOException {
    String base = startServer();
    status.set(404);
    assertEquals(CiRunLineage.RunFindings.NONE, against(base).findings("run-gone"));
    status.set(503);
    assertEquals(CiRunLineage.RunFindings.NONE, against(base).findings("run-b"));
    status.set(200);
    body.set("not json");
    assertEquals(CiRunLineage.RunFindings.NONE, against(base).findings("run-b"));
    body.set("{\"reports\":\"nope\"}");
    assertEquals(CiRunLineage.RunFindings.NONE, against(base).findings("run-b"));
    assertEquals(CiRunLineage.RunFindings.NONE, against(null).findings("run-b"));
    assertEquals(
        CiRunLineage.RunFindings.NONE, against(base, TestCiBearers.none()).findings("run-b"));
    server.stop(0);
    server = null;
    assertEquals(CiRunLineage.RunFindings.NONE, against(base).findings("run-b"));
  }

  /**
   * qits-1940: the golden-master coverage report rides the same response, and the same one request,
   * as the stale outputs; both findings come back from that read.
   */
  @Test
  void readsBothFindingsOffOneResponse() throws IOException {
    body.set(
        "{\"runId\":\"run-b\",\"reports\":["
            + "{\"kind\":\"stale-outputs\",\"highlights\":["
            + "{\"severity\":\"bad\",\"text\":\"stale outputs: cli-tapes\"}]},"
            + "{\"kind\":\"test-results\",\"highlights\":[{\"text\":\"uncovered operations: 7\"}]},"
            + "{\"kind\":\"golden-master-coverage\",\"highlights\":["
            + "{\"severity\":\"bad\",\"text\":\"uncovered operations: 2\"},"
            + "{\"severity\":\"bad\",\"text\":\"uncovered: getRun\"},"
            + "{\"severity\":\"bad\",\"text\":\"uncovered: POST /ci/api/runs/rerun\"}]}]}");
    HttpCiRunLineage lineage = against(startServer());

    assertEquals(
        new CiRunLineage.RunFindings(
            List.of("cli-tapes"),
            new UncoveredOperations(2, List.of("getRun", "POST /ci/api/runs/rerun"))),
        lineage.findings("run-b"));
    assertEquals(List.of("/ci/api/runs/run-b/reports"), paths, "one request for both");
  }
}
