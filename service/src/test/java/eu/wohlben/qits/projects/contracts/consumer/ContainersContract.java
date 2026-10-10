package eu.wohlben.qits.projects.contracts.consumer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.containers.client.ContainersAnswer;
import eu.wohlben.qits.containers.client.ContainersClient;
import eu.wohlben.qits.containers.client.ContainersWire.EnsureRequest;
import eu.wohlben.qits.containers.client.ContainersWire.Envelope;
import eu.wohlben.qits.containers.client.ContainersWire.Policy;
import eu.wohlben.qits.containers.client.ContainersWire.Spec;
import eu.wohlben.qits.projects.contracts.consumer.ConsumerRow.Trigger;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * <b>What qits-projects asks qits-containers</b> (ticket qits-1149), through qits-containers' own
 * client jar ({@link ContainersClient}): the refinement containers and their volumes, and the
 * clean-up of the legacy agent places ({@code LegacyAgentPlaces}).
 *
 * <p>The client decodes every answer but {@code touch} into a type, so a row whose caller reads no
 * field still binds "a JSON object" ({@code $}): an empty body is unreadable to it.
 *
 * <p>The ensure body is what the client writes for a minimal spec, not the full refinement spec
 * {@code RefinementContainerFactory} builds: the contract is the shape of the door, and the factory's
 * values are this service's own business. qits-containers publishes no golden masters yet, so every
 * row waits on its state.
 */
final class ContainersContract {

  static final String PROVIDER = "qits-containers-service";
  static final String APP = "qits-containers";

  private static final EnsureRequest ENSURE =
      EnsureRequest.of(Spec.of("qits/qits-workspace:1", "qits-net"), Policy.ephemeral(3600L));

  private static final String PLACE = "/containers/api/containers/{owner}/{workload}/{ref}";
  private static final String VOLUME = "/containers/api/volumes/{owner}/{name}";

  private ContainersContract() {}

  private static ContainersClient client(String base) {
    return new ContainersClient(base, Duration.ofSeconds(5), Duration.ofSeconds(10), Optional::empty);
  }

  /** {@link #ENSURE} exactly as the client's own writer puts it on the wire. */
  private static JsonNode ensureBody() {
    try {
      Class<?> json = Class.forName("eu.wohlben.qits.containers.client.ContainersJson");
      Method write = json.getDeclaredMethod("write", Object.class);
      write.setAccessible(true);
      return ConsumerRow.json((String) write.invoke(null, ENSURE));
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("cannot reach the containers client's JSON writer", e);
    }
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
          row(
              "ensureContainer",
              "an owner with no container in a place",
              "PUT",
              PLACE,
              ensureBody(),
              List.of("$.containerName", "$.state.observed", "$.detail"),
              201,
              Trigger.operation("startWorkRefinement"),
              (base, p) -> {
                ContainersAnswer<Envelope> answer =
                    client(base).ensure(p.get("owner"), p.get("workload"), p.get("ref"), ENSURE);
                ok(answer);
                assertNotNull(answer.value().containerName());
              },
              "PUT .../containers/{owner}/{workload}/{ref} answering 201 with containerName,"
                  + " state.observed and detail"),
          row(
              "getContainer",
              "an owner with a running container",
              "GET",
              PLACE,
              null,
              List.of("$.containerName", "$.state.observed"),
              200,
              Trigger.operation("getWorkRefinement"),
              (base, p) -> {
                ContainersAnswer<Envelope> answer =
                    client(base).status(p.get("owner"), p.get("workload"), p.get("ref"));
                ok(answer);
                assertNotNull(answer.value().state());
              },
              "GET .../containers/{owner}/{workload}/{ref} answering 200 with containerName and"
                  + " state.observed"),
          row(
              "listWorkloadContainers",
              "an owner with a running container",
              "GET",
              "/containers/api/containers/{owner}/{workload}",
              null,
              List.of("$.containers[*].containerName", "$.containers[*].state.observed"),
              200,
              Trigger.operation("startWorkRefinement"),
              (base, p) -> {
                ContainersAnswer<List<Envelope>> answer =
                    client(base).list(p.get("owner"), p.get("workload"));
                ok(answer);
                assertFalse(answer.value().isEmpty());
              },
              "GET .../containers/{owner}/{workload} answering 200 with containers[] of"
                  + " containerName and state.observed"),
          row(
              "stopContainer",
              "an owner with a running container",
              "POST",
              PLACE + "/stop",
              null,
              List.of("$"),
              200,
              Trigger.schedule("RefinementService.stopContainer"),
              (base, p) -> ok(client(base).stop(p.get("owner"), p.get("workload"), p.get("ref"))),
              "POST .../{ref}/stop answering 200 with an envelope"),
          row(
              "touchContainer",
              "an owner with a running container",
              "POST",
              PLACE + "/touch",
              null,
              List.of(),
              204,
              Trigger.operation("getWorkRefinement"),
              (base, p) -> ok(client(base).touch(p.get("owner"), p.get("workload"), p.get("ref"))),
              "POST .../{ref}/touch answering 204"),
          row(
              "deleteContainer",
              "an owner with a running container",
              "DELETE",
              PLACE,
              null,
              List.of("$"),
              200,
              Trigger.schedule("RefinementService.discard"),
              (base, p) ->
                  ok(client(base).delete(p.get("owner"), p.get("workload"), p.get("ref"), false, false)),
              "DELETE .../{ref} answering 200 with a delete outcome"),
          row(
              "ensureVolume",
              "an owner with no volume of that name",
              "PUT",
              VOLUME,
              null,
              List.of("$"),
              201,
              Trigger.operation("startWorkRefinement"),
              (base, p) -> ok(client(base).ensureVolume(p.get("owner"), p.get("name"))),
              "PUT .../volumes/{owner}/{name} answering 201 with a volume envelope"),
          row(
              "getVolume",
              "an owner with a volume",
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
              "an owner with a volume",
              "DELETE",
              VOLUME,
              null,
              List.of("$"),
              200,
              Trigger.schedule("RefinementService.discard"),
              (base, p) -> ok(client(base).deleteVolume(p.get("owner"), p.get("name"))),
              "DELETE .../volumes/{owner}/{name} answering 200 with a volume envelope"));
}
