package eu.wohlben.qits.projects.idphost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.projects.security.SessionIntrospection.Session;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * What goes on the wire to qits-idp's session introspection, and how each answer is read (qits-891).
 *
 * <p>Plain JUnit against {@link StubIdpServer}, like {@link IdpAgentCredentialsTest}: the door's
 * own behaviour is qits-idp's suite; what is pinned here is the request this service makes — its
 * own Basic pair, the value in the body — and that every "no" is empty rather than an error.
 */
class IdpSessionIntrospectionTest {

  private static final String VIEW =
      "{\"userId\":\"7\",\"username\":\"ada\",\"roles\":[\"qits:admin\",\"qits:system\"],"
          + "\"expiresAt\":\"2026-10-06T10:00:00Z\"}";

  private static IdpSessionIntrospection introspection(String authServerUrl) {
    IdpSessionIntrospection introspection = new IdpSessionIntrospection();
    introspection.objectMapper = new ObjectMapper();
    introspection.tokensEnabled = true;
    introspection.authServerUrl = authServerUrl;
    introspection.clientId = "dev-qits-projects";
    introspection.clientSecret = Optional.of("own-secret");
    return introspection;
  }

  @Test
  void aLiveSessionIsAskedWithThisServicesOwnPairAndReadAsUserAndRoles() throws IOException {
    try (StubIdpServer stub = new StubIdpServer()) {
      stub.answering(200, VIEW);

      Optional<Session> session = introspection(stub.url()).introspect("cookie-value");

      assertEquals(
          Optional.of(new Session("ada", List.of("qits:admin", "qits:system"))), session);
      StubIdpServer.Received request = stub.received().get(0);
      assertEquals("POST", request.method());
      assertEquals("/idp/api/sessions/introspect", request.path());
      assertEquals(
          "Basic "
              + Base64.getEncoder()
                  .encodeToString("dev-qits-projects:own-secret".getBytes(StandardCharsets.UTF_8)),
          request.authorization());
      assertEquals("{\"token\":\"cookie-value\"}", request.body(), "the value is in the body only");
    }
  }

  @Test
  void everyNoIsEmpty() throws IOException {
    try (StubIdpServer stub = new StubIdpServer()) {
      stub.answering(404, "{\"error\":\"not_found\"}")
          .answering(403, "{\"error\":\"access_denied\"}")
          .answering(200, "not json")
          .answering(200, "{\"roles\":[\"qits:admin\"]}");
      IdpSessionIntrospection introspection = introspection(stub.url());

      for (int i = 0; i < 4; i++) {
        assertEquals(Optional.empty(), introspection.introspect("cookie-value"), "answer " + i);
      }
    }
    // Nobody answering: the stub is closed.
    try (StubIdpServer stub = new StubIdpServer()) {
      String gone = stub.url();
      stub.close();
      assertEquals(Optional.empty(), introspection(gone).introspect("cookie-value"));
    }
  }

  @Test
  void withNoCredentialNothingIsDialled() throws IOException {
    try (StubIdpServer stub = new StubIdpServer()) {
      IdpSessionIntrospection off = introspection(stub.url());
      off.tokensEnabled = false;
      IdpSessionIntrospection noSecret = introspection(stub.url());
      noSecret.clientSecret = Optional.of(" ");

      assertFalse(off.introspect("cookie-value").isPresent());
      assertFalse(noSecret.introspect("cookie-value").isPresent());
      assertFalse(introspection(stub.url()).introspect("").isPresent());
      assertTrue(stub.received().isEmpty(), "the %dev/%test posture asks idp nothing");
    }
  }
}
