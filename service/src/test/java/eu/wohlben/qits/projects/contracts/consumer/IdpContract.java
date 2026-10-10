package eu.wohlben.qits.projects.contracts.consumer;

import static eu.wohlben.qits.projects.contracts.consumer.ConsumerRow.json;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.projects.contracts.consumer.ConsumerRow.Trigger;
import eu.wohlben.qits.projects.idphost.IdpRefinementCredentials;
import eu.wohlben.qits.projects.idphost.IdpRunnerCommissioner;
import eu.wohlben.qits.projects.idphost.IdpSessionIntrospection;
import eu.wohlben.qits.projects.idphost.IdpTokens;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * <b>What qits-projects asks qits-idp</b> (ticket qits-1149): commissioned tokens and clients
 * (minted, listed, deleted) and the session introspection behind every person-only door. Each call
 * authenticates with this service's own client pair as HTTP Basic, which is not part of the
 * contract. The base is {@code quarkus.oidc-client.qits.auth-server-url}, which ends in {@code
 * /idp}. qits-idp publishes no golden masters yet, so every row waits on its state.
 */
final class IdpContract {

  static final String PROVIDER = "qits-idp-service";
  static final String APP = "qits-idp";

  private IdpContract() {}

  private static IdpTokens tokens(String base) {
    return Fields.with(
        new IdpTokens(),
        "objectMapper", new ObjectMapper(),
        "tokensEnabled", true,
        "authServerUrl", base + "/idp",
        "clientId", "dev-qits-projects",
        "clientSecret", Optional.of("own-secret"),
        "requestTimeout", Duration.ofSeconds(5));
  }

  private static IdpRunnerCommissioner commissioner(String base) {
    return Fields.with(
        new IdpRunnerCommissioner(),
        "objectMapper", new ObjectMapper(),
        "tokens", tokens(base),
        "tokensEnabled", true,
        "authServerUrl", base + "/idp",
        "clientId", "dev-qits-projects",
        "clientSecret", Optional.of("own-secret"),
        "requestTimeout", Duration.ofSeconds(5),
        "patience", Duration.ZERO);
  }

  private static IdpRefinementCredentials refinements(String base) {
    return Fields.with(
        new IdpRefinementCredentials(),
        "objectMapper", new ObjectMapper(),
        "tokensEnabled", true,
        "authServerUrl", base + "/idp",
        "clientId", "dev-qits-projects",
        "clientSecret", Optional.of("own-secret"),
        "requestTimeout", Duration.ofSeconds(5));
  }

  static final List<ConsumerRow> ROWS =
      List.of(
          new ConsumerRow(
              PROVIDER,
              APP,
              "commissionToken",
              "a service client that may commission tokens",
              "POST",
              "/idp/api/tokens",
              Map.of(),
              json(
                  "{\"contextKind\":\"agent-container\",\"contextId\":\"{projectId}\","
                      + "\"claims\":{\"project\":\"{projectId}\"},\"gitRefs\":[]}"),
              List.of("$.tokenId", "$.token", "$.subject"),
              201,
              Trigger.schedule("FrontDesks.ensure (IdpFrontDeskTokens.mint)"),
              (base, p) -> {
                IdpTokens.Issued issued =
                    tokens(base)
                        .commission(
                            "agent-container",
                            p.get("projectId"),
                            Map.of("project", p.get("projectId")),
                            List.of());
                assertFalse(issued.tokenId().isBlank());
              },
              "POST /idp/api/tokens answering 201 with tokenId, token and subject"),
          new ConsumerRow(
              PROVIDER,
              APP,
              "listTokens",
              "a service client with commissioned tokens",
              "GET",
              "/idp/api/tokens",
              Map.of(),
              null,
              List.of("$[*].tokenId", "$[*].contextKind", "$[*].contextId", "$[*].createdAt"),
              200,
              Trigger.schedule("FrontDeskTokenReconcile.reconcile"),
              (base, p) -> assertFalse(tokens(base).list().orElseThrow().isEmpty()),
              "GET /idp/api/tokens answering 200 with a root array of tokenId, contextKind,"
                  + " contextId and createdAt"),
          new ConsumerRow(
              PROVIDER,
              APP,
              "deleteToken",
              "a service client with commissioned tokens",
              "DELETE",
              "/idp/api/tokens/{tokenId}",
              Map.of(),
              null,
              List.of(),
              204,
              Trigger.schedule("FrontDeskTokenReconcile.reconcile"),
              (base, p) -> assertTrue(tokens(base).delete(p.get("tokenId"))),
              "DELETE /idp/api/tokens/{tokenId} answering 204"),
          new ConsumerRow(
              PROVIDER,
              APP,
              "commissionClient",
              "a service client that may commission clients",
              "POST",
              "/idp/api/clients",
              Map.of(),
              json("{\"contextKind\":\"desk-runner\",\"contextId\":\"{runnerId}\"}"),
              List.of("$.clientId", "$.secret"),
              201,
              Trigger.operation("DeskRunnerController.register"),
              (base, p) -> {
                IdpRunnerCommissioner.RunnerClient client =
                    commissioner(base).runnerClient(UUID.fromString(p.get("runnerId")));
                assertFalse(client.clientId().isBlank());
              },
              "POST /idp/api/clients for a desk-runner answering 201 with clientId and secret"),
          new ConsumerRow(
              PROVIDER,
              APP,
              "commissionClient",
              "a service client that may commission clients",
              "POST",
              "/idp/api/clients",
              Map.of(),
              json(
                  "{\"contextKind\":\"refinement\",\"contextId\":\"{refinementId}\","
                      + "\"claims\":{\"project\":\"{projectId}\"},\"gitRefs\":[]}"),
              List.of("$.clientId", "$.secret"),
              201,
              Trigger.operation("startWorkRefinement"),
              (base, p) ->
                  assertFalse(
                      refinements(base)
                          .commission(
                              Long.parseLong(p.get("refinementId")), p.get("projectId"), List.of())
                          .clientId()
                          .isBlank()),
              "POST /idp/api/clients for a refinement answering 201 with clientId and secret"),
          new ConsumerRow(
              PROVIDER,
              APP,
              "listClients",
              "a service client with commissioned clients",
              "GET",
              "/idp/api/clients",
              Map.of(),
              null,
              List.of("$[*].clientId", "$[*].contextKind", "$[*].contextId"),
              200,
              Trigger.schedule("DeskRunnerCommissionReconcile.reconcile"),
              (base, p) -> assertTrue(commissioner(base).liveClients("desk-runner").isPresent()),
              "GET /idp/api/clients answering 200 with a root array of clientId, contextKind and"
                  + " contextId"),
          new ConsumerRow(
              PROVIDER,
              APP,
              "decommissionClient",
              "a service client with commissioned clients",
              "DELETE",
              "/idp/api/clients/{clientId}",
              Map.of(),
              null,
              List.of(),
              204,
              Trigger.schedule("DeskRunnerCommissionReconcile.reapClients"),
              (base, p) -> assertTrue(commissioner(base).deleteClient(p.get("clientId"))),
              "DELETE /idp/api/clients/{clientId} answering 204"),
          new ConsumerRow(
              PROVIDER,
              APP,
              "introspectSession",
              "a signed-in person",
              "POST",
              "/idp/api/sessions/introspect",
              Map.of(),
              json("{\"token\":\"{sessionCookie}\"}"),
              List.of("$.username", "$.roles[*]"),
              200,
              Trigger.operation("approveReleaseRequest"),
              (base, p) ->
                  assertTrue(
                      Fields.with(
                              new IdpSessionIntrospection(),
                              "objectMapper", new ObjectMapper(),
                              "tokensEnabled", true,
                              "authServerUrl", base + "/idp",
                              "clientId", "dev-qits-projects",
                              "clientSecret", Optional.of("own-secret"))
                          .introspect(p.get("sessionCookie"))
                          .isPresent()),
              "POST /idp/api/sessions/introspect answering 200 with username and roles[]"));
}
