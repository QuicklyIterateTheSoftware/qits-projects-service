package eu.wohlben.qits.projects.deskhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.containers.client.ContainersClient;
import eu.wohlben.qits.projects.containershost.StubContainersServer;
import eu.wohlben.qits.projects.idphost.IdpRunnerCommissionerFixture;
import eu.wohlben.qits.projects.idphost.RoutingIdpServer;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The legacy one-shot (qits-767) against a stand-in qits-containers and qits-idp: each project's
 * {@code project-agent} place is deleted with its volumes and a 404 counts as done, the
 * {@code agent-container} clients are deleted, a surviving checkout volume is named, and a second
 * pass finds nothing to do.
 */
class LegacyAgentPlacesTest {

  private static final String OWNER = "dev-qits-projects";

  private static final String CLIENTS =
      "[{\"clientId\":\"c-1\",\"contextKind\":\"agent-container\",\"contextId\":\"p-1\"},"
          + "{\"clientId\":\"r-1\",\"contextKind\":\"desk-runner\",\"contextId\":\"x\"}]";

  private static LegacyAgentPlaces wired(StubContainersServer containers, RoutingIdpServer idp) {
    LegacyAgentPlaces places = new LegacyAgentPlaces();
    places.containers =
        new ContainersClient(
            containers.url(), Duration.ofSeconds(2), Duration.ofSeconds(5), Optional::empty);
    places.idp = IdpRunnerCommissionerFixture.pointedAt(idp.url());
    places.owner = OWNER;
    return places;
  }

  @Test
  void placesAreDeletedWithTheirVolumesA404IsDoneAndClientsAreReaped() throws Exception {
    try (StubContainersServer containers = new StubContainersServer();
        RoutingIdpServer idp = new RoutingIdpServer()) {
      containers
          .script(200, "{}") // p-1: the place is deleted
          .script(404, "{\"error\":\"NOT_FOUND\"}") // p-1: its volume is gone with it
          .script(404, "{\"error\":\"NOT_FOUND\"}") // p-2: no place at all — done already
          .script(200, "{\"owner\":\"" + OWNER + "\",\"name\":\"qits_project_p-2\"}") // survives
          .script(503, "{\"error\":\"DOWN\"}"); // p-3: could not ask
      idp.on("GET /idp/api/clients", 200, CLIENTS);

      LegacyAgentPlaces.Outcome outcome = wired(containers, idp).run(List.of("p-1", "p-2", "p-3"));

      assertEquals(List.of("p-1"), outcome.placesRemoved());
      assertEquals(List.of("p-3"), outcome.placesFailed());
      assertEquals(List.of("qits_project_p-2"), outcome.volumesSurviving());
      assertEquals(List.of("c-1"), outcome.clientsRemoved());
      StubContainersServer.Received first = containers.received().get(0);
      assertEquals("DELETE", first.method());
      assertEquals("/containers/api/containers/" + OWNER + "/project-agent/p-1", first.path());
      assertTrue(first.query().contains("volumes=true"), first.query());
      assertEquals(1, idp.received("DELETE", "/idp/api/clients/c-1").size());
      assertEquals(0, idp.received("DELETE", "/idp/api/clients/r-1").size());
    }
  }

  @Test
  void aSecondBootDoesNothing() throws Exception {
    try (StubContainersServer containers = new StubContainersServer();
        RoutingIdpServer idp = new RoutingIdpServer()) {
      containers.fallback(404, "{\"error\":\"NOT_FOUND\"}");
      idp.on("GET /idp/api/clients", 200, "[]");

      LegacyAgentPlaces.Outcome outcome = wired(containers, idp).run(List.of("p-1", "p-2"));

      assertEquals(List.of(), outcome.placesRemoved());
      assertEquals(List.of(), outcome.placesFailed());
      assertEquals(List.of(), outcome.clientsRemoved());
      assertEquals(List.of(), outcome.volumesSurviving());
      assertTrue(idp.received().stream().noneMatch(r -> r.method().equals("DELETE")));
    }
  }
}
