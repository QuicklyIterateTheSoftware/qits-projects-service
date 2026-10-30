package eu.wohlben.qits.projects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.control.ReservedSlugs;
import io.smallrye.config.EnvConfigSource;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What a DEPLOYED process resolves once the deployer stops handing it qits-configuration's extras
 * (qits-1391): only {@code QITS_ENVIRONMENT} from the deployer, and everything else from the shipped
 * files. Each assertion is a value the deployer used to state as an {@code env.*} extra on
 * dev-qits-projects, so a release that moved one of them would change a live value.
 *
 * <p>Assembled by hand from the two shipped files at the ordinals a packaged jar gives them (domain's
 * {@code META-INF/microprofile-config.properties} at 100, the service's {@code
 * application.properties} at 250) plus an env source at 300, because the classpath copy is shadowed
 * by the test {@code application.properties} and a {@code @QuarkusTest} only ever runs {@code test}.
 */
class ShippedProdDefaultsTest {

  /** The list the deployer extra QITS_PROJECTS_RESERVED_SLUGS stated on dev, read 2026-10-30. */
  private static final List<String> LIVE_RESERVED =
      List.of(
          "dev", "artifacts", "ci", "configuration", "containers", "deployments", "docs", "edge",
          "editor", "events", "githost", "idp", "maintenance", "mirror", "observability",
          "orchestrator", "projects", "registry", "stt", "system", "workspaces");

  private static URL shipped(String moduleRelative, String module) {
    // Named through the module directory either way, because the service module carries a
    // META-INF/microprofile-config.properties of its own and a bare relative path would find it.
    Path path = Path.of("..", module, moduleRelative);
    if (!Files.isRegularFile(path)) {
      path = Path.of(module, moduleRelative);
    }
    assertTrue(Files.isRegularFile(path), "cannot find shipped " + module + "/" + moduleRelative);
    try {
      return path.toUri().toURL();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static SmallRyeConfig shipped(String profile, Map<String, String> env) {
    try {
      return new SmallRyeConfigBuilder()
          .addDefaultInterceptors()
          .withProfile(profile)
          .withSources(
              new PropertiesConfigSource(
                  shipped("src/main/resources/META-INF/microprofile-config.properties", "domain"),
                  100))
          .withSources(
              new PropertiesConfigSource(
                  shipped("src/main/resources/application.properties", "service"), 250))
          .withSources(new EnvConfigSource(env, 300))
          .withSources(new PropertiesConfigSource(Map.of("user.home", "/home/qits"), "sys", 400))
          .build();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static SmallRyeConfig deployed(String environment) {
    return shipped("prod", Map.of("QITS_ENVIRONMENT", environment));
  }

  @Test
  void aDeployedProcessResolvesEveryRetiredExtraToItsLiveValue() {
    SmallRyeConfig config = deployed("dev");
    assertEquals("true", config.getValue("qits.auth.machine.required", String.class));
    assertEquals("true", config.getValue("quarkus.oidc.tenant-enabled", String.class));
    assertEquals("/data/mirrors", config.getValue("qits.projects.data-dir", String.class));
    assertEquals(
        "http://dev-qits-githost:8080", config.getValue("qits.githost.url", String.class));
    assertEquals(
        "http://dev-qits-ci:8080",
        config.getValue("qits.projects.release-requests.ci-url", String.class));
    assertEquals(
        "http://dev-qits-githost:8080",
        config.getValue("qits.projects.release-requests.githost-url", String.class));
    assertEquals(
        "http://dev-qits-maintenance:8080",
        config.getValue("qits.projects.release-requests.maintenance-url", String.class));
    assertEquals(
        "http://dev-qits-workspaces:8080",
        config.getValue("qits.projects.release-requests.workspaces-url", String.class));
    assertEquals("dev-qits-projects", config.getValue("qits.projects.own-host", String.class));
    assertEquals(
        "http://dev-qits-observability:8080",
        config.getValue("qits.observability.url", String.class));
    assertEquals(
        "http://dev-qits-idp:8080/idp", config.getValue("quarkus.oidc.auth-server-url", String.class));
  }

  @Test
  void theReservedSetIsAtLeastTheOneTheDeployerUsedToState() {
    ReservedSlugs reserved =
        ReservedSlugs.forEnvironments(
            deployed("dev").getOptionalValues("qits.projects.reserved-slugs", String.class).orElseThrow());
    for (String slug : LIVE_RESERVED) {
      assertTrue(reserved.isReserved(slug), slug + " is no longer reserved");
    }
  }

  @Test
  void theAddressesFollowTheEnvironmentRatherThanNamingOne() {
    SmallRyeConfig config = deployed("prod");
    assertEquals(
        "http://prod-qits-githost:8080", config.getValue("qits.githost.url", String.class));
    assertEquals(
        "http://prod-qits-ci:8080",
        config.getValue("qits.projects.release-requests.ci-url", String.class));
    assertEquals(
        "http://prod-qits-githost:8080",
        config.getValue("qits.projects.release-requests.githost-url", String.class));
    assertTrue(
        config
            .getOptionalValues("qits.projects.reserved-slugs", String.class)
            .orElseThrow()
            .contains("prod"));
  }

  @Test
  void devAndTestKeepTheirClonedAloneBehaviour() {
    for (String profile : List.of("dev", "test")) {
      SmallRyeConfig config = shipped(profile, Map.of());
      assertEquals(
          "false", config.getValue("qits.auth.machine.required", String.class), profile);
      assertFalse(
          config.getOptionalValue("qits.projects.release-requests.ci-url", String.class).isPresent(),
          profile);
      assertFalse(
          config
              .getOptionalValue("qits.projects.release-requests.githost-url", String.class)
              .isPresent(),
          profile);
      assertFalse(
          config.getOptionalValues("qits.projects.reserved-slugs", String.class).isPresent(),
          profile);
      assertNotEquals(
          "/data/mirrors", config.getValue("qits.projects.data-dir", String.class), profile);
    }
  }
}
