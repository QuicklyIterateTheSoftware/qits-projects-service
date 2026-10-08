package eu.wohlben.qits.projects.agenthost;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.security.AgentTokens;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The dial-back needs a bearer, and a desk's token must be that desk's (qits-767). */
class DaemonStreamRouteTest {

  @Test
  void anAnonymousDialBackIsRefused() {
    assertFalse(DaemonStreamRoute.admits(null, "p-1", p -> "tok-mine"));
    assertFalse(
        DaemonStreamRoute.admits(
            QuarkusSecurityIdentity.builder().setAnonymous(true).build(), "p-1", p -> "tok-mine"));
  }

  @Test
  void theDesksOwnTokenIsAdmitted() {
    assertTrue(
        DaemonStreamRoute.admits(
            AgentTokens.token(Map.of("sub", "tok-mine", "project", "p-1"), "qits:agent"),
            "p-1",
            p -> "tok-mine"));
  }

  @Test
  void anotherDesksTokenIsRefused() {
    assertFalse(
        DaemonStreamRoute.admits(
            AgentTokens.token(Map.of("sub", "tok-other", "project", "p-1"), "qits:agent"),
            "p-1",
            p -> "tok-mine"));
    assertFalse(
        DaemonStreamRoute.admits(
            AgentTokens.token(Map.of("sub", "tok-mine"), "qits:agent"), "p-2", p -> null));
  }

  @Test
  void anAuthenticatedCallerThatIsNotADeskIsAdmittedOnTheNonce() {
    assertTrue(
        DaemonStreamRoute.admits(
            AgentTokens.token(Map.of("sub", "dev-qits-projects"), "qits:system"), "p-1", p -> null));
    assertTrue(DaemonStreamRoute.admits(AgentTokens.forwarded("qits:admin"), "p-1", p -> null));
  }
}
