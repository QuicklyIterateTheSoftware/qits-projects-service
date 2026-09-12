package eu.wohlben.qits.projects.refinementhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.security.AgentTokens;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.websockets.next.HttpUpgradeCheck;
import io.quarkus.websockets.next.UserData;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.HttpServerRequest;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * An agent opens only the socket of the refinement its client was commissioned for; a platform
 * service is judged as before. The row lookup is replaced, so no database is needed.
 */
class RefinementControlSocketAccessTest {

  /** Refinement 7's container holds the client {@code dyn-refinement-7}; no other row exists. */
  private static final RefinementControlSocketAccess CHECK =
      new RefinementControlSocketAccess() {
        @Override
        String commissionedClientOf(String refinementId) {
          return "7".equals(refinementId) ? "dyn-refinement-7" : null;
        }
      };

  private static HttpUpgradeCheck.CheckResult upgrade(
      SecurityIdentity identity, String refinementId) {
    HttpUpgradeCheck.HttpUpgradeContext context =
        new HttpUpgradeCheck.HttpUpgradeContext() {
          @Override
          public UserData userData() {
            return null;
          }

          @Override
          public String pathParam(String name) {
            return "refinementId".equals(name) ? refinementId : null;
          }

          @Override
          public HttpServerRequest httpRequest() {
            return null;
          }

          @Override
          public Uni<SecurityIdentity> securityIdentity() {
            return Uni.createFrom().item(identity);
          }

          @Override
          public String endpointId() {
            return RefinementControlSocket.ENDPOINT_ID;
          }
        };
    return CHECK.perform(context).await().atMost(Duration.ofSeconds(5));
  }

  private static SecurityIdentity agentFor(String client) {
    return AgentTokens.token(Map.of("sub", client), "qits:agent");
  }

  @Test
  void theCommissionedClientOpensItsOwnRefinementsSocket() {
    assertTrue(upgrade(agentFor("dyn-refinement-7"), "7").isUpgradePermitted());
  }

  @Test
  void anotherClientIsRefusedWith403() {
    HttpUpgradeCheck.CheckResult result = upgrade(agentFor("dyn-refinement-8"), "7");

    assertFalse(result.isUpgradePermitted());
    assertEquals(403, result.getHttpResponseCode());
  }

  @Test
  void anUnknownRowOrOneWithNoCommissionIsRefused() {
    assertFalse(upgrade(agentFor("dyn-refinement-7"), "8").isUpgradePermitted());
    assertFalse(upgrade(agentFor("dyn-refinement-7"), "not-a-number").isUpgradePermitted());
    assertFalse(upgrade(AgentTokens.forwarded("qits:agent"), "7").isUpgradePermitted());
  }

  @Test
  void aPlatformServiceIsJudgedAsBefore() {
    assertTrue(upgrade(AgentTokens.token(Map.of(), "qits:system"), "8").isUpgradePermitted());
  }

  @Test
  void theCheckNamesThisSocketOnly() {
    assertTrue(CHECK.appliesTo(RefinementControlSocket.ENDPOINT_ID));
    assertFalse(CHECK.appliesTo("projects-agent-control-socket"));
  }
}
