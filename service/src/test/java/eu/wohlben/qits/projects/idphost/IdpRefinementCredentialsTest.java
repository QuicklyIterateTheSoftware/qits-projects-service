package eu.wohlben.qits.projects.idphost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.projects.agenthost.AgentCredentialException;
import eu.wohlben.qits.projects.refinementhost.RefinementCredentials;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * What goes on the wire when a refinement container is commissioned: which Git refs it states,
 * and what happens when qits-idp refuses them. Plain JUnit against a stub, like {@link
 * IdpAgentCredentialsTest}.
 */
class IdpRefinementCredentialsTest {

  private static final long REFINEMENT = 42L;

  private static final String PROJECT = "11111111-2222-3333-4444-555555555555";

  private static final List<String> BRANCH = List.of("refs/heads/refining/sharper-onboarding");

  private static final String COMMISSIONED =
      "{\"clientId\":\"dev-qits-projects-refinement-42\",\"secret\":\"s3cr3t\",\"owner\":"
          + "\"dev-qits-projects\",\"contextKind\":\"refinement\",\"contextId\":\"42\","
          + "\"createdAt\":\"2026-09-12T10:00:00Z\"}";

  private static IdpRefinementCredentials credentials(String authServerUrl) {
    IdpRefinementCredentials credentials = new IdpRefinementCredentials();
    credentials.objectMapper = new ObjectMapper();
    credentials.tokensEnabled = true;
    credentials.authServerUrl = authServerUrl;
    credentials.clientId = "dev-qits-projects";
    credentials.clientSecret = Optional.of("own-secret");
    credentials.requestTimeout = Duration.ofSeconds(2);
    return credentials;
  }

  private static Map<?, ?> json(String body) throws IOException {
    return new ObjectMapper().readValue(body, Map.class);
  }

  @Test
  void aCommissionStatesTheRefinementsOwnBranch() throws IOException {
    try (StubIdpServer stub = new StubIdpServer()) {
      stub.answering(201, COMMISSIONED);

      RefinementCredentials.Commissioned pair =
          credentials(stub.url()).commission(REFINEMENT, PROJECT, BRANCH);

      assertEquals("dev-qits-projects-refinement-42", pair.clientId());
      assertEquals(1, stub.received().size());
      Map<?, ?> body = json(stub.received().get(0).body());
      assertEquals("refinement", body.get("contextKind"));
      assertEquals("42", body.get("contextId"));
      assertEquals(Map.of("project", PROJECT), body.get("claims"));
      assertEquals(BRANCH, body.get("gitRefs"), "exactly the branch the daemon auto-pushes to");
    }
  }

  /**
   * Fail closed. A 400 to a stated list comes from an idp that read it and refused it. The
   * commission is sent again with gitRefs [] — push nothing — and never without gitRefs, which
   * would let the credential push anything. An ERROR names the refinement and the reason.
   */
  @Test
  void aRefusedListIsCommissionedAgainAsPushNothing() throws IOException {
    try (StubIdpServer stub = new StubIdpServer();
        CapturedErrors errors = new CapturedErrors(IdpRefinementCredentials.class)) {
      stub.answering(400, "{\"error\":\"gitRefs holds more than 500 entries\"}")
          .answering(201, COMMISSIONED);

      RefinementCredentials.Commissioned pair =
          credentials(stub.url()).commission(REFINEMENT, PROJECT, BRANCH);

      assertEquals("dev-qits-projects-refinement-42", pair.clientId());
      List<StubIdpServer.Received> requests = stub.received();
      assertEquals(2, requests.size());
      assertEquals(BRANCH, json(requests.get(0).body()).get("gitRefs"));
      Map<?, ?> second = json(requests.get(1).body());
      assertTrue(second.containsKey("gitRefs"), "never unscoped: " + requests.get(1).body());
      assertEquals(List.of(), second.get("gitRefs"), "push nothing: " + requests.get(1).body());
      assertEquals(
          Map.of("project", PROJECT),
          second.get("claims"),
          "only the refs change: " + requests.get(1).body());

      assertEquals(1, errors.messages().size(), errors.messages().toString());
      String error = errors.messages().get(0);
      assertTrue(error.contains("refinement " + REFINEMENT), "the error names the context: " + error);
      assertTrue(
          error.contains("gitRefs holds more than 500 entries"),
          "the error names the reason: " + error);
    }
  }

  /** A 400 to the push-nothing commission too is about the request, and is thrown. */
  @Test
  void aSecondRefusalIsThrown() throws IOException {
    try (StubIdpServer stub = new StubIdpServer()) {
      stub.answering(400, "{\"error\":\"invalid_request\"}")
          .answering(400, "{\"error\":\"invalid_request\"}");

      AgentCredentialException refused =
          assertThrows(
              AgentCredentialException.class,
              () -> credentials(stub.url()).commission(REFINEMENT, PROJECT, BRANCH));

      assertFalse(refused.retryable());
      assertEquals(2, stub.received().size(), "asked twice, never a third time");
    }
  }

  /** No list is read as push nothing, so a 400 to it is not sent again. */
  @Test
  void noListIsStatedAsPushNothing() throws IOException {
    try (StubIdpServer stub = new StubIdpServer()) {
      stub.answering(400, "{\"error\":\"invalid_request\"}");

      assertThrows(
          AgentCredentialException.class,
          () -> credentials(stub.url()).commission(REFINEMENT, PROJECT, null));

      assertEquals(1, stub.received().size(), "the same request is not sent twice");
      assertEquals(List.of(), json(stub.received().get(0).body()).get("gitRefs"));
    }
  }
}
