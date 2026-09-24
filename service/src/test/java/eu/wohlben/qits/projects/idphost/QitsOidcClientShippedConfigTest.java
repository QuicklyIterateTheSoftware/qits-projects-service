package eu.wohlben.qits.projects.idphost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import java.util.Optional;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

/**
 * The one named oidc client, {@code qits}, as the shipped {@code application.properties} resolves
 * it with no {@code QITS_RESOURCE_IDP_*} or old extras env set — the "nothing configured" arm every
 * clone-alone build and every other test in this repo runs on (service-client-identity-plan.md, C4).
 *
 * <p>{@link QitsOidcClientOldExtrasFallbackTest} and {@link
 * QitsOidcClientResourceOverridesOldExtrasTest} hold the other two arms — the old extras keys alone,
 * and the new resource keys winning over them — each in its own {@code @QuarkusTest} because a
 * {@code @TestProfile}'s config overrides are fixed for the life of one boot.
 */
@QuarkusTest
class QitsOidcClientShippedConfigTest {

  private static String value(String key) {
    Config config = ConfigProvider.getConfig();
    return config.getValue(key, String.class);
  }

  @Test
  void theQitsClientResolvesItsOwnLiteralDefaults() {
    // Derived from QITS_ENVIRONMENT, unset here and so defaulting to "dev" — qits-platform-idp is
    // one of the nine platform services and the bare alias resolves to nothing on a real estate.
    assertEquals(
        "http://dev-qits-platform-idp:8080/idp", value("quarkus.oidc-client.qits.auth-server-url"));
    assertEquals("qits-projects", value("quarkus.oidc-client.qits.client-id"));
    // Empty, not absent — SmallRye reads a configured-empty String as null, so an empty secret
    // reads as an empty Optional rather than as "" itself.
    Optional<String> secret =
        ConfigProvider.getConfig()
            .getOptionalValue("quarkus.oidc-client.qits.credentials.secret", String.class);
    assertTrue(secret.isEmpty());
    // One audience for every outbound call now, never qits-containers, qits-githost, qits-ci,
    // qits-workspaces or qits-platform-maintenance specifically.
    assertEquals("qits-platform", value("quarkus.oidc-client.qits.grant-options.client.audience"));
  }

  @Test
  void theClientStaysDisabledUnderTest() {
    // %test.quarkus.oidc-client.qits.client-enabled=false wins over the shipped expression
    // regardless of what QUARKUS_OIDC_CLIENT_CLIENT_ENABLED says — the arm every test in this repo
    // is on, so a suite never dials a real idp.
    assertEquals("false", value("quarkus.oidc-client.qits.client-enabled"));
  }

  @Test
  void theFourNamesTheDeploymentStillMintsAreNeutralised() {
    // These are not dead stubs and a cleanup must not take them out again: the deployed environment
    // carries QUARKUS_OIDC_CLIENT_{CI,GITHOST,MAINTENANCE,WORKSPACES}_* entries, one variable mints
    // the map key, and `client-enabled`/`discovery-enabled` both default to TRUE — an enabled client
    // dials qits-idp during runtime init, before the HTTP listener accepts. `discovery-enabled` is
    // what actually stops that (the env's `..._CLIENT_ENABLED=true` outranks this file), and
    // `token-path` is mandatory beside it or the metadata has no token endpoint and the boot fails.
    // QitsOidcClientEnvMintedNamesTest measures both of those against the shipped file.
    for (String name : new String[] {"ci", "githost", "maintenance", "workspaces"}) {
      String prefix = "quarkus.oidc-client." + name + ".";
      assertEquals("false", value(prefix + "client-enabled"), name);
      assertEquals("false", value(prefix + "discovery-enabled"), name);
      assertEquals("token", value(prefix + "token-path"), name);
      // Every one of these clients dials the same idp the named `qits` client does — derived the
      // same way, so a deployment no longer states QUARKUS_OIDC_CLIENT_<NAME>_AUTH_SERVER_URL for
      // any of the four to reach it.
      assertEquals("http://dev-qits-platform-idp:8080/idp", value(prefix + "auth-server-url"), name);
    }
  }

  @Test
  void aNameThisFileDoesNotMentionIsENABLEDandDISCOVERING() {
    // The premise of the block above, pinned rather than argued, and measured through the real
    // Quarkus config stack because only that carries the mapping's recorded defaults. The map key
    // `quarkus.oidc-client.<name>` matches a STAR name, so ANY name — one an environment variable
    // mints, and one nothing mints at all — answers `true` to `client-enabled` unless this file says
    // otherwise. An undeclared name is used so that restoring or retiring a real one cannot make
    // this pass or fail for the wrong reason.
    assertEquals("true", value("quarkus.oidc-client.nosuchclient.client-enabled"));
    // `discovery-enabled` has NO recorded default, because OidcClientConfig types it as an Optional
    // and OidcClientRecorder applies `discoveryEnabled().orElse(true)` itself. Unset therefore still
    // means DISCOVER — a dial to qits-idp in front of the HTTP listener during runtime init — which
    // is exactly why the four names above have to say `false` in this file rather than say nothing.
    assertTrue(
        ConfigProvider.getConfig()
            .getOptionalValue("quarkus.oidc-client.nosuchclient.discovery-enabled", String.class)
            .isEmpty());
  }

  @Test
  void theContainersOwnerKeyFollowsTheQitsClientsId() {
    // qits.projects.containers.owner reads quarkus.oidc-client.qits.client-id by default —
    // OwnerGuard compares this string to a machine token's `sub` once the gate is on.
    assertEquals("qits-projects", value("qits.projects.containers.owner"));
  }
}
