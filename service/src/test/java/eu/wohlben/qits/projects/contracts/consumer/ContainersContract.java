package eu.wohlben.qits.projects.contracts.consumer;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.containers.client.ContainersAnswer;
import eu.wohlben.qits.containers.client.ContainersClient;
import eu.wohlben.qits.containers.client.contracts.ContainersClientPact;
import eu.wohlben.qits.pact.consumer.Via;
import eu.wohlben.qits.projects.contracts.consumer.ConsumerRow.Trigger;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * <b>What qits-projects asks qits-containers</b> (ticket qits-1149), through qits-containers' own
 * client jar ({@link ContainersClient}): nothing but clean-up since the refinement containers were
 * retired (qits-1316). {@code LegacyAgentPlaces} deletes the legacy agent places with their volumes
 * ({@code volumes=true}) and reads what is left; {@code LegacyRefinementContainers} deletes the
 * retired refinement places and then their {@code qits_refinement_<rowId>} volumes.
 *
 * <p>The client decodes every answer into a type, so a row whose caller reads no field still binds
 * "a JSON object" ({@code $}): an empty body is unreadable to it.
 *
 * <p>The rows are this service's own, not the kit's ({@code qits-containers-client-contracts},
 * which drives the client with its own workload and body); the pact takes only the kit's {@code
 * qits-via} reference ({@link #VIA}), and {@link ContainersClientUsageTest} holds the rows in step
 * with the kit's scan of the compiled classes (qits-1163).
 */
final class ContainersContract {

  static final String PROVIDER = ContainersClientPact.PROVIDER;
  static final String APP = "qits-containers";

  /** The {@code qits-via} reference every interaction of this pact carries: client and version. */
  static final Via VIA = ContainersClientPact.VIA;

  private static final String PLACE = "/containers/api/containers/{owner}/{workload}/{ref}";
  private static final String VOLUME = "/containers/api/volumes/{owner}/{name}";

  private ContainersContract() {}

  private static ContainersClient client(String base) {
    return new ContainersClient(
        base, Duration.ofSeconds(5), Duration.ofSeconds(10), Optional::empty, RowClients.clients());
  }

  private static void ok(ContainersAnswer<?> answer) {
    assertTrue(answer.succeeded(), answer.detail());
  }

  private static ConsumerRow row(
      String operationId,
      String state,
      String method,
      String path,
      JsonNode body,
      List<String> consumes,
      int status,
      Trigger trigger,
      ConsumerRow.Call call,
      String needs) {
    return new ConsumerRow(
        PROVIDER, APP, operationId, state, method, path, Map.of(), body, consumes, status, trigger,
        call, needs);
  }

  static final List<ConsumerRow> ROWS =
      List.of(
          new ConsumerRow(
              PROVIDER,
              APP,
              "deleteContainer",
              "a running container",
              "DELETE",
              PLACE,
              Map.of("logs", "false", "volumes", "false"),
              null,
              List.of("$"),
              200,
              Trigger.schedule("LegacyRefinementContainers.run"),
              (base, p) ->
                  ok(client(base).delete(p.get("owner"), p.get("workload"), p.get("ref"), false, false)),
              "DELETE .../{ref} answering 200 with a delete outcome"),
          new ConsumerRow(
              PROVIDER,
              APP,
              "deleteContainer",
              "a running container with its volumes",
              "DELETE",
              PLACE,
              Map.of("logs", "false", "volumes", "true"),
              null,
              List.of("$"),
              200,
              Trigger.schedule("LegacyAgentPlaces.run"),
              (base, p) ->
                  ok(client(base).delete(p.get("owner"), p.get("workload"), p.get("ref"), true, false)),
              "DELETE .../{ref}?volumes=true answering 200 with a delete outcome"),
          row(
              "getVolume",
              "a claimed volume",
              "GET",
              VOLUME,
              null,
              List.of("$"),
              200,
              Trigger.schedule("LegacyAgentPlaces.run"),
              (base, p) -> {
                var answer = client(base).volume(p.get("owner"), p.get("name"));
                ok(answer);
                assertNotNull(answer.value());
              },
              "GET .../volumes/{owner}/{name} answering 200 with a volume envelope"),
          row(
              "deleteVolume",
              "a claimed volume",
              "DELETE",
              VOLUME,
              null,
              List.of("$"),
              200,
              Trigger.schedule("LegacyRefinementContainers.run"),
              (base, p) -> ok(client(base).deleteVolume(p.get("owner"), p.get("name"))),
              "DELETE .../volumes/{owner}/{name} answering 200 with a volume envelope"));
}

