package eu.wohlben.qits.projects.idphost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * What goes on the wire to qits-idp when a front-desk runner is commissioned (qits-767), and how
 * each answer is read: the two kinds and their context, this service's own Basic pair, the patience
 * across a cutover, the give-backs that never throw, and the listings that answer EMPTY rather than
 * an empty list when they could not be read. Plain JUnit against a stub, the {@code
 * IdpAgentCredentialsTest} way.
 */
class IdpRunnerCommissionerTest {

  private static final UUID RUNNER = UUID.fromString("11111111-2222-3333-4444-555555555555");

  private static final String BASIC =
      "Basic "
          + Base64.getEncoder()
              .encodeToString(
                  (IdpRunnerCommissionerFixture.OWN_CLIENT
                          + ":"
                          + IdpRunnerCommissionerFixture.OWN_SECRET)
                      .getBytes(StandardCharsets.UTF_8));

  @Test
  void theRegistrationTokenIsOfTheRegistrationKindNamedByTheRunner() throws IOException {
    try (StubIdpServer stub = new StubIdpServer()) {
      stub.answering(
          201, "{\"tokenId\":\"t-1\",\"token\":\"qits_tok_abc\",\"subject\":\"sub-1\",\"x\":1}");

      IdpTokens.Issued token =
          IdpRunnerCommissionerFixture.pointedAt(stub.url()).registrationToken(RUNNER);

      assertEquals("t-1", token.tokenId());
      assertEquals("qits_tok_abc", token.token());
      assertEquals("sub-1", token.subject());
      assertFalse(token.toString().contains("qits_tok_abc"), "the value is never in a log line");
      StubIdpServer.Received request = stub.received().get(0);
      assertEquals("POST", request.method());
      assertEquals("/idp/api/tokens", request.path());
      assertEquals(BASIC, request.authorization());
      assertTrue(
          request.body().contains("\"contextKind\":\"desk-runner-registration\""), request.body());
      assertTrue(request.body().contains("\"contextId\":\"" + RUNNER + "\""), request.body());
      assertFalse(request.body().contains("claims"), "a runner states no claims: " + request.body());
    }
  }

  @Test
  void theRunnerClientIsOfTheRunnerKindAndStatesNothingElse() throws IOException {
    try (StubIdpServer stub = new StubIdpServer()) {
      stub.answering(201, "{\"clientId\":\"dr-1\",\"secret\":\"s3cr3t\"}");

      IdpRunnerCommissioner.RunnerClient client =
          IdpRunnerCommissionerFixture.pointedAt(stub.url()).runnerClient(RUNNER);

      assertEquals("dr-1", client.clientId());
      assertEquals("s3cr3t", client.secret());
      assertFalse(client.toString().contains("s3cr3t"));
      StubIdpServer.Received request = stub.received().get(0);
      assertEquals("/idp/api/clients", request.path());
      assertEquals(BASIC, request.authorization());
      assertEquals(
          "{\"contextKind\":\"desk-runner\",\"contextId\":\"" + RUNNER + "\"}", request.body());
    }
  }

  /** The kinds fit qits-idp's 32-character context kind; the projects- prefix would not. */
  @Test
  void theKindsFitTheIdpsContextKind() {
    assertTrue(IdpRunnerCommissioner.RUNNER_KIND.length() <= 32);
    assertTrue(IdpRunnerCommissioner.REGISTRATION_KIND.length() <= 32);
  }

  @Test
  void anAnswerAboutTheMomentIsAskedAgainInsideThePatience() throws IOException {
    try (StubIdpServer stub = new StubIdpServer()) {
      stub.answering(401, "{}")
          .answering(503, "{}")
          .answering(201, "{\"clientId\":\"dr-1\",\"secret\":\"s3cr3t\"}");

      IdpRunnerCommissioner.RunnerClient client =
          IdpRunnerCommissionerFixture.pointedAt(stub.url(), Duration.ofSeconds(30))
              .runnerClient(RUNNER);

      assertEquals("dr-1", client.clientId());
      assertEquals(3, stub.received().size());
    }
  }

  @Test
  void anAnswerAboutTheRequestIsNotAskedAgain() throws IOException {
    try (StubIdpServer stub = new StubIdpServer()) {
      stub.answering(400, "{\"error\":\"unknown kind\"}").answering(201, "{}");

      IdpRunnerCommissioner.CommissionFailedException failed =
          assertThrows(
              IdpRunnerCommissioner.CommissionFailedException.class,
              () ->
                  IdpRunnerCommissionerFixture.pointedAt(stub.url(), Duration.ofSeconds(30))
                      .registrationToken(RUNNER));

      assertTrue(failed.getMessage().contains("unknown kind"), failed.getMessage());
      assertEquals(1, stub.received().size());
    }
  }

  @Test
  void anAnswerWithNoUsableTokenIsAFailure() throws IOException {
    try (StubIdpServer stub = new StubIdpServer()) {
      stub.answering(201, "{\"tokenId\":\"t-1\"}");

      assertThrows(
          IdpRunnerCommissioner.CommissionFailedException.class,
          () -> IdpRunnerCommissionerFixture.pointedAt(stub.url()).registrationToken(RUNNER));
    }
  }

  @Test
  void anUnwiredCommissionerCommissionsNothingAndAsksNothing() throws IOException {
    try (StubIdpServer stub = new StubIdpServer()) {
      IdpRunnerCommissioner commissioner = IdpRunnerCommissionerFixture.pointedAt(stub.url());
      commissioner.clientSecret = Optional.of(" ");

      assertFalse(commissioner.enabled());
      assertThrows(
          IdpRunnerCommissioner.CommissionFailedException.class,
          () -> commissioner.runnerClient(RUNNER));
      commissioner.decommissionClient("dr-1");
      assertFalse(commissioner.deleteToken("t-1"));
      assertEquals(Optional.empty(), commissioner.liveTokens());
      assertEquals(Optional.empty(), commissioner.liveRunnerClients());
      assertTrue(stub.received().isEmpty());
    }
  }

  @Test
  void giveBacksAreOneAttemptAndA404IsGone() throws IOException {
    try (StubIdpServer stub = new StubIdpServer()) {
      stub.answering(404, "").answering(204, "").answering(500, "{}");
      IdpRunnerCommissioner commissioner = IdpRunnerCommissionerFixture.pointedAt(stub.url());

      assertTrue(commissioner.deleteToken("t-1"), "404 is the state a delete asks for");
      assertTrue(commissioner.deleteToken("t-2"));
      assertFalse(commissioner.deleteToken("t-3"), "a 500 leaves it for the reconcile");

      List<StubIdpServer.Received> requests = stub.received();
      assertEquals("DELETE", requests.get(0).method());
      assertEquals("/idp/api/tokens/t-1", requests.get(0).path());
      assertEquals(3, requests.size());
    }
  }

  @Test
  void decommissioningAClientNeverThrows() throws IOException {
    try (StubIdpServer stub = new StubIdpServer()) {
      stub.answering(500, "{}");
      IdpRunnerCommissioner commissioner = IdpRunnerCommissionerFixture.pointedAt(stub.url());

      commissioner.decommissionClient("dr-1");

      assertEquals("DELETE", stub.received().get(0).method());
      assertEquals("/idp/api/clients/dr-1", stub.received().get(0).path());
    }
    // Nothing answering at all is no throw either.
    IdpRunnerCommissionerFixture.pointedAt("http://127.0.0.1:1/idp").decommissionClient("dr-1");
  }

  @Test
  void theListingsAreFilteredToTheirKindAndAFailedOneIsEmpty() throws IOException {
    try (StubIdpServer stub = new StubIdpServer()) {
      stub.answering(
              200,
              "[{\"clientId\":\"dr-1\",\"contextKind\":\"desk-runner\",\"contextId\":\"r-1\"},"
                  + "{\"clientId\":\"a-1\",\"contextKind\":\"agent-container\",\"contextId\":\"p\"}]")
          .answering(
              200,
              "[{\"tokenId\":\"t-1\",\"contextKind\":\"desk-runner-registration\","
                  + "\"contextId\":\"r-1\",\"createdAt\":\"2026-10-08T10:00:00Z\"}]")
          .answering(500, "{}");
      IdpRunnerCommissioner commissioner = IdpRunnerCommissionerFixture.pointedAt(stub.url());

      assertEquals(
          Optional.of(List.of(new IdpRunnerCommissioner.LiveClient("dr-1", "desk-runner", "r-1"))),
          commissioner.liveRunnerClients());
      assertEquals(
          Optional.of(
              List.of(
                  new IdpTokens.LiveToken(
                      "t-1",
                      "desk-runner-registration",
                      "r-1",
                      Instant.parse("2026-10-08T10:00:00Z")))),
          commissioner.liveTokens());
      assertEquals(Optional.empty(), commissioner.liveTokens(), "a failed listing reaps nothing");
    }
  }
}
