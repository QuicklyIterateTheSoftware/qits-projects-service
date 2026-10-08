package eu.wohlben.qits.projects.deskhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.entity.DeskRunner;
import eu.wohlben.qits.projects.error.CodedRefusalException;
import eu.wohlben.qits.projectsdeskrunner.protocol.DeskRunnerBinary;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The install line and {@code install.sh} a front-desk runner is handed (qits-767), rendered from
 * qits-runner-toolkit's template with the desk runner's identity and this deployment's public
 * names — and refused, coded, on a deployment with no public domain.
 */
class DeskRunnerInstallScriptTest {

  private static final String DOMAIN = DeskRunnerAddressesFixture.DOMAIN;

  private static DeskRunnerInstallScript on(String domain) {
    DeskRunnerInstallScript script = new DeskRunnerInstallScript();
    script.addresses = DeskRunnerAddressesFixture.withDomain(domain);
    return script;
  }

  @Test
  void theLineFetchesTheScriptFromProjectsAndSetsTheDeskRunnersEnvironment() {
    DeskRunner runner = new DeskRunner();
    runner.id = UUID.fromString("11111111-2222-3333-4444-555555555555");
    runner.slots = 8;

    String line = on(DOMAIN).line(runner, "qits_tok_abc");

    assertTrue(
        line.startsWith(
            "curl -fsSL -H 'Authorization: Bearer qits_tok_abc' https://projects.qits."
                + DOMAIN
                + "/projects/api/runners/install.sh | sudo env "),
        line);
    assertTrue(line.contains("QITS_PROJECTS_DESK_RUNNER_URL="), line);
    assertTrue(line.contains("https://projects.qits." + DOMAIN), line);
    assertTrue(line.contains("QITS_PROJECTS_DESK_RUNNER_ID="), line);
    assertTrue(line.contains(runner.id.toString()), line);
    assertTrue(line.contains("QITS_PROJECTS_DESK_RUNNER_REGISTRATION_TOKEN="), line);
    assertTrue(line.contains("QITS_PROJECTS_DESK_RUNNER_SLOTS="), line);
    assertTrue(line.trim().endsWith("sh"), line);
  }

  @Test
  void theScriptNamesThePinnedDeskRunnerImageOnThisRegistryAndCarriesNoSecret() {
    String script = on(DOMAIN).script();

    assertTrue(script.startsWith("#!"), "a script: " + script.lines().findFirst().orElse(""));
    assertTrue(
        script.contains(
            "registry.qits." + DOMAIN + "/qits/qits-projects-desk-runner:" + DeskRunnerBinary.VERSION),
        "the pinned runner image on this deployment's registry");
    assertTrue(script.contains("QITS_PROJECTS_DESK_RUNNER_"), "the desk runner's environment");
    assertFalse(script.contains("qits_tok_"), "no token is in the script");
    assertFalse(script.contains("qits-workspaces-runner"), "no other runner's names");
  }

  /** The node's shared volumes are tuning the line passes through when the operator sets them. */
  @Test
  void theScriptPassesTheDeskVolumesThroughAsTuning() {
    String script = on(DOMAIN).script();

    for (String variable :
        java.util.List.of(
            "QITS_PROJECTS_DESK_RUNNER_CLAUDE_VOLUME",
            "QITS_PROJECTS_DESK_RUNNER_M2_VOLUME",
            "QITS_PROJECTS_DESK_RUNNER_PNPM_VOLUME")) {
      org.junit.jupiter.api.Assertions.assertTrue(script.contains(variable), variable);
    }
  }

  @Test
  void aDomainWithoutADotRefusesEveryRendering() {
    DeskRunnerInstallScript script = on("localhost");

    CodedRefusalException refused =
        assertThrows(CodedRefusalException.class, script::requireRenderable);

    assertEquals(503, refused.statusCode());
    assertEquals(DeskRunnerAddresses.RUNNER_PLANE_UNCONFIGURED, refused.code());
    assertThrows(CodedRefusalException.class, () -> on(null).script());
  }

  @Test
  void theAddressesAreTheProjectsHostsOnTheDomain() {
    DeskRunnerAddresses addresses = DeskRunnerAddressesFixture.withDomain(" .Example.Test. ");

    assertEquals("https://projects.qits.example.test", addresses.serviceBase());
    assertEquals(
        "wss://projects.qits.example.test/projects/runners/socket", addresses.socketUrl());
    assertEquals("https://idp.qits.example.test/idp/token", addresses.tokenUrl());
    assertEquals("qits-platform", addresses.audience());
    assertEquals(
        "registry.qits.example.test/qits/qits-projects-desk-runner:1.2.3",
        addresses.runnerImage("1.2.3"));
  }
}
