package eu.wohlben.qits.projects.contracts.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.projects.contracts.GoldenFiles;
import eu.wohlben.qits.projects.contracts.consumer.ConsumerRow.Trigger;
import eu.wohlben.qits.projects.idphost.IdpRefinementCredentials;
import eu.wohlben.qits.projects.idphost.IdpRunnerCommissioner;
import eu.wohlben.qits.projects.idphost.IdpSessionIntrospection;
import eu.wohlben.qits.projects.idphost.IdpTokens;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import static eu.wohlben.qits.projects.contracts.consumer.ConsumerRow.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>What qits-projects asks qits-idp</b> (ticket qits-1149): commissioned tokens and clients
 * (minted, listed, deleted), the session introspection behind every person-only door, and the
 * client-credentials token mint every bearer to a peer comes from.
 *
 * <p><b>Every call authenticates with this service's own client pair as HTTP Basic</b>, and qits-idp
 * records each door with the {@code Authorization} its state's caller presents ({@code
 * {authorization}}). So every row sends that header, and each client is configured with the pair
 * the state's header encodes: what the client puts on the wire is then exactly the header the
 * provider's state answers. The base is {@code quarkus.oidc-client.qits.auth-server-url}, which
 * ends in {@code /idp}.
 *
 * <p><b>The context ids are this service's, not the provider's</b>: a front desk's project, a
 * runner's id and a refinement's id are whatever this side commissions for, so the bodies carry
 * fixed examples rather than state parameters.
 *
 * <p><b>The token mint is quarkus-oidc-client's</b> ({@code quarkus.oidc-client.qits}), which runs
 * only inside Quarkus. Its row makes the same request with the JDK client, built from the client's
 * own configuration in {@code application.properties} (grant {@code client}, its audience, the
 * secret as HTTP Basic), and reads what {@code TokensHelper} reads.
 */
final class IdpContract {

  static final String PROVIDER = "qits-idp-service";
  static final String APP = "qits-idp";

  /** The token mint's trigger: every bearer this service presents to a peer is minted here. */
  static final String TOKEN_MINT_TRIGGER =
      "quarkus-oidc-client qits (before the peer bearer expires)";

  private static final Map<String, String> AUTH = Map.of("Authorization", "{authorization}");

  private static final String FRONT_DESK_PROJECT = "00000000-0000-4000-8000-0000000000a1";
  private static final String RUNNER = "00000000-0000-4000-8000-0000000000b1";
  private static final long REFINEMENT = 41L;
  private static final String REFINEMENT_PROJECT = "00000000-0000-4000-8000-0000000000c1";

  private IdpContract() {}

  /** The client pair the state's {@code Basic} header encodes. */
  private static String[] pair(Map<String, String> p) {
    String header = p.get("authorization");
    String decoded =
        new String(
            Base64.getDecoder().decode(header.substring("Basic ".length())), StandardCharsets.UTF_8);
    return decoded.split(":", 2);
  }

  private static IdpTokens tokens(String base, Map<String, String> p) {
    String[] pair = pair(p);
    return Fields.with(
        new IdpTokens(RowClients.clients()),
        "objectMapper", new ObjectMapper(),
        "tokensEnabled", true,
        "authServerUrl", base + "/idp",
        "clientId", pair[0],
        "clientSecret", Optional.of(pair[1]),
        "requestTimeout", Duration.ofSeconds(5));
  }

  private static IdpRunnerCommissioner commissioner(String base, Map<String, String> p) {
    String[] pair = pair(p);
    return Fields.with(
        new IdpRunnerCommissioner(RowClients.clients()),
        "objectMapper", new ObjectMapper(),
        "tokens", tokens(base, p),
        "tokensEnabled", true,
        "authServerUrl", base + "/idp",
        "clientId", pair[0],
        "clientSecret", Optional.of(pair[1]),
        "requestTimeout", Duration.ofSeconds(5),
        "patience", Duration.ZERO);
  }

  private static IdpRefinementCredentials refinements(String base, Map<String, String> p) {
    String[] pair = pair(p);
    return Fields.with(
        new IdpRefinementCredentials(RowClients.clients()),
        "objectMapper", new ObjectMapper(),
        "tokensEnabled", true,
        "authServerUrl", base + "/idp",
        "clientId", pair[0],
        "clientSecret", Optional.of(pair[1]),
        "requestTimeout", Duration.ofSeconds(5));
  }

  private static ConsumerRow row(
      String operationId,
      String state,
      String method,
      String path,
      String body,
      List<String> consumes,
      int status,
      Trigger trigger,
      ConsumerRow.Call call,
      String needs) {
    return new ConsumerRow(
            PROVIDER, APP, operationId, state, method, path, Map.of(),
            body == null ? null : json(body), consumes, status, trigger, call, needs)
        .withHeaders(AUTH);
  }

  // --- the token mint, as quarkus-oidc-client sends it ----------------------------------------

  /**
   * {@code quarkus.oidc-client.qits.<key>} as the shipped {@code application.properties} sets it:
   * main's file, read off the tree, since the test classpath's copy of that name is the test one.
   */
  private static String oidcClient(String key) {
    String value = shippedProperties().getProperty("quarkus.oidc-client.qits." + key);
    if (value == null) {
      throw new IllegalStateException("application.properties sets no quarkus.oidc-client.qits." + key);
    }
    return value.trim();
  }

  /** The service's shipped {@code application.properties}. */
  static Properties shippedProperties() {
    Properties properties = new Properties();
    try (InputStream in =
        Files.newInputStream(
            GoldenFiles.repositoryRoot()
                .resolve("service/src/main/resources/application.properties"))) {
      properties.load(in);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return properties;
  }

  /** The form quarkus-oidc-client posts for {@code grant.type=client} with an audience option. */
  static String tokenForm() {
    if (!"client".equals(oidcClient("grant.type"))) {
      throw new IllegalStateException("the qits oidc-client no longer mints with grant.type=client");
    }
    return "grant_type=client_credentials&audience=" + oidcClient("grant-options.client.audience");
  }

  private static void mint(String base, Map<String, String> p) throws Exception {
    HttpResponse<String> response =
        RowClients.HTTP
            .send(
                HttpRequest.newBuilder(URI.create(base + "/idp/" + oidcClient("token-path")))
                    .header("Authorization", p.get("authorization"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(tokenForm()))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode());
    JsonNode token = new ObjectMapper().readTree(response.body());
    assertFalse(token.path("access_token").asText().isBlank());
    assertTrue(token.path("expires_in").isNumber());
  }

  static final List<ConsumerRow> ROWS =
      List.of(
          new ConsumerRow(
                  PROVIDER,
                  APP,
                  "issueToken",
                  "a service client calling its peers",
                  "POST",
                  "/idp/token",
                  Map.of(),
                  null,
                  List.of("$.access_token", "$.expires_in"),
                  200,
                  Trigger.schedule(TOKEN_MINT_TRIGGER),
                  IdpContract::mint,
                  "POST /idp/token, client_credentials with audience qits-platform and the client"
                      + " pair as HTTP Basic, answering 200 with access_token and expires_in")
              .withHeaders(AUTH)
              .withFormBody(tokenForm()),
          row(
              "commissionToken",
              "a service client with the system role",
              "POST",
              "/idp/api/tokens",
              "{\"contextKind\":\"agent-container\",\"contextId\":\"" + FRONT_DESK_PROJECT + "\","
                  + "\"claims\":{\"project\":\"" + FRONT_DESK_PROJECT + "\"},\"gitRefs\":[]}",
              List.of("$.tokenId", "$.token", "$.subject"),
              201,
              Trigger.schedule("FrontDesks.ensure (IdpFrontDeskTokens.mint)"),
              (base, p) -> {
                IdpTokens.Issued issued =
                    tokens(base, p)
                        .commission(
                            "agent-container",
                            FRONT_DESK_PROJECT,
                            Map.of("project", FRONT_DESK_PROJECT),
                            List.of());
                assertFalse(issued.tokenId().isBlank());
              },
              "POST /idp/api/tokens answering 201 with tokenId, token and subject"),
          row(
              "commissionToken",
              "a service client with the system role",
              "POST",
              "/idp/api/tokens",
              "{\"contextKind\":\"desk-runner-registration\",\"contextId\":\"" + RUNNER + "\"}",
              List.of("$.tokenId", "$.token", "$.subject"),
              201,
              Trigger.operation("DeskRunnerController.create"),
              (base, p) ->
                  assertFalse(
                      commissioner(base, p)
                          .registrationToken(UUID.fromString(RUNNER))
                          .token()
                          .isBlank()),
              "POST /idp/api/tokens for a desk-runner registration answering 201 with tokenId, token"
                  + " and subject"),
          row(
              "listTokens",
              "a commissioned token",
              "GET",
              "/idp/api/tokens",
              null,
              List.of("$[*].tokenId", "$[*].contextKind", "$[*].contextId", "$[*].createdAt"),
              200,
              Trigger.schedule("FrontDeskTokenReconcile.reconcile"),
              (base, p) -> assertFalse(tokens(base, p).list().orElseThrow().isEmpty()),
              "GET /idp/api/tokens answering 200 with a root array of tokenId, contextKind,"
                  + " contextId and createdAt"),
          row(
              "listTokens",
              "a commissioned token",
              "GET",
              "/idp/api/tokens",
              null,
              List.of("$[*].tokenId", "$[*].contextKind", "$[*].contextId", "$[*].createdAt"),
              200,
              Trigger.schedule("DeskRunnerCommissionReconcile.reapRegistrationTokens"),
              (base, p) ->
                  assertFalse(commissioner(base, p).liveTokens().orElseThrow().isEmpty()),
              "GET /idp/api/tokens answering 200 with a root array of tokenId, contextKind,"
                  + " contextId and createdAt"),
          row(
              "deleteToken",
              "a commissioned token",
              "DELETE",
              "/idp/api/tokens/{tokenId}",
              null,
              List.of(),
              204,
              Trigger.schedule("FrontDeskTokenReconcile.reconcile"),
              (base, p) -> assertTrue(tokens(base, p).delete(p.get("tokenId"))),
              "DELETE /idp/api/tokens/{tokenId} answering 204"),
          row(
              "deleteToken",
              "a commissioned token",
              "DELETE",
              "/idp/api/tokens/{tokenId}",
              null,
              List.of(),
              204,
              Trigger.schedule("DeskRunnerCommissionReconcile.reapRegistrationTokens"),
              (base, p) -> assertTrue(commissioner(base, p).deleteToken(p.get("tokenId"))),
              "DELETE /idp/api/tokens/{tokenId} answering 204"),
          row(
              "commissionClient",
              "a service client with the system role",
              "POST",
              "/idp/api/clients",
              "{\"contextKind\":\"desk-runner\",\"contextId\":\"" + RUNNER + "\"}",
              List.of("$.clientId", "$.secret"),
              201,
              Trigger.operation("DeskRunnerController.register"),
              (base, p) -> {
                IdpRunnerCommissioner.RunnerClient client =
                    commissioner(base, p).runnerClient(UUID.fromString(RUNNER));
                assertFalse(client.clientId().isBlank());
              },
              "POST /idp/api/clients for a desk-runner answering 201 with clientId and secret"),
          row(
              "commissionClient",
              "a service client with the system role",
              "POST",
              "/idp/api/clients",
              "{\"contextKind\":\"refinement\",\"contextId\":\"" + REFINEMENT + "\","
                  + "\"claims\":{\"project\":\"" + REFINEMENT_PROJECT + "\"},\"gitRefs\":[]}",
              List.of("$.clientId", "$.secret"),
              201,
              Trigger.operation("startWorkRefinement"),
              (base, p) ->
                  assertFalse(
                      refinements(base, p)
                          .commission(REFINEMENT, REFINEMENT_PROJECT, List.of())
                          .clientId()
                          .isBlank()),
              "POST /idp/api/clients for a refinement answering 201 with clientId and secret"),
          row(
              "listClients",
              "a commissioned client",
              "GET",
              "/idp/api/clients",
              null,
              List.of("$[*].clientId", "$[*].contextKind", "$[*].contextId"),
              200,
              Trigger.schedule("DeskRunnerCommissionReconcile.reapClients"),
              (base, p) -> assertTrue(commissioner(base, p).liveRunnerClients().isPresent()),
              "GET /idp/api/clients answering 200 with a root array of clientId, contextKind and"
                  + " contextId"),
          row(
              "listClients",
              "a commissioned client",
              "GET",
              "/idp/api/clients",
              null,
              List.of("$[*].clientId", "$[*].contextKind", "$[*].contextId"),
              200,
              Trigger.schedule("LegacyAgentPlaces.run"),
              (base, p) ->
                  assertTrue(commissioner(base, p).liveClients("agent-container").isPresent()),
              "GET /idp/api/clients answering 200 with a root array of clientId, contextKind and"
                  + " contextId"),
          row(
              "listClients",
              "a commissioned client",
              "GET",
              "/idp/api/clients",
              null,
              List.of("$[*].clientId", "$[*].contextKind", "$[*].contextId"),
              200,
              Trigger.schedule("RefinementCommissionReconcile.reconcile"),
              (base, p) -> refinements(base, p).listRefinementCommissions(),
              "GET /idp/api/clients answering 200 with a root array of clientId, contextKind and"
                  + " contextId"),
          row(
              "decommissionClient",
              "a commissioned client",
              "DELETE",
              "/idp/api/clients/{clientId}",
              null,
              List.of(),
              204,
              Trigger.schedule("DeskRunnerCommissionReconcile.reapClients"),
              (base, p) -> commissioner(base, p).decommissionClient(p.get("clientId")),
              "DELETE /idp/api/clients/{clientId} answering 204"),
          row(
              "decommissionClient",
              "a commissioned client",
              "DELETE",
              "/idp/api/clients/{clientId}",
              null,
              List.of(),
              204,
              Trigger.schedule("LegacyAgentPlaces.run"),
              (base, p) -> assertTrue(commissioner(base, p).deleteClient(p.get("clientId"))),
              "DELETE /idp/api/clients/{clientId} answering 204"),
          row(
              "decommissionClient",
              "a commissioned client",
              "DELETE",
              "/idp/api/clients/{clientId}",
              null,
              List.of(),
              204,
              Trigger.schedule("RefinementCommissionReconcile.reconcile"),
              (base, p) -> refinements(base, p).decommission(p.get("clientId")),
              "DELETE /idp/api/clients/{clientId} answering 204"),
          row(
              "introspectSession",
              "a signed-in person",
              "POST",
              "/idp/api/sessions/introspect",
              "{\"token\":\"{sessionToken}\"}",
              List.of("$.username", "$.roles[*]"),
              200,
              Trigger.operation("approveReleaseRequest"),
              (base, p) -> {
                String[] pair = pair(p);
                assertTrue(
                    Fields.with(
                            new IdpSessionIntrospection(RowClients.clients()),
                            "objectMapper", new ObjectMapper(),
                            "tokensEnabled", true,
                            "authServerUrl", base + "/idp",
                            "clientId", pair[0],
                            "clientSecret", Optional.of(pair[1]))
                        .introspect(p.get("sessionToken"))
                        .isPresent());
              },
              "POST /idp/api/sessions/introspect answering 200 with username and roles[]"));
}
