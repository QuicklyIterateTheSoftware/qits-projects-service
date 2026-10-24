package eu.wohlben.qits.projects.contracts.consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.projects.agenthost.ContainerProxyPath;
import eu.wohlben.qits.projects.api.AgentCapabilityController.CapabilityReportRequest;
import eu.wohlben.qits.projects.api.ProjectFrontDeskController.LastAnswerResponse;
import eu.wohlben.qits.projects.api.ProjectFrontDeskController.PromptResponse;
import eu.wohlben.qits.projects.contracts.consumer.ConsumerRow.Trigger;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

/**
 * <b>What qits-projects asks the project agent's qits-projects-daemon</b> (ticket qits-1149): its
 * harness capability report, read by {@code AgentCapabilityRelay} through the reverse tunnel once
 * the daemon has said hello; and, since qits-1572, the front desk's prompt and last answer, which
 * {@code ProjectFrontDeskController} proxies to it down the same tunnel. Every other request to the
 * daemon is a browser's, passed through verbatim by {@code ContainerProxyRoute}, and has no row here.
 *
 * <p>The two front-desk rows are pending until qits-projects-daemon records the state {@value
 * #FRONT_DESK_RUNNING} for {@code promptFrontDesk} and {@code getFrontDeskLastAnswer}. Their bodies
 * pass through this service verbatim, so what they consume is what this service's own OpenAPI
 * document promises its callers in turn — the non-null members of its typed records.
 *
 * <p>The relay speaks Vert.x over a loopback tunnel, which a test cannot stand up without a daemon;
 * the row's call makes the same GET with the JDK client and decodes the body exactly as {@code
 * AgentCapabilityRelay.ingest} does. The relay presents the daemon's own api token as a bearer,
 * so the row does too.
 */
final class ProjectsDaemonContract {

  static final String PROVIDER = "qits-projects-daemon";
  static final String APP = "qits-projects-daemon";

  /** The daemon's state for the front desk's two routes (qits-1572), pending its recording. */
  static final String FRONT_DESK_RUNNING = "a daemon whose front desk is running";

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
                    RowClients.HTTP
                        .send(
                            HttpRequest.newBuilder(
                                    URI.create(
                                        base
                                            + ContainerProxyPath.base(p.get("projectId"))
                                            + "agents/available"))
                                .header("Authorization", "Bearer " + p.get("apiToken"))
                                .GET()
                                .build(),
                            HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode());
                CapabilityReportRequest report =
                    new ObjectMapper().readValue(response.body(), CapabilityReportRequest.class);
                assertFalse(report.capabilities().isEmpty());
              },
              "GET {base}/agents/available answering 200 with reportedBy, imageVersion and"
                  + " capabilities[]")
              .withHeaders(Map.of("Authorization", "Bearer {apiToken}")),
          new ConsumerRow(
              PROVIDER,
              APP,
              "promptFrontDesk",
              FRONT_DESK_RUNNING,
              "POST",
              ContainerProxyPath.PREFIX + "{projectId}/front-desk/prompt",
              Map.of(),
              ConsumerRow.json("{\"text\":\"What is ready for dev?\"}"),
              List.of("$.commandId", "$.state", "$.delivered", "$.queued"),
              200,
              Trigger.operation("promptFrontDesk"),
              (base, p) -> {
                HttpResponse<String> response =
                    RowClients.HTTP
                        .send(
                            HttpRequest.newBuilder(
                                    URI.create(
                                        base
                                            + ContainerProxyPath.base(p.get("projectId"))
                                            + "front-desk/prompt"))
                                .header("Authorization", "Bearer " + p.get("apiToken"))
                                .header("Content-Type", "application/json")
                                .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                        "{\"text\":\"What is ready for dev?\"}"))
                                .build(),
                            HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode());
                PromptResponse prompted =
                    new ObjectMapper().readValue(response.body(), PromptResponse.class);
                assertNotNull(prompted.commandId());
              },
              "POST {base}/front-desk/prompt {text} answering 200 with commandId, state, delivered,"
                  + " queued and position")
              .withHeaders(Map.of("Authorization", "Bearer {apiToken}")),
          new ConsumerRow(
              PROVIDER,
              APP,
              "getFrontDeskLastAnswer",
              FRONT_DESK_RUNNING,
              "GET",
              ContainerProxyPath.PREFIX + "{projectId}/front-desk/last-answer",
              Map.of(),
              null,
              List.of("$.commandId", "$.sessionId", "$.state"),
              200,
              Trigger.operation("getFrontDeskLastAnswer"),
              (base, p) -> {
                HttpResponse<String> response =
                    RowClients.HTTP
                        .send(
                            HttpRequest.newBuilder(
                                    URI.create(
                                        base
                                            + ContainerProxyPath.base(p.get("projectId"))
                                            + "front-desk/last-answer"))
                                .header("Authorization", "Bearer " + p.get("apiToken"))
                                .GET()
                                .build(),
                            HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode());
                LastAnswerResponse answer =
                    new ObjectMapper().readValue(response.body(), LastAnswerResponse.class);
                assertNotNull(answer.commandId());
              },
              "GET {base}/front-desk/last-answer answering 200 with commandId, sessionId, state,"
                  + " text and at")
              .withHeaders(Map.of("Authorization", "Bearer {apiToken}")));
}
