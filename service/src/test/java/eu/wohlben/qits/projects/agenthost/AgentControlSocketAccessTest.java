package eu.wohlben.qits.projects.agenthost;

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

/** An agent opens only its own project's control socket; a platform service is judged as before. */
class AgentControlSocketAccessTest {

  private static HttpUpgradeCheck.CheckResult upgrade(SecurityIdentity identity, String projectId) {
    HttpUpgradeCheck.HttpUpgradeContext context =
        new HttpUpgradeCheck.HttpUpgradeContext() {
          @Override
          public UserData userData() {
            return null;
          }

          @Override
          public String pathParam(String name) {
            return "projectId".equals(name) ? projectId : null;
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
            return AgentControlSocket.ENDPOINT_ID;
          }
        };
    return new AgentControlSocketAccess().perform(context).await().atMost(Duration.ofSeconds(5));
  }

  @Test
  void anAgentOpensItsOwnProjectsSocket() {
    SecurityIdentity agent = AgentTokens.token(Map.of("project", "p-1"), "qits:agent");

    assertTrue(upgrade(agent, "p-1").isUpgradePermitted());
  }

  @Test
  void anAgentIsRefusedAnotherProjectsSocketWith403() {
    SecurityIdentity agent = AgentTokens.token(Map.of("project", "p-1"), "qits:agent");

    HttpUpgradeCheck.CheckResult result = upgrade(agent, "p-2");
    assertFalse(result.isUpgradePermitted());
    assertEquals(403, result.getHttpResponseCode());
  }

  @Test
  void anAgentRoleWithNoTokenIsRefused() {
    assertFalse(upgrade(AgentTokens.forwarded("qits:agent"), "p-1").isUpgradePermitted());
  }

  @Test
  void aPlatformServiceIsJudgedAsBefore() {
    assertTrue(upgrade(AgentTokens.token(Map.of(), "qits:system"), "any").isUpgradePermitted());
    assertTrue(
        upgrade(AgentTokens.token(Map.of("project", "p-1"), "qits:system", "qits:agent"), "p-2")
            .isUpgradePermitted(),
        "a caller that also holds qits:system is not bound");
  }

  @Test
  void theCheckNamesThisSocketOnly() {
    AgentControlSocketAccess check = new AgentControlSocketAccess();

    assertTrue(check.appliesTo(AgentControlSocket.ENDPOINT_ID));
    assertFalse(check.appliesTo("projects-refinement-control-socket"));
  }
}
