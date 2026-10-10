package eu.wohlben.qits.projects.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.json.Json;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Who is bound as an agent, and what its token covers. */
class AgentAccessTest {

  private static final String AGENT = AgentAccess.AGENT_ROLE;

  @Test
  void onlyACallerWithTheAgentRoleAndNoWiderRoleIsBound() {
    assertTrue(AgentAccess.isBoundAgent(AgentTokens.token(Map.of(), AGENT), "qits:system"));
    assertFalse(
        AgentAccess.isBoundAgent(AgentTokens.token(Map.of(), AGENT, "qits:system"), "qits:system"),
        "a caller that also holds a wider role is judged as before");
    assertFalse(AgentAccess.isBoundAgent(AgentTokens.token(Map.of(), "qits:system"), "qits:system"));
    assertTrue(
        AgentAccess.isBoundAgent(AgentTokens.forwarded(AGENT), "qits:system"),
        "the role from a forwarded header binds too");
  }

  @Test
  void theProjectClaimCoversItsOwnProjectOnly() {
    SecurityIdentity agent = AgentTokens.token(Map.of("project", "p-1"), AGENT);

    assertTrue(AgentAccess.coversProject(agent, "p-1"));
    assertFalse(AgentAccess.coversProject(agent, "p-2"));
    assertFalse(AgentAccess.coversProject(agent, null));
  }

  @Test
  void aCallerWithNoTokenCoversNothing() {
    SecurityIdentity forwarded = AgentTokens.forwarded(AGENT);

    assertFalse(AgentAccess.coversProject(forwarded, "p-1"));
    assertFalse(AgentAccess.coversBranch(forwarded, "ticket/x"));
    assertFalse(AgentAccess.isClient(forwarded, "someone"));
  }

  @Test
  void gitRefsCoverExactRefsAndTrailingPatterns() {
    SecurityIdentity agent =
        AgentTokens.token(
            Map.of("git_refs", List.of("refs/heads/ticket/own", "refs/heads/external/*")), AGENT);

    assertTrue(AgentAccess.coversBranch(agent, "ticket/own"));
    assertFalse(AgentAccess.coversBranch(agent, "ticket/own-2"), "exact means exact");
    assertFalse(AgentAccess.coversBranch(agent, "ticket/other"));
    assertTrue(AgentAccess.coversBranch(agent, "external/idea"));
    assertTrue(AgentAccess.coversBranch(agent, "external/deep/idea"));
    assertFalse(AgentAccess.coversBranch(agent, "externals/idea"), "a pattern is a path prefix");
    assertFalse(AgentAccess.coversBranch(agent, "main"));
    assertFalse(AgentAccess.coversBranch(agent, " "));
  }

  /** An agent's scope (qits-1152): its wrapper branch, and every name that branch starts. */
  @Test
  void aTrailingStarCoversWhatStartsWithWhatPrecedesIt() {
    SecurityIdentity agent =
        AgentTokens.token(
            Map.of(
                "git_refs",
                List.of("refs/heads/ticket/qits-1002", "refs/heads/ticket/qits-1002-*")),
            AGENT);

    assertTrue(AgentAccess.coversBranch(agent, "ticket/qits-1002"));
    assertTrue(AgentAccess.coversBranch(agent, "ticket/qits-1002-fix-totals"));
    assertFalse(AgentAccess.coversBranch(agent, "ticket/qits-1002-"), "the star matches something");
    assertFalse(AgentAccess.coversBranch(agent, "ticket/qits-10020"));
    assertFalse(AgentAccess.coversBranch(agent, "ticket/qits-1003-fix"));
  }

  @Test
  void aStarThatIsNotLastCoversNothing() {
    SecurityIdentity agent =
        AgentTokens.token(
            Map.of("git_refs", List.of("refs/heads/*/own", "refs/heads/ticket/**")), AGENT);

    assertFalse(AgentAccess.coversBranch(agent, "ticket/own"));
    assertFalse(AgentAccess.coversBranch(agent, "ticket/x"));
    assertFalse(AgentAccess.coversBranch(agent, "*/own"));
  }

  /** What quarkus-oidc hands back for a JSON array claim. */
  @Test
  void gitRefsAreReadFromAJsonArray() {
    SecurityIdentity agent =
        AgentTokens.token(
            Map.of("git_refs", Json.createArrayBuilder().add("refs/heads/epic/plan").build()),
            AGENT);

    assertTrue(AgentAccess.coversBranch(agent, "epic/plan"));
  }

  @Test
  void noGitRefsClaimAndAnEmptyOneCoverNothing() {
    assertFalse(AgentAccess.coversBranch(AgentTokens.token(Map.of(), AGENT), "ticket/x"));
    assertFalse(
        AgentAccess.coversBranch(AgentTokens.token(Map.of("git_refs", List.of()), AGENT), "ticket/x"));
  }

  @Test
  void theSubjectNamesTheCommissionedClient() {
    SecurityIdentity agent = AgentTokens.token(Map.of("sub", "dyn-refinement-7"), AGENT);

    assertTrue(AgentAccess.isClient(agent, "dyn-refinement-7"));
    assertFalse(AgentAccess.isClient(agent, "dyn-refinement-8"));
    assertFalse(AgentAccess.isClient(agent, null), "a row with no commission admits no agent");
  }
}
