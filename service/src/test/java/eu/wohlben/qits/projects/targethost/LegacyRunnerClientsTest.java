package eu.wohlben.qits.projects.targethost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.idphost.IdpClients;
import eu.wohlben.qits.projects.idphost.IdpClientsFixture;
import eu.wohlben.qits.projects.idphost.RoutingIdpServer;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * qits-1592's lesson, the desk cutover's side (epic qits-1481, qits-1551): the cutover only GIVES
 * BACK credentials.
 *
 * <p>On 2026-10-24 the workspace cutover deleted the old runner's qits-idp client, and that runner
 * read the revoked credential as "deleted" and purged every container and volume it held. The live
 * desk runner runs a toolkit that stops without purging on a revoked credential; what this service
 * must hold up is that {@link LegacyRunnerClients} deletes qits-idp clients and tokens of the two
 * retired kinds, {@code desk-runner} and {@code desk-runner-registration}, and nothing else — no
 * other kind's credential, nothing minted, and no word to any runner: no {@code Retire{DELETED}}, no
 * 1008 {@code RUNNER_DELETED} close. The last is proved structurally: the socket, the registry, the
 * commission reconcile that revoked a vanished runner's client, and the protocols those frames
 * travel in are gone from this service's classpath.
 */
class LegacyRunnerClientsTest {

  private RoutingIdpServer idp;

  private IdpClients clients;

  private LegacyRunnerClients legacy;

  @BeforeEach
  void wire() throws Exception {
    idp = new RoutingIdpServer();
    clients = IdpClientsFixture.pointedAt(idp.url());
    legacy = new LegacyRunnerClients();
    legacy.idp = clients;
  }

  @AfterEach
  void stop() {
    IdpClientsFixture.close(clients);
    idp.close();
  }

  private static String client(String clientId, String kind, String contextId) {
    return "{\"clientId\":\"" + clientId + "\",\"owner\":\"dev-qits-projects\",\"contextKind\":\""
        + kind + "\",\"contextId\":\"" + contextId + "\",\"createdAt\":\"2026-10-20T10:00:00Z\"}";
  }

  private static String token(String tokenId, String kind, String contextId) {
    return "{\"tokenId\":\"" + tokenId + "\",\"subject\":\"tok-" + tokenId
        + "\",\"owner\":\"dev-qits-projects\",\"contextKind\":\"" + kind + "\",\"contextId\":\""
        + contextId + "\",\"claims\":{},\"gitRefs\":[],\"createdAt\":\"2026-10-20T10:00:00Z\"}";
  }

  private List<String> deleted(String prefix) {
    return idp.received().stream()
        .filter(r -> r.method().equals("DELETE") && r.path().startsWith(prefix))
        .map(r -> r.path().substring(prefix.length()))
        .toList();
  }

  @Test
  void itDeletesOnlyTheRetiredKindsCredentialsAndMintsNothing() {
    idp.on(
        "GET /idp/api/clients",
        200,
        "["
            + String.join(
                ",",
                client("client-desk-runner", "desk-runner", "runner-1"),
                client("client-agent-container", "agent-container", "p-1"),
                client("client-refinement", "refinement", "r-1"))
            + "]");
    idp.on(
        "GET /idp/api/tokens",
        200,
        "["
            + String.join(
                ",",
                token("token-registration", "desk-runner-registration", "runner-2"),
                token("token-desk", "agent-container", "p-1"),
                token("token-session", "desk-session", "p-1"),
                token("token-delivery", "desk-delivery", "p-1"))
            + "]");

    int given = legacy.giveBack();

    assertEquals(2, given);
    assertEquals(List.of("client-desk-runner"), deleted("/idp/api/clients/"));
    assertEquals(List.of("token-registration"), deleted("/idp/api/tokens/"));
    // Gives back, never commissions: nothing is posted anywhere.
    assertTrue(
        idp.received().stream().noneMatch(r -> r.method().equals("POST")), idp.received().toString());
  }

  @Test
  void itGivesBackNothingWhenNoRetiredCredentialIsLeft() {
    idp.on("GET /idp/api/clients", 200, "[" + client("client-x", "agent-container", "p") + "]");
    idp.on("GET /idp/api/tokens", 200, "[" + token("token-x", "desk-session", "p") + "]");

    assertEquals(0, legacy.giveBack());
    assertTrue(idp.received().stream().noneMatch(r -> r.method().equals("DELETE")));
  }

  @Test
  void anUnreadableListingDeletesNothing() {
    idp.on("GET /idp/api/clients", 500, "{}");
    idp.on("GET /idp/api/tokens", 200, "{\"not\":\"an array\"}");

    assertEquals(0, legacy.giveBack());
    assertTrue(idp.received().stream().noneMatch(r -> r.method().equals("DELETE")));
    assertTrue(idp.received().size() >= 2, "it did ask");
  }

  /** Its one collaborator is qits-idp: it holds no handle on a runner, a socket or a registry. */
  @Test
  void itsOnlyCollaboratorIsTheIdpClients() {
    List<Class<?>> collaborators =
        Arrays.stream(LegacyRunnerClients.class.getDeclaredFields())
            .filter(field -> !Modifier.isStatic(field.getModifiers()))
            .<Class<?>>map(Field::getType)
            .toList();

    assertEquals(List.of(IdpClients.class), collaborators);
  }

  /**
   * And there is no runner to speak to: the runner socket, its registry, the commission reconcile
   * that revoked a vanished runner's client, the runner doors, and the protocols that carried {@code
   * Retire} and the {@code RUNNER_DELETED} close are gone, so neither can be sent from here at all.
   */
  @Test
  void noRunnerSocketOrRunnerProtocolIsLeftToSendARetireOrA1008Close() {
    for (String gone :
        List.of(
            "eu.wohlben.qits.projects.deskhost.DeskRunnerSocket",
            "eu.wohlben.qits.projects.deskhost.DeskRunnerRegistry",
            "eu.wohlben.qits.projects.deskhost.DeskRunnerCommissionReconcile",
            "eu.wohlben.qits.projects.deskhost.DeskRunnerWork",
            "eu.wohlben.qits.projects.api.DeskRunnerController",
            "eu.wohlben.qits.projects.idphost.IdpRunnerCommissioner",
            "eu.wohlben.qits.projects.entity.DeskRunner",
            "eu.wohlben.qits.projectsdeskrunner.protocol.DeskRunnerProtocol",
            "eu.wohlben.qits.runner.protocol.Retire",
            "eu.wohlben.qits.runner.protocol.RunnerCodec")) {
      assertThrows(
          ClassNotFoundException.class,
          () -> Class.forName(gone, false, LegacyRunnerClientsTest.class.getClassLoader()),
          gone + " must not be on the classpath");
    }
  }
}
