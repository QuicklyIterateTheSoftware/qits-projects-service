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
 * <p><b>The row is pending, and on more than a missing recording.</b> qits-workspace-daemon removed
 * the container-wide {@code /agents/entity} and {@code /agents/blocked} doors with qits-1152 (its
 * {@code docs/openapi.yml}: "The checkout-wide routes of the single-checkout model … are gone"),
 * including at the version this service pins as its refinement image. Its golden masters record only
 * the per-worktree {@code setAgentEntity}, under {@code /workspaces/container/…}. So this call answers
 * 404 twice on a current refinement container; the row names the door it makes until either side
 * moves.
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
                  "a refinement daemon serving the container-wide entity door",
                  "POST",
                  "/projects/refinement-container/{refinementId}/agents/entity",
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
                                                + "/agents/entity"))
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
                  "POST {refinement proxy base}agents/entity with title, status, blocked and"
                      + " blockSource answering 2xx — a door qits-workspace-daemon removed with"
                      + " qits-1152; it records only the per-worktree setAgentEntity")
              .withHeaders(Map.of("Authorization", "Bearer {apiToken}")));
}
