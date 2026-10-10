package eu.wohlben.qits.projects.contracts.consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.projects.agenthost.ContainerProxyPath;
import eu.wohlben.qits.projects.api.AgentCapabilityController.CapabilityReportRequest;
import eu.wohlben.qits.projects.contracts.consumer.ConsumerRow.Trigger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

/**
 * <b>What qits-projects asks the project agent's qits-projects-daemon</b> (ticket qits-1149): its
 * harness capability report, read by {@code AgentCapabilityRelay} through the reverse tunnel once
 * the daemon has said hello. Every other request to the daemon is a browser's, passed through
 * verbatim by {@code ContainerProxyRoute}, and has no row here.
 *
 * <p>The relay speaks Vert.x over a loopback tunnel, which a test cannot stand up without a daemon;
 * the row's call makes the same GET with the JDK client and decodes the body exactly as {@code
 * AgentCapabilityRelay.ingest} does. qits-projects-daemon publishes no golden masters yet.
 */
final class ProjectsDaemonContract {

  static final String PROVIDER = "qits-projects-daemon";
  static final String APP = "qits-projects-daemon";

  private ProjectsDaemonContract() {}

  static final List<ConsumerRow> ROWS =
      List.of(
          new ConsumerRow(
              PROVIDER,
              APP,
              "listAvailableAgents",
              "a daemon whose harnesses were probed",
              "GET",
              ContainerProxyPath.PREFIX + "{projectId}/agents/available",
              Map.of(),
              null,
              List.of("$.reportedBy", "$.imageVersion", "$.capabilities"),
              200,
              Trigger.schedule("AgentCapabilityRelay.relay (on the daemon's Hello)"),
              (base, p) -> {
                HttpResponse<String> response =
                    HttpClient.newHttpClient()
                        .send(
                            HttpRequest.newBuilder(
                                    URI.create(
                                        base
                                            + ContainerProxyPath.base(p.get("projectId"))
                                            + "agents/available"))
                                .header("Authorization", "Bearer daemon-api-token")
                                .GET()
                                .build(),
                            HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode());
                CapabilityReportRequest report =
                    new ObjectMapper().readValue(response.body(), CapabilityReportRequest.class);
                assertFalse(report.capabilities().isEmpty());
              },
              "GET {base}/agents/available answering 200 with reportedBy, imageVersion and"
                  + " capabilities[]"));
}
