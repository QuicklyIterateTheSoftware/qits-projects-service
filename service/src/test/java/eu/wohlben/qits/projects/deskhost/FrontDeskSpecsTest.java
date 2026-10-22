package eu.wohlben.qits.projects.deskhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.entity.FrontDesk;
import eu.wohlben.qits.projects.entity.FrontDeskLifecycle;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projectsdeskrunner.protocol.DeskSpec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The front desk's spec (qits-767), pinned whole for a fixed project, token and domain, with the
 * belts an edge-only desk lives by: no commissioned client, no token endpoint, and no internal,
 * environment-service or localhost address anywhere in it.
 */
class FrontDeskSpecsTest {

  private static final String PROJECT_ID = "6f1c2a3b-0000-4000-8000-000000000001";

  private static final String DOCUMENT = "{\"version\":2,\"surfaces\":{}}";

  private static FrontDeskSpecs.Settings settings(String domain) {
    return new FrontDeskSpecs.Settings(
        domain,
        "registry.dev.localhost:8080/qits/project-agent",
        "2026.1001.1",
        null,
        "1000",
        "4g",
        null,
        null,
        800,
        "qits-projects-daemon",
        13338,
        13337,
        "/tmp/qits/agent-configuration.json");
  }

  private static Project project(FrontDeskLifecycle lifecycle) {
    Project project = new Project();
    project.id = PROJECT_ID;
    project.name = "Qits";
    project.slug = "qits";
    project.frontDeskLifecycle = lifecycle;
    return project;
  }

  private static FrontDesk row() {
    FrontDesk row = new FrontDesk();
    row.projectId = PROJECT_ID;
    row.tokenId = "t-1";
    row.tokenValue = "qits_tok_secret";
    row.tokenSubject = "tok-abc";
    return row;
  }

  private static Map<String, String> identity() {
    Map<String, String> env = new LinkedHashMap<>();
    env.put("GIT_AUTHOR_NAME", "qits");
    env.put("GIT_AUTHOR_EMAIL", "qits@local");
    env.put("GIT_COMMITTER_NAME", "qits");
    env.put("GIT_COMMITTER_EMAIL", "qits@local");
    return env;
  }

  private static FrontDeskSpecs.Composed compose(String domain, FrontDeskLifecycle lifecycle) {
    return FrontDeskSpecs.compose(
        settings(domain), project(lifecycle), row(), identity(), DOCUMENT);
  }

  /** The golden: every field and the exact env, in the table's order. */
  @Test
  void theSpecIsPinnedWhole() {
    FrontDeskSpecs.Composed composed = compose("Example.TEST.", FrontDeskLifecycle.ALWAYS_ON);
    DeskSpec spec = composed.spec();

    assertEquals("registry.qits.example.test/qits/project-agent:2026.1001.1", spec.image());
    assertEquals("1000", spec.user());
    assertEquals("4g", spec.memory());
    assertEquals("4g", spec.memorySwap());
    assertNull(spec.pidsLimit());
    assertNull(spec.cpus());
    assertEquals(800, spec.oomScoreAdj());

    Map<String, String> expected = new LinkedHashMap<>();
    expected.put(
        "QITS_PROJECTS_DAEMON_URL", "wss://projects.qits.example.test/projects/daemon/" + PROJECT_ID);
    expected.put("QITS_PROJECTS_DAEMON_API_BASE_PATH", "/projects/container/" + PROJECT_ID + "/");
    expected.put("QITS_PROJECTS_DAEMON_PROJECT_ID", PROJECT_ID);
    expected.put("QITS_PROJECTS_DAEMON_REPO_NAME", "qits-qits");
    expected.put("QITS_PROJECTS_DAEMON_GIT_BASE", "https://githost.qits.example.test/git");
    expected.put("QITS_PROJECTS_DAEMON_API_TOKEN", "qits-projects-daemon");
    expected.put("QITS_PROJECTS_DAEMON_API_PORT", "13338");
    expected.put("QITS_PROJECTS_DAEMON_HOOKS_PORT", "13337");
    expected.put("QITS_PROJECTS_DAEMON_CLAUDE_MOUNT", "/claude-home");
    expected.put(
        "QITS_PROJECTS_DAEMON_AGENT_CONFIGURATION_PATH", "/tmp/qits/agent-configuration.json");
    expected.put("QITS_PROJECTS_DAEMON_AGENT_CONFIGURATION", DOCUMENT);
    expected.put("QITS_PROJECTS_DAEMON_LIFECYCLE", "ALWAYS_ON");
    expected.put("QITS_PROJECTS_DAEMON_DELIVERY", "off");
    expected.put("QITS_PROJECTS_DAEMON_DELIVERY_INTERVAL", "PT10M");
    expected.put("QITS_TOKEN", "qits_tok_secret");
    expected.put("QITS_TOKEN_SUBJECT", "tok-abc");
    expected.put("QITS_DOMAIN", "example.test");
    expected.put("GIT_CONFIG_GLOBAL", "/etc/qits-gitconfig");
    expected.put("QITS_GIT_AUTH_HOST", "githost.qits.example.test");
    expected.put("QITS_PLATFORM_MCP_URL", "https://mcp.qits.example.test/mcp");
    expected.putAll(identity());
    expected.put("CLAUDE_CONFIG_DIR", "/claude-home/.claude");
    expected.put("KIMI_CODE_HOME", "/claude-home/.kimi-code");
    expected.put("MAVEN_OPTS", "-Dmaven.repo.local=/caches/m2");
    expected.put("npm_config_store_dir", "/caches/pnpm/store");
    assertEquals(expected, spec.env());
    assertEquals(List.copyOf(expected.keySet()), List.copyOf(spec.env().keySet()));

    assertEquals(FrontDeskSpecs.canonical(spec), composed.json());
    assertEquals(FrontDeskSpecs.sha256(composed.json()), composed.hash());
    assertTrue(composed.json().startsWith("{\"cpus\":null,\"env\":{\"CLAUDE_CONFIG_DIR\""));
  }

  /** The forbidden-value belts, over every name and every value. */
  @Test
  void anEdgeOnlyDeskCarriesNothingInternal() {
    for (FrontDeskLifecycle lifecycle : FrontDeskLifecycle.values()) {
      DeskSpec spec = compose("example.test", lifecycle).spec();
      assertFalse(spec.image().contains(".localhost"), spec.image());
      spec.env()
          .forEach(
              (name, value) -> {
                assertFalse(name.startsWith("QITS_COMMISSIONED_"), name);
                assertFalse(name.endsWith("_AUTH_TOKEN_URL"), name);
                assertFalse(value.contains(".internal"), name + "=" + value);
                assertFalse(value.contains("-qits-"), name + "=" + value);
                assertFalse(value.contains(".localhost"), name + "=" + value);
              });
    }
  }

  /**
   * The delivery loop (epic qits-1440): on or off, the desk is told so with its interval — and since
   * qits-1482 it holds the delivery token in no environment either way: that token travels only in
   * the {@code sessionCredentials} frame, under the {@code front-desk} key. The hash still moves
   * with the loop.
   */
  @Test
  void theDeliveryTokenIsInNoSpecWhileTheLoopIsOnOrOff() {
    Project off = project(FrontDeskLifecycle.ON_DEMAND);
    FrontDesk row = row();
    row.deliveryTokenId = "d-1";
    row.deliveryTokenValue = "qits_tok_delivery";
    row.deliveryTokenSubject = "tok-delivery";
    Map<String, String> offEnv =
        FrontDeskSpecs.compose(settings("example.test"), off, row, identity(), DOCUMENT).spec().env();
    assertEquals("off", offEnv.get("QITS_PROJECTS_DAEMON_DELIVERY"));
    assertEquals("PT10M", offEnv.get("QITS_PROJECTS_DAEMON_DELIVERY_INTERVAL"));
    assertFalse(offEnv.containsKey("QITS_DELIVERY_TOKEN"));
    assertFalse(offEnv.containsKey("QITS_DELIVERY_TOKEN_SUBJECT"));

    Project on = project(FrontDeskLifecycle.ON_DEMAND);
    on.deliveryEnabled = true;
    on.deliveryInterval = "PT5M";
    FrontDeskSpecs.Composed composed =
        FrontDeskSpecs.compose(settings("example.test"), on, row, identity(), DOCUMENT);
    Map<String, String> onEnv = composed.spec().env();
    assertEquals("on", onEnv.get("QITS_PROJECTS_DAEMON_DELIVERY"));
    assertEquals("PT5M", onEnv.get("QITS_PROJECTS_DAEMON_DELIVERY_INTERVAL"));
    assertFalse(onEnv.containsKey("QITS_DELIVERY_TOKEN"), "the token rides the control socket");
    assertFalse(onEnv.containsKey("QITS_DELIVERY_TOKEN_SUBJECT"));
    onEnv.forEach(
        (name, value) -> {
          assertFalse(value.contains("qits_tok_delivery"), name);
          assertFalse(value.equals("tok-delivery"), name);
        });
    assertEquals("qits_tok_secret", onEnv.get("QITS_TOKEN"), "the desk's own token is unchanged");

    assertFalse(
        composed.hash().equals(
            FrontDeskSpecs.compose(settings("example.test"), off, row, identity(), DOCUMENT).hash()),
        "turning the loop on moves the hash, so the spec roll recreates the desk");
    FrontDesk tokenless = row();
    assertEquals(
        FrontDeskSpecs.compose(settings("example.test"), on, tokenless, identity(), DOCUMENT).hash(),
        composed.hash(),
        "the delivery token never moves the hash");
  }

  /** The session token (qits-1482) is in no spec: it goes over the control socket. */
  @Test
  void theSessionTokenIsInNoSpec() {
    FrontDesk row = row();
    row.sessionTokenId = "s-1";
    row.sessionTokenValue = "qits_tok_session";
    row.sessionTokenSubject = "tok-session";
    Project project = project(FrontDeskLifecycle.ON_DEMAND);
    project.deliveryEnabled = true;
    FrontDeskSpecs.Composed composed =
        FrontDeskSpecs.compose(settings("example.test"), project, row, identity(), DOCUMENT);
    composed
        .spec()
        .env()
        .forEach(
            (name, value) -> {
              assertFalse(value.contains("qits_tok_session"), name);
              assertFalse(value.equals("tok-session"), name);
            });
    assertEquals(
        FrontDeskSpecs.compose(settings("example.test"), project, row(), identity(), DOCUMENT).hash(),
        composed.hash(),
        "the session token never moves the hash");
  }

  /** TZ only when a zone is configured. */
  @Test
  void theTimezoneIsStatedOnlyWhenSet() {
    assertFalse(compose("example.test", FrontDeskLifecycle.ON_DEMAND).spec().env().containsKey("TZ"));
    FrontDeskSpecs.Settings base = settings("example.test");
    FrontDeskSpecs.Settings zoned =
        new FrontDeskSpecs.Settings(
            base.domain(), base.imageRepo(), base.imageVersion(), "Europe/Berlin", base.user(),
            base.memory(), base.pidsLimit(), base.cpus(), base.oomScoreAdj(),
            base.daemonApiToken(), base.daemonApiPort(), base.daemonHooksPort(),
            base.agentConfigurationPath());
    DeskSpec spec =
        FrontDeskSpecs.compose(zoned, project(FrontDeskLifecycle.ON_DEMAND), row(), identity(), DOCUMENT)
            .spec();
    assertEquals("Europe/Berlin", spec.env().get("TZ"));
  }

  /** Blank, dotless and localhost domains refuse to compose: EDGE_PLANE_UNCONFIGURED. */
  @Test
  void anUnconfiguredEdgeRefusesToCompose() {
    for (String domain : new String[] {null, "", "  ", "localhost", "dev.localhost", "nodot"}) {
      FrontDeskSpecs.EdgePlaneUnconfigured refused =
          assertThrows(
              FrontDeskSpecs.EdgePlaneUnconfigured.class,
              () -> compose(domain, FrontDeskLifecycle.ON_DEMAND),
              String.valueOf(domain));
      assertTrue(refused.getMessage().startsWith(FrontDeskSpecs.EDGE_PLANE_UNCONFIGURED));
    }
  }

  /** A lifecycle flip and a token change move the hash; the same inputs never do. */
  @Test
  void theHashFollowsTheSpecAndOnlyTheSpec() {
    String onDemand = compose("example.test", FrontDeskLifecycle.ON_DEMAND).hash();
    assertEquals(onDemand, compose("example.test", FrontDeskLifecycle.ON_DEMAND).hash());
    assertFalse(onDemand.equals(compose("example.test", FrontDeskLifecycle.ALWAYS_ON).hash()));
    assertEquals(64, onDemand.length());
  }

  /** What spec_json holds reads back as the spec it was. */
  @Test
  void theStoredSpecReadsBack() {
    FrontDeskSpecs.Composed composed = compose("example.test", FrontDeskLifecycle.ON_DEMAND);
    DeskSpec back = FrontDeskSpecs.parse(composed.json());
    assertEquals(composed.spec().env(), back.env());
    assertEquals(composed.json(), FrontDeskSpecs.canonical(back));
  }

  /** The image rewrite keeps path, tag and digest byte for byte, and leaves a foreign registry. */
  @Test
  void theImageRewriteKeepsEverythingButTheRegistry() {
    FrontDeskSpecs.Settings base = settings("example.test");
    FrontDeskSpecs.Settings digest =
        new FrontDeskSpecs.Settings(
            base.domain(), "registry.dev.localhost:8080/qits/project-agent", "1@sha256:abc",
            null, "1000", null, null, null, null, "t", 1, 2, "");
    assertEquals(
        "registry.qits.example.test/qits/project-agent:1@sha256:abc", FrontDeskSpecs.image(digest));
    FrontDeskSpecs.Settings hub =
        new FrontDeskSpecs.Settings(
            base.domain(), "library/agent", "1", null, "1000", null, null, null, null, "t", 1, 2, "");
    assertEquals("library/agent:1", FrontDeskSpecs.image(hub));
  }

  /**
   * The emergency hatch over the pin, moved here from {@code AgentContainerFactoryTest} with the
   * rule: set, it wins; absent or blank (a deployment rendering {@code KEY=}), the pin wins.
   */
  @Test
  void theOverrideWinsWhenSetAndThePinWinsWhenItIsNot() {
    FrontDeskSpecs overridden = new FrontDeskSpecs();
    overridden.imageVersionOverride = java.util.Optional.of("2026.999.000000");
    assertEquals("2026.999.000000", overridden.imageVersion());

    FrontDeskSpecs unset = new FrontDeskSpecs();
    unset.imageVersionOverride = java.util.Optional.empty();
    assertEquals(
        eu.wohlben.qits.projectsdaemon.protocol.ProjectAgentImage.VERSION,
        unset.imageVersion(),
        "absent is the shipped state");

    FrontDeskSpecs blank = new FrontDeskSpecs();
    blank.imageVersionOverride = java.util.Optional.of("   ");
    assertEquals(
        eu.wohlben.qits.projectsdaemon.protocol.ProjectAgentImage.VERSION,
        blank.imageVersion(),
        "a present, empty value must not become the image tag");
  }
}
