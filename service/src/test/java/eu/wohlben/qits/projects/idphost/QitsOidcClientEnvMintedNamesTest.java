package eu.wohlben.qits.projects.idphost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.smallrye.config.EnvConfigSource;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The four {@code quarkus.oidc-client.<name>} keys THE DEPLOYED ENVIRONMENT MINTS, and the one thing
 * that must not follow from neutralising them: the real {@code qits} client staying on.
 *
 * <p>This is the arm no {@code @QuarkusTest} can reach. A {@link
 * io.quarkus.test.junit.QuarkusTestProfile}'s overrides land in a plain map-backed source, so a
 * name spelled {@code QUARKUS_OIDC_CLIENT_GITHOST_CLIENT_ENABLED} there is matched by that exact
 * name and never by the dotted key — which is precisely the mangling under test. So the config is
 * assembled by hand from the two sources a deployed process actually has, at the ordinals it gives
 * them: the SHIPPED {@code application.properties} (250, read from {@code src/main/resources} rather
 * than the classpath, where the test copy shadows it) and an {@link EnvConfigSource} (300) carrying
 * the entries read out of qits-configuration for {@code dev} on 2026-09-15.
 *
 * <p>{@link QitsOidcClientShippedConfigTest} pins the same neutralisation through the real Quarkus
 * config stack; it cannot pin what an environment does to it.
 */
class QitsOidcClientEnvMintedNamesTest {

  /** The names the deployed environment still mints. {@code configuration} is deliberately absent. */
  private static final String[] PHANTOMS = {"ci", "githost", "maintenance", "workspaces"};

  /**
   * What qits-configuration answers for (dev, qits-projects) today, oidc-client entries only, secrets
   * elided. Every {@code <NAME>} family here is reported {@code orphaned}; the unnamed one is not.
   */
  private static Map<String, String> deployedEnv() {
    Map<String, String> env = new LinkedHashMap<>();
    env.put("QUARKUS_OIDC_CLIENT_AUTH_SERVER_URL", "http://qits-platform-idp:8080/idp");
    env.put("QUARKUS_OIDC_CLIENT_CLIENT_ENABLED", "true");
    env.put("QUARKUS_OIDC_CLIENT_CLIENT_ID", "dev-qits-projects");
    env.put("QUARKUS_OIDC_CLIENT_CREDENTIALS_SECRET", "deployed-secret");
    env.put("QUARKUS_OIDC_CLIENT_GRANT_OPTIONS_CLIENT_AUDIENCE", "dev-qits-containers");
    for (String name : PHANTOMS) {
      String upper = name.toUpperCase();
      env.put("QUARKUS_OIDC_CLIENT_" + upper + "_AUTH_SERVER_URL", "http://qits-platform-idp:8080/idp");
      env.put("QUARKUS_OIDC_CLIENT_" + upper + "_CLIENT_ENABLED", "true");
      env.put("QUARKUS_OIDC_CLIENT_" + upper + "_CLIENT_ID", "dev-qits-projects");
      env.put("QUARKUS_OIDC_CLIENT_" + upper + "_CREDENTIALS_SECRET", "deployed-secret");
      env.put("QUARKUS_OIDC_CLIENT_" + upper + "_GRANT_OPTIONS_CLIENT_AUDIENCE", "dev-" + name);
    }
    return env;
  }

  private static URL shippedProperties() {
    Path shipped = Path.of("src/main/resources/application.properties");
    if (!Files.isRegularFile(shipped)) {
      // Run from the reactor root rather than from the module directory.
      shipped = Path.of("service/src/main/resources/application.properties");
    }
    assertTrue(Files.isRegularFile(shipped), "cannot find the shipped application.properties");
    try {
      return shipped.toUri().toURL();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /** The shipped file alone, at the ordinal a jar's application.properties gets. */
  private static SmallRyeConfig shippedOnly() {
    try {
      return new SmallRyeConfigBuilder()
          .addDefaultInterceptors()
          .withSources(new PropertiesConfigSource(shippedProperties(), 250))
          .build();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /** The shipped file plus the environment a deployed container is handed. */
  private static SmallRyeConfig shippedUnderDeployedEnv() {
    try {
      return new SmallRyeConfigBuilder()
          .addDefaultInterceptors()
          .withSources(new PropertiesConfigSource(shippedProperties(), 250))
          .withSources(new EnvConfigSource(deployedEnv(), 300))
          .build();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void theShippedFileNeutralisesEveryNameTheEnvironmentMints() {
    SmallRyeConfig config = shippedOnly();
    for (String name : PHANTOMS) {
      String prefix = "quarkus.oidc-client." + name + ".";
      assertEquals("false", config.getValue(prefix + "client-enabled", String.class), name);
      assertEquals("false", config.getValue(prefix + "discovery-enabled", String.class), name);
      assertEquals("token", config.getValue(prefix + "token-path", String.class), name);
    }
  }

  @Test
  void configurationStaysDeletedBecauseNothingMintsIt() {
    // The env carries no QUARKUS_OIDC_CLIENT_CONFIGURATION_* entry, so the key is never minted and
    // the block's deletion was already complete. Restoring it would be three lines for no client.
    assertTrue(
        shippedUnderDeployedEnv()
            .getOptionalValue("quarkus.oidc-client.configuration.client-enabled", String.class)
            .isEmpty());
  }

  @Test
  void theDeploymentOutranksTheShippedClientEnabledSoDiscoveryIsWhatStopsTheDial() {
    // MEASURED, and the reason `client-enabled=false` alone is not the fix: the env source is
    // ordinal 300 and application.properties 250, so `..._CLIENT_ENABLED=true` wins and Quarkus
    // builds the client. What the shipped file still decides is whether it dials qits-idp during
    // runtime init — discovery off, with a token path so the metadata can be composed without one.
    SmallRyeConfig config = shippedUnderDeployedEnv();
    for (String name : PHANTOMS) {
      String prefix = "quarkus.oidc-client." + name + ".";
      assertEquals("true", config.getValue(prefix + "client-enabled", String.class), name);
      assertEquals("false", config.getValue(prefix + "discovery-enabled", String.class), name);
      assertEquals("token", config.getValue(prefix + "token-path", String.class), name);
    }
  }

  @Test
  void theQitsClientIsStillSwitchedOnByTheDeployedEnvironment() {
    // THE ONE THAT MATTERS. `quarkus.oidc-client.qits.client-enabled` is an expression over the
    // unnamed family's raw ENV NAME, and the neutralisation above declares dotted keys of other
    // names. A `qits` client switched off in production would be worse than the boot hang being
    // fixed, so it is measured rather than reasoned about.
    SmallRyeConfig config = shippedUnderDeployedEnv();
    assertEquals("true", config.getValue("quarkus.oidc-client.qits.client-enabled", String.class));
    assertEquals("dev-qits-projects", config.getValue("quarkus.oidc-client.qits.client-id", String.class));
    assertEquals(
        "deployed-secret", config.getValue("quarkus.oidc-client.qits.credentials.secret", String.class));
    assertEquals(
        "http://qits-platform-idp:8080/idp",
        config.getValue("quarkus.oidc-client.qits.auth-server-url", String.class));
    // And the containers owner follows that id, as it must for OwnerGuard.
    assertEquals("dev-qits-projects", config.getValue("qits.projects.containers.owner", String.class));
  }

  @Test
  void aDottedDeclarationIsNeverREADBackUnderItsENVNAME() {
    // WHY the test above can pass at all, and the rule the `qits` client's expressions depend on:
    // the mangling is one-way. EnvConfigSource resolves a dotted key from an UPPER_SNAKE variable;
    // no source resolves an UPPER_SNAKE name from a dotted properties key. So a `${QUARKUS_OIDC_
    // CLIENT_GITHOST_CLIENT_ENABLED:…}` fallback could not pick up the `false` declared above, and
    // the unnamed block's long-shipped `quarkus.oidc-client.client-enabled=false` is not what
    // `${QUARKUS_OIDC_CLIENT_CLIENT_ENABLED:false}` has been reading either.
    SmallRyeConfig shipped = shippedOnly();
    for (String declared :
        new String[] {
          "QUARKUS_OIDC_CLIENT_CLIENT_ENABLED",
          "QUARKUS_OIDC_CLIENT_GITHOST_CLIENT_ENABLED",
          "QUARKUS_OIDC_CLIENT_CI_CLIENT_ENABLED",
          "QUARKUS_OIDC_CLIENT_MAINTENANCE_CLIENT_ENABLED",
          "QUARKUS_OIDC_CLIENT_WORKSPACES_CLIENT_ENABLED"
        }) {
      Optional<String> byEnvName = shipped.getOptionalValue(declared, String.class);
      assertTrue(byEnvName.isEmpty(), declared + " resolved from a dotted properties key: " + byEnvName);
    }
    // The dotted keys themselves are of course there — this is about the spelling, not the value.
    assertEquals("false", shipped.getValue("quarkus.oidc-client.client-enabled", String.class));
    assertEquals("false", shipped.getValue("quarkus.oidc-client.githost.client-enabled", String.class));
  }
}
