package eu.wohlben.qits.projects.agenthost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.entity.AgentHarnessCapability;
import eu.wohlben.qits.projects.persistence.AgentHarnessCapabilityRepository;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * What the relay does with the three things a daemon can answer.
 *
 * <p>It drives {@code ingest} directly rather than the tunnel: the transport is
 * {@link ContainerProxyRoute}'s and {@link AgentTunnels}' and is proven where they are, while what is
 * worth pinning here is the <b>absent-vs-broken</b> rule. Until both daemons are released the normal
 * answer carries none of the three members this feature added, and getting that case wrong is either
 * a container start that fails or an error log on every container start for weeks.
 */
@QuarkusTest
class AgentCapabilityRelayTest {

  private static final String PROJECT_ID = "9f0f3c30-0000-4000-8000-00000000cafe";

  @Inject AgentCapabilityRelay relay;

  @Inject AgentHarnessCapabilityRepository capabilities;

  /** A daemon that reports: the row lands under the image version and harness it named. */
  @Test
  void aReportIsRecordedUnderTheHarnessAndImageVersionItNames() {
    relay.ingest(
        PROJECT_ID,
        """
        {"agents":["CLAUDE","KIMI"],"defaultAgent":"CLAUDE",
         "reportedBy":"qits-projects-daemon","imageVersion":"2026.911.relaytest",
         "capabilities":[{"harness":"CLAUDE","harnessVersion":"2.0.31",
           "models":["opus","sonnet"],"modelsEnumerated":false,
           "effortSupported":true,"effortLevels":["low","high"],
           "authenticated":true,"authDetail":"","probeFailed":false,"probeDetail":""}]}
        """);

    AgentHarnessCapability row =
        capabilities.find("CLAUDE", "2026.911.relaytest").orElseThrow();
    assertEquals("qits-projects-daemon", row.reportedBy);
    assertEquals(List.of("opus", "sonnet"), AgentHarnessCapability.decode(row.models));
    assertTrue(row.authenticated);
  }

  /**
   * <b>The normal case until the daemons ship.</b> An older {@code /agents/available} answers the
   * harness list and the default and nothing else. That is absent, not broken: nothing is written,
   * nothing throws, and the editor keeps reading the shipped fallback — which is a state the
   * catalogue is designed to be in, not a degradation.
   */
  @Test
  void anOlderDaemonThatReportsNoCapabilitiesIsQuietAndWritesNothing() {
    long before = capabilities.count();

    relay.ingest(PROJECT_ID, """
        {"agents":["CLAUDE","KIMI"],"defaultAgent":"CLAUDE"}
        """);

    assertEquals(before, capabilities.count(), "an absent report writes no row");
  }

  /** The same for a report whose {@code capabilities} member is present and empty. */
  @Test
  void anEmptyCapabilityListIsAbsentRatherThanARefusal() {
    long before = capabilities.count();

    relay.ingest(
        PROJECT_ID,
        """
        {"reportedBy":"d","imageVersion":"2026.911.empty","capabilities":[]}
        """);

    assertEquals(before, capabilities.count());
  }

  /**
   * Broken, and it must not escape. The door refuses an unknown harness with a 400; from a
   * background relay with nobody waiting that becomes a WARN, never an exception on the thread that
   * a container start runs on.
   */
  @Test
  void aHarnessThisPlatformDoesNotKnowIsRefusedWithoutThrowing() {
    long before = capabilities.count();

    relay.ingest(
        PROJECT_ID,
        """
        {"reportedBy":"d","imageVersion":"2026.911.unknown",
         "capabilities":[{"harness":"GEMINI","harnessVersion":"","models":[],
           "modelsEnumerated":false,"effortSupported":false,"effortLevels":[],
           "authenticated":false,"authDetail":"","probeFailed":false,"probeDetail":""}]}
        """);

    assertEquals(before, capabilities.count());
  }

  /** A body that is not this contract at all is a WARN and a return, never a throw. */
  @Test
  void aBodyThatIsNotJsonIsSurvived() {
    relay.ingest(PROJECT_ID, "<html>404 not found</html>");
  }

  /**
   * <b>No body and no capabilities are DIFFERENT answers, and the live defect was reading the first
   * as the second's neighbour — broken.</b>
   *
   * <p>Asserted as outcomes rather than as row counts because neither writes a row: what separates
   * them is only whether the relay asks again, and a count cannot see that. An empty body is a
   * daemon that has not finished booting ({@code NOT_READY}, retried); a body naming no capabilities
   * is an older daemon that answered ({@code ABSENT}, terminal and quiet); a body that is present
   * and will not parse is the two sides disagreeing about a contract ({@code BROKEN}, loud and
   * terminal). Handing "" to Jackson collapses the first into the third — {@code
   * MismatchedInputException: No content to map due to end-of-input} — which is exactly what shipped
   * in 2026.909.130640 and left the catalogue empty on every container start.
   */
  @Test
  void anEmptyBodyIsNotReadyWhileABodyNamingNoCapabilitiesIsAbsent() {
    assertEquals(
        AgentCapabilityRelay.Outcome.Kind.NOT_READY,
        relay.ingest(PROJECT_ID, "").kind(),
        "an empty body says nothing about capabilities, so it cannot end the window");
    assertEquals(
        AgentCapabilityRelay.Outcome.Kind.NOT_READY,
        relay.ingest(PROJECT_ID, "   \n ").kind(),
        "whitespace is no more of an answer than nothing is");
    assertEquals(
        AgentCapabilityRelay.Outcome.Kind.NOT_READY,
        relay.ingest(PROJECT_ID, null).kind());

    assertEquals(
        AgentCapabilityRelay.Outcome.Kind.ABSENT,
        relay.ingest(PROJECT_ID, """
            {"agents":["CLAUDE","KIMI"],"defaultAgent":"CLAUDE"}
            """).kind(),
        "an older daemon ANSWERED: terminal and quiet, not retried");

    assertEquals(
        AgentCapabilityRelay.Outcome.Kind.BROKEN,
        relay.ingest(PROJECT_ID, "<html>not this contract</html>").kind(),
        "a body that is present and will not parse is still loud and still terminal");
  }

  /**
   * The two blanks the relay fills, and the reason it is allowed to.
   *
   * <p>The image version is half the key the catalogue stores under and this host chose the pin it
   * created the container from, so a report keyed on the empty string would be one that cannot be
   * told apart from another build's. The reporter is display text, and "unnamed" says less than the
   * project does. Neither is an opinion about capabilities — a daemon that names them wins.
   */
  @Test
  void aReportThatNamesNoImageVersionIsKeyedOnTheImageThisHostCreatedTheContainerFrom() {
    relay.ingest(
        PROJECT_ID,
        """
        {"capabilities":[{"harness":"KIMI","harnessVersion":"1.2.3","models":["k2"],
           "modelsEnumerated":true,"effortSupported":false,"effortLevels":[],
           "authenticated":false,"authDetail":"","probeFailed":false,"probeDetail":""}]}
        """);

    Optional<AgentHarnessCapability> row =
        capabilities.find(
            "KIMI",
            org.eclipse.microprofile.config.ConfigProvider.getConfig()
                .getValue("qits.projects.agent-image-version", String.class));
    assertTrue(row.isPresent(), "keyed on the host's own image pin rather than on the empty string");
    assertEquals("project-agent/" + PROJECT_ID, row.get().reportedBy);
    assertFalse(row.get().effortSupported, "Kimi has no effort concept and the report said so");
  }
}
