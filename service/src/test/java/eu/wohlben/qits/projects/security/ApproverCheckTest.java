package eu.wohlben.qits.projects.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import eu.wohlben.qits.projects.control.approval.ApprovalRole;
import eu.wohlben.qits.projects.control.approval.Approver;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.security.identity.SecurityIdentity;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * <b>Which approval roles a caller holds</b> (epic qits-1038): {@link ApproverCheck}'s decision with
 * the request's parts handed in, {@link PersonCheckTest}'s idiom. A person is {@code person} under
 * the proof's name; an agent's own workspace token is {@code agent} of its project under its {@code
 * sub}; nothing else — headers, service clients, {@code qits:system}, other context kinds — holds a
 * role.
 */
class ApproverCheckTest {

  private final ApproverCheck check = new ApproverCheck();

  ApproverCheckTest() {
    PersonCheck persons = new PersonCheck();
    persons.sessions = new FakeSessionIntrospection();
    check.persons = persons;
  }

  @Test
  void aVerifiedPersonHoldsPerson() {
    assertEquals(
        Optional.of(Approver.person("ada")),
        check.decide(
            AgentTokens.forwarded("qits:admin"),
            FakeSessionIntrospection.admin("ada"),
            LaunchMode.NORMAL));
    Map<String, Object> cli = new HashMap<>();
    cli.put("sub", "user-7");
    cli.put("credential_type", "cli");
    cli.put("groups", List.of("qits:admin"));
    assertEquals(
        Optional.of(Approver.person("user-7")),
        check.decide(AgentTokens.token(cli, "qits:admin"), null, LaunchMode.NORMAL));
  }

  @Test
  void anAgentsWorkspaceTokenHoldsAgentOfItsProject() {
    for (String kind : List.of("workspace", "workspace-admin")) {
      assertEquals(
          Optional.of(Approver.agent("agent-client-1", "project-a")),
          check.decide(agent(kind, "project-a", "qits:agent"), null, LaunchMode.NORMAL),
          kind);
    }
  }

  /**
   * qits-idp mints {@code clients/<client id>} into the groups of every client token, a
   * commissioned agent's included: its OWN self-role is not a service client's mark. Anybody
   * else's still refuses.
   */
  @Test
  void anAgentsOwnClientGroupIsAllowedAndAnotherIsNot() {
    assertEquals(
        Optional.of(Approver.agent("agent-client-1", "project-a")),
        check.decide(
            agent("workspace", "project-a", "qits:agent", "clients/agent-client-1"),
            null,
            LaunchMode.NORMAL));
    assertEquals(
        Optional.empty(),
        check.decide(
            agent("workspace", "project-a", "qits:agent", "clients/agent-client-1", "clients/other"),
            null,
            LaunchMode.NORMAL));
  }

  /**
   * The agent is named by its principal — what {@code addedBy} records for it on every other door
   * — and keeps its {@code sub} as an alias, so an excluded author is excluded under either name.
   */
  @Test
  void anAgentIsNamedByItsPrincipalWithItsSubAsAnAlias() {
    Map<String, Object> claims = new HashMap<>();
    claims.put("sub", "agent-client-1");
    claims.put("preferred_username", "workspace-agent-a");
    claims.put("context_kind", "workspace");
    claims.put("project", "project-a");
    claims.put("groups", List.of("qits:agent", "clients/agent-client-1"));
    Approver approver =
        check.decide(AgentTokens.token(claims, "qits:agent"), null, LaunchMode.NORMAL).orElseThrow();
    assertEquals("workspace-agent-a", approver.name());
    assertEquals(java.util.Set.of("workspace-agent-a", "agent-client-1"), approver.names());
  }

  @Test
  void anAgentTokenOfAnotherContextKindOrWithoutAProjectHoldsNothing() {
    assertEquals(
        Optional.empty(),
        check.decide(agent("agent-container", "project-a", "qits:agent"), null, LaunchMode.NORMAL));
    assertEquals(
        Optional.empty(),
        check.decide(agent("workspace", null, "qits:agent"), null, LaunchMode.NORMAL));
    assertEquals(
        Optional.empty(),
        check.decide(agent("workspace", "project-a", "qits:system"), null, LaunchMode.NORMAL),
        "no qits:agent in its groups");
  }

  @Test
  void aServiceClientHoldsNothingEvenWithAgentClaims() {
    assertEquals(
        Optional.empty(),
        check.decide(
            agent("workspace", "project-a", "qits:agent", "clients/dev-qits-ci"),
            null,
            LaunchMode.NORMAL));
    Map<String, Object> claims = new HashMap<>();
    claims.put("sub", "dev-qits-maintenance");
    claims.put("groups", List.of("qits:system", "clients/dev-qits-maintenance"));
    assertEquals(
        Optional.empty(),
        check.decide(AgentTokens.token(claims, "qits:system"), null, LaunchMode.NORMAL));
  }

  // ---- the delivery desk (epic qits-1440) --------------------------------------------------------

  /**
   * The desk's delivery token — {@code qits:agent} and {@code qits:desk}, {@code desk-delivery},
   * its own {@code clients/<sub>} and a project — holds {@code desk} and {@code agent} of that
   * project, never {@code person}, under its {@code sub}.
   */
  @Test
  void theDeliveryDesksTokenHoldsDeskAndAgentOfItsProjectUnderItsSub() {
    Approver desk =
        check
            .decide(
                agent(
                    "desk-delivery",
                    "project-a",
                    "qits:agent",
                    "qits:desk",
                    "clients/agent-client-1"),
                null,
                LaunchMode.NORMAL)
            .orElseThrow();
    assertEquals("agent-client-1", desk.name());
    assertEquals(
        List.of(ApprovalRole.deskOf("project-a")), desk.held());
    assertEquals("project-a", desk.deskProject());
    assertFalse(desk.held().contains(ApprovalRole.PERSON));
  }

  @Test
  void aDeskTokenMissingARoleAProjectOrItsKindIsNoDesk() {
    assertEquals(
        Optional.empty(),
        check.decide(agent("desk-delivery", "project-a", "qits:agent"), null, LaunchMode.NORMAL),
        "no qits:desk: an agent's roles under a kind no agent is minted with");
    assertEquals(
        Optional.empty(),
        check.decide(agent("desk-delivery", "project-a", "qits:desk"), null, LaunchMode.NORMAL),
        "no qits:agent");
    assertEquals(
        Optional.empty(),
        check.decide(
            agent("desk-delivery", null, "qits:agent", "qits:desk"), null, LaunchMode.NORMAL),
        "no project");
    assertEquals(
        Optional.of(Approver.agent("agent-client-1", "project-a")),
        check.decide(
            agent("workspace", "project-a", "qits:agent", "qits:desk"), null, LaunchMode.NORMAL),
        "qits:desk on a workspace token is only an agent");
    assertEquals(
        Optional.empty(),
        check.decide(
            agent("desk-delivery", "project-a", "qits:agent", "qits:desk", "clients/other"),
            null,
            LaunchMode.NORMAL),
        "another client's group is a service client's mark");
  }

  @Test
  void assertedHeadersHoldNothing() {
    assertEquals(
        Optional.empty(),
        check.decide(AgentTokens.forwarded("qits:admin", "qits:agent"), null, LaunchMode.NORMAL));
  }

  /** An agent's token as qits-workspaces commissions it, holding {@code groups}. */
  private static SecurityIdentity agent(String contextKind, String project, String... groups) {
    Map<String, Object> claims = new HashMap<>();
    claims.put("sub", "agent-client-1");
    claims.put("context_kind", contextKind);
    if (project != null) {
      claims.put("project", project);
    }
    claims.put("groups", List.of(groups));
    return AgentTokens.token(claims, groups);
  }
}
