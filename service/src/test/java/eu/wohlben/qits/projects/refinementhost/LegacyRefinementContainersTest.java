package eu.wohlben.qits.projects.refinementhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.containers.client.ContainersClient;
import eu.wohlben.qits.http.ClosableQitsHttpClients;
import eu.wohlben.qits.projects.containershost.StubContainersServer;
import eu.wohlben.qits.projects.idphost.IdpRunnerCommissioner;
import eu.wohlben.qits.projects.idphost.IdpRunnerCommissionerFixture;
import eu.wohlben.qits.projects.idphost.RoutingIdpServer;
import jakarta.enterprise.inject.Vetoed;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The refinement containers' one-shot (qits-1316) against a stand-in qits-containers and qits-idp
 * and a scripted git host: each row's {@code refinement} place is deleted and then its {@code
 * qits_refinement_<rowId>} volume, a 404 counts as done and a refusal is retried next boot; the
 * {@code refinement} CLIENTS are deleted while the rooms' live {@code refinement} TOKENS are never
 * so much as listed; every {@code refining/*} branch of every wrapper goes and no other branch is
 * touched; and a second pass finds nothing to do.
 */
class LegacyRefinementContainersTest {

  private static final String OWNER = "dev-qits-projects";

  private static final String CLIENTS =
      "[{\"clientId\":\"rc-7\",\"contextKind\":\"refinement\",\"contextId\":\"7\"},"
          + "{\"clientId\":\"r-1\",\"contextKind\":\"desk-runner\",\"contextId\":\"x\"}]";

  /** A live room token of the same kind word, on the OTHER listing: it must never be reached. */
  private static final String TOKENS =
      "[{\"id\":\"tok-7\",\"contextKind\":\"refinement\",\"contextId\":\"7\"}]";

  private final List<ClosableQitsHttpClients> containersClients = new CopyOnWriteArrayList<>();

  private final List<IdpRunnerCommissioner> commissioners = new CopyOnWriteArrayList<>();

  @AfterEach
  void closeClients() {
    containersClients.forEach(ClosableQitsHttpClients::close);
    commissioners.forEach(IdpRunnerCommissionerFixture::close);
  }

  /**
   * The one-shot with its git host scripted: {@code branches} per wrapper, deletes recorded. Vetoed,
   * because the scope annotation is inherited and the suite's bean discovery would take it up.
   */
  @Vetoed
  private static final class Scripted extends LegacyRefinementContainers {
    final Map<String, List<String>> branches;
    final List<String> deleted = new ArrayList<>();

    Scripted(Map<String, List<String>> branches) {
      this.branches = branches;
    }

    @Override
    List<String> branchesOf(String repoId) {
      List<String> listed = branches.get(repoId);
      if (listed == null) {
        throw new IllegalStateException("the git host did not answer for " + repoId);
      }
      return listed;
    }

    @Override
    boolean deleteBranch(String repoId, String branch) {
      deleted.add(repoId + ":" + branch);
      return true;
    }
  }

  private Scripted wired(
      StubContainersServer containers, RoutingIdpServer idp, Map<String, List<String>> branches) {
    ClosableQitsHttpClients containersHttpClients = new ClosableQitsHttpClients();
    containersClients.add(containersHttpClients);
    Scripted oneShot = new Scripted(branches);
    oneShot.containers =
        new ContainersClient(
            containers.url(),
            Duration.ofSeconds(2),
            Duration.ofSeconds(5),
            Optional::empty,
            containersHttpClients);
    IdpRunnerCommissioner commissioner = IdpRunnerCommissionerFixture.pointedAt(idp.url());
    commissioners.add(commissioner);
    oneShot.idp = commissioner;
    oneShot.owner = OWNER;
    return oneShot;
  }

  @Test
  void placesThenVolumesGoClientsGoTokensStayAndOnlyRefiningBranchesAreDeleted() throws Exception {
    try (StubContainersServer containers = new StubContainersServer();
        RoutingIdpServer idp = new RoutingIdpServer()) {
      containers
          .script(200, "{}") // row 7: the place is deleted
          .script(200, "{\"owner\":\"" + OWNER + "\",\"name\":\"qits_refinement_7\"}") // its volume
          .script(404, "{\"error\":\"NOT_FOUND\"}") // row 8: no place — a session room all along
          .script(404, "{\"error\":\"NOT_FOUND\"}") // row 8: no volume either
          .script(503, "{\"error\":\"DOWN\"}"); // row 9: could not ask
      idp.on("GET /idp/api/clients", 200, CLIENTS);
      idp.on("GET /idp/api/tokens", 200, TOKENS);

      Scripted oneShot =
          wired(
              containers,
              idp,
              Map.of(
                  "wrapper-a", List.of("main", "refining/export", "ticket/x", "refining/rounding"),
                  "wrapper-b", List.of("main")));
      LegacyRefinementContainers.Outcome outcome =
          oneShot.run(List.of(7L, 8L, 9L), List.of("wrapper-a", "wrapper-b", "wrapper-c"));

      assertEquals(List.of(7L), outcome.placesRemoved());
      assertEquals(List.of(9L), outcome.placesFailed());
      assertEquals(List.of("qits_refinement_7"), outcome.volumesRemoved());
      assertEquals(List.of("rc-7"), outcome.clientsRemoved());
      assertEquals(
          List.of("wrapper-a:refining/export", "wrapper-a:refining/rounding"),
          outcome.branchesRemoved());
      assertEquals(List.of("wrapper-c"), outcome.branchesFailed());
      assertEquals(outcome.branchesRemoved(), oneShot.deleted);

      StubContainersServer.Received place = containers.received().get(0);
      assertEquals("DELETE", place.method());
      assertEquals("/containers/api/containers/" + OWNER + "/refinement/7", place.path());
      assertTrue(place.query().contains("volumes=false"), place.query());
      StubContainersServer.Received volume = containers.received().get(1);
      assertEquals("DELETE", volume.method());
      assertEquals("/containers/api/volumes/" + OWNER + "/qits_refinement_7", volume.path());
      assertEquals(5, containers.received().size(), "row 9's volume waits for its place");

      assertEquals(1, idp.received("DELETE", "/idp/api/clients/rc-7").size());
      assertEquals(0, idp.received("DELETE", "/idp/api/clients/r-1").size());
      assertTrue(
          idp.received().stream().noneMatch(r -> r.path().startsWith("/idp/api/tokens")),
          "the rooms' live tokens are never listed, let alone deleted: " + idp.received());
    }
  }

  @Test
  void aSecondBootDoesNothing() throws Exception {
    try (StubContainersServer containers = new StubContainersServer();
        RoutingIdpServer idp = new RoutingIdpServer()) {
      containers.fallback(404, "{\"error\":\"NOT_FOUND\"}");
      idp.on("GET /idp/api/clients", 200, "[]");

      Scripted oneShot = wired(containers, idp, Map.of("wrapper-a", List.of("main")));
      LegacyRefinementContainers.Outcome outcome =
          oneShot.run(List.of(7L, 8L), List.of("wrapper-a"));

      assertEquals(List.of(), outcome.placesRemoved());
      assertEquals(List.of(), outcome.placesFailed());
      assertEquals(List.of(), outcome.volumesRemoved());
      assertEquals(List.of(), outcome.clientsRemoved());
      assertEquals(List.of(), outcome.branchesRemoved());
      assertEquals(List.of(), outcome.branchesFailed());
      assertEquals(List.of(), oneShot.deleted);
      assertTrue(idp.received().stream().noneMatch(r -> r.method().equals("DELETE")));
    }
  }
}
