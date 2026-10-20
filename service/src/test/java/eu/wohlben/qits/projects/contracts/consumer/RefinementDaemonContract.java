package eu.wohlben.qits.projects.contracts.consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.projects.contracts.consumer.ConsumerRow.Trigger;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

/**
 * <b>What qits-projects asks a refinement container's qits-workspace-daemon</b> (ticket qits-1149):
 * the entity's title, status and block flag, posted down the tunnel by {@code
 * refinementhost/RefinementAgentEntities} whenever one of them changes. Every other request to that
 * daemon is a browser's, passed through verbatim by {@code RefinementProxyRoute}, and has no row.
 *
 * <p>The client speaks Vert.x over a loopback tunnel a test cannot stand up without a daemon, so the
 * row's call makes the same POST with the JDK client, with the body and bearer {@code send} writes.
 *
 * <p>This row targets the per-agent {@code setAgentEntity} door (qits-1285), keyed by the entity id
 * exactly as {@code RefinementAgentEntities} now addresses it, under the refinement proxy prefix
 * rather than the provider's own {@code /workspaces/container/…} one.
 */
final class RefinementDaemonContract {

  static final String PROVIDER = "qits-workspace-daemon";
  static final String APP = "qits-workspace-daemon";

  private RefinementDaemonContract() {}

  static final List<ConsumerRow> ROWS =
      List.of(
          new ConsumerRow(
                  PROVIDER,
                  APP,
                  "setAgentEntity",
                  "a workspace daemon with an agent running",
                  "POST",
                  "/projects/refinement-container/{refinementId}/agent-worktrees/{agentId}/entity",
                  Map.of(),
                  ConsumerRow.json(
                      "{\"title\":\"Fix it\",\"status\":\"REPORTED\",\"blocked\":true,"
                          + "\"blockSource\":\"EXPLICIT\"}"),
                  List.of(),
                  200,
                  Trigger.operation("setWorkBlocked (RefinementAgentEntities.changed)"),
                  (base, p) -> {
                    HttpResponse<String> response =
                        RowClients.HTTP
                            .send(
                                HttpRequest.newBuilder(
                                        URI.create(
                                            base
                                                + "/projects/refinement-container/"
                                                + p.get("refinementId")
                                                + "/agent-worktrees/"
                                                + p.get("agentId")
                                                + "/entity"))
                                    .header("Authorization", "Bearer " + p.get("apiToken"))
                                    .header("Content-Type", "application/json")
                                    .POST(
                                        HttpRequest.BodyPublishers.ofString(
                                            "{\"title\":\"Fix it\",\"status\":\"REPORTED\","
                                                + "\"blocked\":true,\"blockSource\":\"EXPLICIT\"}"))
                                    .build(),
                                HttpResponse.BodyHandlers.ofString());
                    assertEquals(200, response.statusCode());
                  },
                  "params refinementId and agentId; POST {refinement proxy"
                      + " base}agent-worktrees/{agentId}/entity with title, status, blocked and"
                      + " blockSource answering 200 — the per-agent setAgentEntity door (qits-1285);"
                      + " ours keeps the refinement proxy prefix where the provider's own recording"
                      + " keeps its workspace one")
              .withHeaders(Map.of("Authorization", "Bearer {apiToken}")));
}
