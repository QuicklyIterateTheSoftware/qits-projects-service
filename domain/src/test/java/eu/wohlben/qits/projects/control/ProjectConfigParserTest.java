package eu.wohlben.qits.projects.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.control.ProjectConfig.DeliverySettings;
import eu.wohlben.qits.projects.control.ProjectConfigParser.ProjectConfigException;
import eu.wohlben.qits.projects.entity.FrontDeskLifecycle;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The pure parse of {@code .config/qits/project.yml}. Instantiated directly (no CDI), like {@link
 * ReleaseRequestSettingsParserTest} and {@link QitsConfigParserTest} beside it.
 *
 * <p>The load-bearing half of this suite is the negative: every way the file can be wrong has to
 * <b>throw</b> rather than answer either value, because answering silently re-routes a project's
 * deployments off a typo.
 */
class ProjectConfigParserTest {

  private final ProjectConfigParser parser = new ProjectConfigParser();

  @Test
  void absentContentAbsentKeyAndTrueAreOneAnswer() {
    assertTrue(parser.parse(null).supportsEnvironments());
    assertTrue(parser.parse("").supportsEnvironments());
    assertTrue(parser.parse("   \n").supportsEnvironments());
    assertTrue(parser.parse("# nothing but a comment\n").supportsEnvironments());
    assertTrue(parser.parse("something-else: 1\n").supportsEnvironments());
    assertTrue(parser.parse("supports_environments: true\n").supportsEnvironments());
    assertEquals(ProjectConfig.DEFAULT, parser.parse("supports_environments: true\n"));
  }

  @Test
  void onlyAnExplicitFalseChangesAnything() {
    assertFalse(parser.parse("supports_environments: false\n").supportsEnvironments());
    assertEquals(
        ProjectConfig.SINGLE_ENVIRONMENT, parser.parse("supports_environments: false\n"));
  }

  @Test
  void anUnknownKeyIsIgnoredSoAnOlderReaderStillAnswers() {
    assertFalse(
        parser
            .parse("supports_environments: false\nsome-later-key: whatever\n")
            .supportsEnvironments());
  }

  @Test
  void aFileThatWillNotParseThrowsNamingTheFile() {
    ProjectConfigException e =
        assertThrows(
            ProjectConfigException.class, () -> parser.parse("supports_environments: [unclosed\n"));
    assertTrue(e.getMessage().contains(ProjectConfigParser.CONFIG_PATH));
  }

  @Test
  void aDocumentThatIsNotAMappingThrowsNamingTheFile() {
    ProjectConfigException e =
        assertThrows(ProjectConfigException.class, () -> parser.parse("- supports_environments\n"));
    assertTrue(e.getMessage().contains(ProjectConfigParser.CONFIG_PATH));
  }

  /**
   * The typo case this whole class is shaped around: {@code "false"} quoted, {@code no}, a number —
   * none of them is a boolean, and reading any of them as one would re-route a project on a
   * misspelling. It throws, the reconcile keeps the stored flag, and a person fixes the file.
   */
  @Test
  void aSupportsEnvironmentsThatIsNotABooleanThrowsRatherThanBeingGuessed() {
    for (String bad :
        new String[] {
          "supports_environments: 'false'\n",
          "supports_environments: nope\n",
          "supports_environments: 0\n",
          "supports_environments:\n  nested: true\n"
        }) {
      ProjectConfigException e =
          assertThrows(ProjectConfigException.class, () -> parser.parse(bad), bad);
      assertTrue(e.getMessage().contains(ProjectConfigParser.CONFIG_PATH), bad);
      assertTrue(e.getMessage().contains(ProjectConfigParser.SUPPORTS_ENVIRONMENTS), bad);
    }
  }

  // -------------------------------------------------------------------------------------------
  // front_desk.lifecycle (qits-767)
  // -------------------------------------------------------------------------------------------

  @Test
  void anAbsentFrontDeskOrLifecycleIsOnDemand() {
    assertEquals(FrontDeskLifecycle.ON_DEMAND, parser.parse(null).frontDeskLifecycle());
    assertEquals(
        FrontDeskLifecycle.ON_DEMAND,
        parser.parse("supports_environments: false\n").frontDeskLifecycle());
    assertEquals(
        FrontDeskLifecycle.ON_DEMAND, parser.parse("front_desk:\n").frontDeskLifecycle());
    assertEquals(
        FrontDeskLifecycle.ON_DEMAND,
        parser.parse("front_desk:\n  something-later: 1\n").frontDeskLifecycle());
    assertEquals(FrontDeskLifecycle.ON_DEMAND, ProjectConfig.DEFAULT.frontDeskLifecycle());
    assertEquals(FrontDeskLifecycle.ON_DEMAND, ProjectConfig.SINGLE_ENVIRONMENT.frontDeskLifecycle());
  }

  @Test
  void anExplicitOnDemandIsOnDemand() {
    assertEquals(
        FrontDeskLifecycle.ON_DEMAND,
        parser.parse("front_desk:\n  lifecycle: ON_DEMAND\n").frontDeskLifecycle());
  }

  @Test
  void alwaysOnIsReadBesideTheEnvironmentFlag() {
    ProjectConfig config =
        parser.parse("supports_environments: false\nfront_desk:\n  lifecycle: ALWAYS_ON\n");
    assertEquals(FrontDeskLifecycle.ALWAYS_ON, config.frontDeskLifecycle());
    assertFalse(config.supportsEnvironments());
  }

  /** Case-sensitive, and nothing else is guessed at: a typo throws and the stored value stays. */
  @Test
  void aLifecycleThatIsNotOneOfTheTwoValuesThrows() {
    for (String bad :
        new String[] {
          "front_desk:\n  lifecycle: always_on\n",
          "front_desk:\n  lifecycle: ALWAYS-ON\n",
          "front_desk:\n  lifecycle: true\n",
          "front_desk:\n  lifecycle: [ALWAYS_ON]\n"
        }) {
      ProjectConfigException e =
          assertThrows(ProjectConfigException.class, () -> parser.parse(bad), bad);
      assertTrue(e.getMessage().contains(ProjectConfigParser.CONFIG_PATH), bad);
      assertTrue(e.getMessage().contains("front_desk.lifecycle"), bad);
    }
  }

  @Test
  void aFrontDeskThatIsNotAMappingThrows() {
    for (String bad :
        new String[] {"front_desk: ALWAYS_ON\n", "front_desk:\n  - lifecycle: ALWAYS_ON\n"}) {
      ProjectConfigException e =
          assertThrows(ProjectConfigException.class, () -> parser.parse(bad), bad);
      assertTrue(e.getMessage().contains(ProjectConfigParser.CONFIG_PATH), bad);
      assertTrue(e.getMessage().contains(ProjectConfigParser.FRONT_DESK), bad);
    }
  }

  // -------------------------------------------------------------------------------------------
  // front_desk.delivery (epic qits-1440)
  // -------------------------------------------------------------------------------------------

  /** Absent at every level is the loop off with every value at its default. */
  @Test
  void anAbsentDeliveryIsOff() {
    for (String absent :
        new String[] {
          null, "", "supports_environments: false\n", "front_desk:\n",
          "front_desk:\n  lifecycle: ALWAYS_ON\n", "front_desk:\n  delivery:\n"
        }) {
      assertEquals(DeliverySettings.OFF, parser.parse(absent).delivery(), String.valueOf(absent));
    }
    assertEquals(DeliverySettings.OFF, ProjectConfig.DEFAULT.delivery());
    assertFalse(DeliverySettings.OFF.enabled());
    assertEquals(2, DeliverySettings.OFF.maxRunning());
    assertFalse(DeliverySettings.OFF.startRefined());
    assertEquals(Set.of(), DeliverySettings.OFF.approves());
    assertEquals(Duration.ofMinutes(10), DeliverySettings.OFF.interval());
  }

  /** An absent key inside the mapping is that key's default, and an unknown key is ignored. */
  @Test
  void anEnabledLoopTakesTheDefaultsItDoesNotState() {
    DeliverySettings delivery =
        parser.parse("front_desk:\n  delivery:\n    enabled: true\n    later-key: 1\n").delivery();
    assertEquals(
        new DeliverySettings(true, 2, false, Set.of(), Duration.ofMinutes(10)), delivery);
  }

  @Test
  void everyKeyIsReadBesideTheLifecycle() {
    ProjectConfig config =
        parser.parse(
            "front_desk:\n"
                + "  lifecycle: ALWAYS_ON\n"
                + "  delivery:\n"
                + "    enabled: true\n"
                + "    max_running: 20\n"
                + "    start_refined: true\n"
                + "    approves: [manual-review, artifact-declarations]\n"
                + "    interval: PT1H\n");
    assertEquals(FrontDeskLifecycle.ALWAYS_ON, config.frontDeskLifecycle());
    assertEquals(
        new DeliverySettings(
            true, 20, true, Set.of("artifact-declarations", "manual-review"), Duration.ofHours(1)),
        config.delivery());
    assertEquals("artifact-declarations manual-review", config.delivery().approvesColumn());
  }

  @Test
  void theBoundsAreInclusive() {
    DeliverySettings low =
        parser
            .parse("front_desk:\n  delivery:\n    max_running: 1\n    interval: PT2M\n")
            .delivery();
    assertEquals(1, low.maxRunning());
    assertEquals(Duration.ofMinutes(2), low.interval());
    assertEquals(Set.of(), parser.parse("front_desk:\n  delivery:\n    approves: []\n").delivery().approves());
  }

  /**
   * Every malformed value throws, naming the file and the key — the reconcile then keeps the stored
   * settings. {@code recorded} is a requirement the desk may never satisfy, so naming it is
   * malformed too ({@code config-directory} is approvable since qits-1560, {@code no-changes} since
   * qits-1568).
   */
  @Test
  void aMalformedDeliveryThrows() {
    String[][] bad = {
      {"front_desk:\n  delivery: true\n", "front_desk.delivery"},
      {"front_desk:\n  delivery:\n    - enabled\n", "front_desk.delivery"},
      {"front_desk:\n  delivery:\n    enabled: 'true'\n", "front_desk.delivery.enabled"},
      {"front_desk:\n  delivery:\n    enabled: yes-please\n", "front_desk.delivery.enabled"},
      {"front_desk:\n  delivery:\n    enabled: 1\n", "front_desk.delivery.enabled"},
      {"front_desk:\n  delivery:\n    max_running: 0\n", "front_desk.delivery.max_running"},
      {"front_desk:\n  delivery:\n    max_running: 21\n", "front_desk.delivery.max_running"},
      {"front_desk:\n  delivery:\n    max_running: -1\n", "front_desk.delivery.max_running"},
      {"front_desk:\n  delivery:\n    max_running: 2.5\n", "front_desk.delivery.max_running"},
      {"front_desk:\n  delivery:\n    max_running: 'two'\n", "front_desk.delivery.max_running"},
      {"front_desk:\n  delivery:\n    start_refined: 'false'\n", "front_desk.delivery.start_refined"},
      {"front_desk:\n  delivery:\n    approves: manual-review\n", "front_desk.delivery.approves"},
      {"front_desk:\n  delivery:\n    approves: [recorded]\n", "front_desk.delivery.approves"},
      {
        "front_desk:\n  delivery:\n    approves: [manual-review, recorded]\n",
        "front_desk.delivery.approves"
      },
      {"front_desk:\n  delivery:\n    approves: [No-Changes]\n", "front_desk.delivery.approves"},
      {"front_desk:\n  delivery:\n    approves: [Manual-Review]\n", "front_desk.delivery.approves"},
      {"front_desk:\n  delivery:\n    approves: [1]\n", "front_desk.delivery.approves"},
      {"front_desk:\n  delivery:\n    interval: 10m\n", "front_desk.delivery.interval"},
      {"front_desk:\n  delivery:\n    interval: 600\n", "front_desk.delivery.interval"},
      {"front_desk:\n  delivery:\n    interval: PT1M\n", "front_desk.delivery.interval"},
      {"front_desk:\n  delivery:\n    interval: PT1H1S\n", "front_desk.delivery.interval"},
      {"front_desk:\n  delivery:\n    interval: P1D\n", "front_desk.delivery.interval"},
    };
    for (String[] c : bad) {
      ProjectConfigException e =
          assertThrows(ProjectConfigException.class, () -> parser.parse(c[0]), c[0]);
      assertTrue(e.getMessage().contains(ProjectConfigParser.CONFIG_PATH), c[0]);
      assertTrue(e.getMessage().contains(c[1]), c[0] + " -> " + e.getMessage());
    }
  }

  /**
   * qits-1517: {@code automation} and {@code contract} name a family of requirements and are
   * approvable; qits-1560 added {@code config-directory}, qits-1568 {@code no-changes}; {@code
   * recorded} and a family member's own id are not.
   */
  @Test
  void automationAndContractAreApprovableAndNothingElseIs() {
    assertEquals(
        Set.of(
            "automation",
            "contract",
            "manual-review",
            "artifact-declarations",
            "config-directory",
            "no-changes"),
        DeliverySettings.APPROVABLE);
    assertEquals(
        Set.of("automation", "contract"),
        parser
            .parse("front_desk:\n  delivery:\n    approves: [automation, contract]\n")
            .delivery()
            .approves());
    for (String refused :
        new String[] {
          "recorded", "automation:entity-diagram", "contract:qits", "Automation", "Config-Directory",
          "No-Changes", "no_changes"
        }) {
      ProjectConfigException e =
          assertThrows(
              ProjectConfigException.class,
              () ->
                  parser.parse(
                      "front_desk:\n  delivery:\n    approves: [automation, '"
                          + refused
                          + "']\n"),
              refused);
      assertTrue(e.getMessage().contains("front_desk.delivery.approves"), e.getMessage());
    }
  }

  /** qits-1560: {@code config-directory} is accepted, beside the other names. */
  @Test
  void configDirectoryIsApprovable() {
    assertEquals(
        Set.of("config-directory"),
        parser
            .parse("front_desk:\n  delivery:\n    approves: [config-directory]\n")
            .delivery()
            .approves());
    DeliverySettings all =
        parser
            .parse(
                "front_desk:\n  delivery:\n    approves: [manual-review, artifact-declarations,"
                    + " automation, contract, config-directory, no-changes]\n")
            .delivery();
    assertEquals(DeliverySettings.APPROVABLE, all.approves());
    assertEquals(
        Set.of("config-directory", "manual-review"),
        DeliverySettings.approvesOf("config-directory manual-review"));
  }

  /** qits-1568: {@code no-changes} is accepted, alone and beside manual-review, and round-trips. */
  @Test
  void noChangesIsApprovable() {
    assertEquals(
        Set.of("no-changes"),
        parser
            .parse("front_desk:\n  delivery:\n    approves: [no-changes]\n")
            .delivery()
            .approves());
    DeliverySettings wrapper =
        parser
            .parse(
                "front_desk:\n  delivery:\n    enabled: true\n    approves: [manual-review,"
                    + " no-changes]\n")
            .delivery();
    assertEquals(Set.of("manual-review", "no-changes"), wrapper.approves());
    assertEquals("manual-review no-changes", wrapper.approvesColumn());
    assertEquals(
        Set.of("manual-review", "no-changes"),
        DeliverySettings.approvesOf("manual-review no-changes"));
  }

  /** The approves column round-trips, sorted and space-separated. */
  @Test
  void theApprovesColumnRoundTrips() {
    assertEquals(Set.of(), DeliverySettings.approvesOf(""));
    assertEquals(Set.of(), DeliverySettings.approvesOf(null));
    assertEquals(
        Set.of("manual-review", "artifact-declarations"),
        DeliverySettings.approvesOf("artifact-declarations manual-review"));
    assertEquals(
        "artifact-declarations manual-review",
        new DeliverySettings(
                true, 2, false, Set.of("manual-review", "artifact-declarations"), null)
            .approvesColumn());
  }

  // ---- release_requests.feature_freeze (epic qits-1580) ----------------------------------------

  /** An absent map, an empty one and an absent key are the default freeze of three. */
  @Test
  void anAbsentFeatureFreezeIsThree() {
    assertEquals(ProjectConfig.ReleaseRequestsSettings.DEFAULT, parser.parse(null).releaseRequests());
    assertEquals(3, parser.parse("supports_environments: true\n").releaseRequests().featureFreeze());
    assertEquals(3, parser.parse("release_requests:\n").releaseRequests().featureFreeze());
    assertEquals(
        3, parser.parse("release_requests:\n  other: 1\n").releaseRequests().featureFreeze());
  }

  /** A stated freeze is read beside the other keys, and both bounds are inclusive. */
  @Test
  void aStatedFeatureFreezeIsRead() {
    ProjectConfig config =
        parser.parse(
            "front_desk:\n  lifecycle: ALWAYS_ON\nrelease_requests:\n  feature_freeze: 2\n");
    assertEquals(2, config.releaseRequests().featureFreeze());
    assertEquals(FrontDeskLifecycle.ALWAYS_ON, config.frontDeskLifecycle());
    assertEquals(
        1, parser.parse("release_requests:\n  feature_freeze: 1\n").releaseRequests().featureFreeze());
    assertEquals(
        20,
        parser.parse("release_requests:\n  feature_freeze: 20\n").releaseRequests().featureFreeze());
  }

  /** A freeze out of range or not an integer, and a release_requests that is no mapping, throw. */
  @Test
  void aMalformedFeatureFreezeThrows() {
    for (String bad :
        new String[] {
          "release_requests:\n  feature_freeze: 0\n",
          "release_requests:\n  feature_freeze: 21\n",
          "release_requests:\n  feature_freeze: x\n",
          "release_requests:\n  feature_freeze: \"3\"\n",
          "release_requests:\n  feature_freeze: 2.5\n",
        }) {
      ProjectConfigException e =
          assertThrows(ProjectConfigException.class, () -> parser.parse(bad), bad);
      assertTrue(e.getMessage().contains("an integer from 1 to 20"), e.getMessage());
      assertTrue(e.getMessage().contains("release_requests.feature_freeze"), e.getMessage());
    }
    for (String bad : new String[] {"release_requests: 3\n", "release_requests: [3]\n"}) {
      ProjectConfigException e =
          assertThrows(ProjectConfigException.class, () -> parser.parse(bad), bad);
      assertTrue(e.getMessage().contains("Expected a mapping at 'release_requests'"), e.getMessage());
    }
  }
}
