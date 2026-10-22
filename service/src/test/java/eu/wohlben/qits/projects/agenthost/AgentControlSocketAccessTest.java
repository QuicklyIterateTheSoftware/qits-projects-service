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
            return "deskId".equals(name) ? projectId : null;
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

  // --- a front desk's own token (qits-767) --------------------------------------------------------

  private static SecurityIdentity deskToken(String subject, String project) {
    return AgentTokens.token(Map.of("sub", subject, "project", project), "qits:agent");
  }

  @Test
  void aDesksTokenOpensItsOwnDesksSocket() {
    assertTrue(
        AgentControlSocketAccess.decide(deskToken("tok-mine", "p-1"), "p-1", p -> "tok-mine")
            .isUpgradePermitted());
  }

  @Test
  void anotherDesksTokenIsRefusedWith403EvenForTheRightProject() {
    HttpUpgradeCheck.CheckResult result =
        AgentControlSocketAccess.decide(deskToken("tok-other", "p-1"), "p-1", p -> "tok-mine");
    assertFalse(result.isUpgradePermitted());
    assertEquals(403, result.getHttpResponseCode());
  }

  @Test
  void aDeskTokenWithNoDeskIsRefused() {
    assertFalse(
        AgentControlSocketAccess.decide(deskToken("tok-revoked", "p-1"), "p-1", p -> null)
            .isUpgradePermitted());
    assertFalse(
        upgrade(deskToken("tok-unbound", "p-1"), "p-1").isUpgradePermitted(),
        "a check with no desks to ask refuses every tok- subject");
  }

  @Test
  void anAgentTokenThatIsNotADesksIsJudgedAsBefore() {
    assertTrue(
        AgentControlSocketAccess.decide(
                AgentTokens.token(Map.of("sub", "dyn-client", "project", "p-1"), "qits:agent"),
                "p-1",
                p -> "tok-mine")
            .isUpgradePermitted());
  }

  // --- desks, not projects (qits-1482) -------------------------------------------------------------

  /** An agent of the project opens its DELIVERY desk's socket: the desk id names the project. */
  @Test
  void aDeliveryDesksSocketIsItsProjects() {
    SecurityIdentity agent = AgentTokens.token(Map.of("project", "p-1"), "qits:agent");

    assertTrue(upgrade(agent, "p-1-delivery").isUpgradePermitted());
    assertEquals(403, upgrade(agent, "p-2-delivery").getHttpResponseCode());
  }

  /**
   * The DELIVERY desk's own token opens that desk and not the front desk; the front desk's token
   * opens the front desk and not the DELIVERY desk — the bound subject is the desk's.
   */
  @Test
  void eachDesksTokenOpensItsOwnDeskOnly() {
    java.util.function.Function<String, String> bound =
        desk -> desk.equals("p-1") ? "tok-front" : desk.equals("p-1-delivery") ? "tok-loop" : null;

    assertTrue(
        AgentControlSocketAccess.decide(deskToken("tok-loop", "p-1"), "p-1-delivery", bound)
            .isUpgradePermitted());
    assertEquals(
        403,
        AgentControlSocketAccess.decide(deskToken("tok-loop", "p-1"), "p-1", bound)
            .getHttpResponseCode());
    assertEquals(
        403,
        AgentControlSocketAccess.decide(deskToken("tok-front", "p-1"), "p-1-delivery", bound)
            .getHttpResponseCode());
  }
}
