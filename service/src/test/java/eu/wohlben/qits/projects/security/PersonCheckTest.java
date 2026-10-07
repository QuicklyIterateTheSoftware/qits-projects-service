package eu.wohlben.qits.projects.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.runtime.LaunchMode;
import io.quarkus.security.identity.SecurityIdentity;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Who counts as a person (qits-891), decided without HTTP: the identity the mechanism produced, the
 * cookie the request carried, and the launch mode.
 *
 * <p>Plain JUnit, with tokens hand-made the way {@link AgentTokens} makes them: by the time this
 * check runs quarkus-oidc has already validated the signature and audience, so what is pinned here
 * is which <em>validated</em> tokens are a person. The doors that call it are driven over HTTP in
 * {@code ReleaseRequestApprovalDoorTest} and {@code WorkCampaignApiTest}.
 */
class PersonCheckTest {

  private final PersonCheck check = new PersonCheck();

  PersonCheckTest() {
    check.sessions = new FakeSessionIntrospection();
  }

  // --- the two proofs ---------------------------------------------------------------------------

  @Test
  void aSessionIdpSaysIsAnAdminIsAPersonAndNamesTheDecision() {
    SecurityIdentity forwarded = AgentTokens.forwarded("qits:admin");

    assertEquals(
        Optional.of("ada"),
        check.verify(forwarded, FakeSessionIntrospection.admin("ada"), LaunchMode.NORMAL),
        "the name is the session's, not the forwarded principal's ('someone')");
  }

  @Test
  void aPersonsCliTokenHoldingAdminIsAPerson() {
    assertEquals(
        Optional.of("user-7"),
        check.verify(cli(Map.of(), "qits:admin"), null, LaunchMode.NORMAL),
        "the name is the token's principal");
  }

  /**
   * {@code qits:admin-agent} is admitted here too (qits-628 follow-up), stated explicitly on the
   * same terms as {@code qits:admin} — even though, in practice, nothing commissioned with
   * {@code context_kind} ever reaches this path (see {@link #anAgentOrWorkspaceTokenIsNotAPerson}).
   * A session naming it is still the proof this method accepts.
   */
  @Test
  void aSessionIdpSaysHoldsAdminAgentIsAPerson() {
    SecurityIdentity forwarded = AgentTokens.forwarded("qits:admin-agent");

    assertEquals(
        Optional.of("ada"),
        check.verify(forwarded, FakeSessionIntrospection.cookie("ada", "qits:admin-agent"), LaunchMode.NORMAL));
  }

  // --- everything else ----------------------------------------------------------------------------

  @Test
  void assertedHeadersAloneAreNotAPerson() {
    assertEquals(
        Optional.empty(),
        check.verify(AgentTokens.forwarded("qits:admin"), null, LaunchMode.NORMAL));
  }

  @Test
  void aSessionWithoutAdminOrUnknownToIdpIsNotAPerson() {
    SecurityIdentity forwarded = AgentTokens.forwarded("qits:admin");

    assertEquals(
        Optional.empty(),
        check.verify(
            forwarded, FakeSessionIntrospection.cookie("bob", "qits:agent"), LaunchMode.NORMAL));
    assertEquals(
        Optional.empty(), check.verify(forwarded, "a-value-idp-never-issued", LaunchMode.NORMAL));
  }

  @Test
  void aServiceClientTokenIsNotAPersonWhateverItHolds() {
    assertEquals(
        Optional.empty(),
        check.verify(
            token(
                Map.of(
                    "sub", "dev-qits-ci",
                    "groups", List.of("qits:system", "qits:admin", "clients/dev-qits-ci"))),
            null,
            LaunchMode.NORMAL));
  }

  /** Even a {@code cli} token: a {@code clients/…} group means a machine minted it. */
  @Test
  void aCliTokenCarryingAClientGroupIsNotAPerson() {
    assertEquals(
        Optional.empty(),
        check.verify(cli(Map.of(), "qits:admin", "clients/dev-qits-ci"), null, LaunchMode.NORMAL));
  }

  @Test
  void anAgentOrWorkspaceTokenIsNotAPerson() {
    assertEquals(
        Optional.empty(),
        check.verify(
            cli(Map.of("context_kind", "agent-container"), "qits:admin"), null, LaunchMode.NORMAL));
  }

  @Test
  void aWorkstationTokenIsNotAPerson() {
    assertEquals(
        Optional.empty(),
        check.verify(
            token(
                Map.of(
                    "sub", "user-7",
                    "credential_type", "workstation",
                    "groups", List.of("qits:git:external", "qits:admin"))),
            null,
            LaunchMode.NORMAL));
  }

  @Test
  void aCliTokenWithoutAdminIsNotAPerson() {
    assertEquals(
        Optional.empty(), check.verify(cli(Map.of(), "qits:agent"), null, LaunchMode.NORMAL));
  }

  /**
   * A bearer is the credential when there is one: a machine's token with an admin's cookie riding
   * along is still the machine.
   */
  @Test
  void aCookieBesideAMachineTokenDoesNotMakeItAPerson() {
    SecurityIdentity machine =
        token(Map.of("sub", "dev-qits-ci", "groups", List.of("qits:system", "clients/dev-qits-ci")));

    assertEquals(
        Optional.empty(),
        check.verify(machine, FakeSessionIntrospection.admin("ada"), LaunchMode.NORMAL));
  }

  // --- the dev-mode fallback --------------------------------------------------------------------

  @Test
  void aForwardedAdminCountsInDevModeOnly() {
    SecurityIdentity forwarded = AgentTokens.forwarded("qits:admin");

    assertEquals(Optional.of("someone"), check.verify(forwarded, null, LaunchMode.DEVELOPMENT));
    assertEquals(Optional.empty(), check.verify(forwarded, null, LaunchMode.TEST));
    assertEquals(Optional.empty(), check.verify(forwarded, null, LaunchMode.NORMAL));
    assertEquals(
        Optional.empty(),
        check.verify(AgentTokens.forwarded("qits:agent"), null, LaunchMode.DEVELOPMENT),
        "and only one holding qits:admin");
  }

  // --- tokens -----------------------------------------------------------------------------------

  /** A person's CLI token as qits-idp mints it, plus {@code extra}, holding {@code groups}. */
  private static SecurityIdentity cli(Map<String, Object> extra, String... groups) {
    Map<String, Object> claims = new HashMap<>(extra);
    claims.put("sub", "user-7");
    claims.put("credential_type", "cli");
    claims.put("groups", List.of(groups));
    return token(claims);
  }

  /** A validated token with these claims; the identity's roles mirror its groups. */
  @SuppressWarnings("unchecked")
  private static SecurityIdentity token(Map<String, Object> claims) {
    List<String> groups = (List<String>) claims.getOrDefault("groups", List.of());
    return AgentTokens.token(claims, groups.toArray(String[]::new));
  }
}
