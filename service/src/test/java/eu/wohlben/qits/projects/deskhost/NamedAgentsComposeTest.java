package eu.wohlben.qits.projects.deskhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.control.AgentSurfaceDefaults;
import eu.wohlben.qits.projects.control.NamedAgents;
import eu.wohlben.qits.projects.entity.FrontDesk;
import eu.wohlben.qits.projects.entity.FrontDeskLifecycle;
import eu.wohlben.qits.projects.entity.NamedAgent;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.refinementhost.RefinementSessions;
import eu.wohlben.qits.projectsdaemon.protocol.DaemonProtocol;
import eu.wohlben.qits.projectsdaemon.protocol.SessionCredentials;
import eu.wohlben.qits.projectsdaemon.protocol.SessionCredentials.Credential;
import eu.wohlben.qits.projectsdaemon.protocol.Sessions;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The named agents off any container (qits-2154): the registry, the {@code agent:<name>} entries of
 * the sessions frame (capability gate and idle window), the credentials frame's keys, and the
 * listing's state vocabulary. All pure.
 */
class NamedAgentsComposeTest {

  private static final Instant NOW = Instant.parse("2026-11-13T12:00:00Z");

  private static final Duration IDLE = Duration.ofHours(4);

  private static Project project() {
    Project project = new Project();
    project.id = "p1";
    project.slug = "demo";
    project.frontDeskLifecycle = FrontDeskLifecycle.ON_DEMAND;
    return project;
  }

  private static NamedAgent agent(String name, Instant lastDemandAt, String sessionId) {
    NamedAgent row = new NamedAgent();
    row.projectId = "p1";
    row.name = name;
    row.surface = AgentSurfaceDefaults.PROJECT_UI_BUILDER;
    row.lastDemandAt = lastDemandAt;
    row.sessionId = sessionId;
    return row;
  }

  // ---- the registry ------------------------------------------------------------------------------

  @Test
  void everyProjectHasTheDeskAndTheUiBuilderTheDeskFirst() {
    assertEquals(
        List.of("desk", "ui-builder"),
        NamedAgents.ALL.stream().map(NamedAgents.Agent::name).toList());
    NamedAgents.Agent desk = NamedAgents.find("desk").orElseThrow();
    assertTrue(desk.desk());
    assertEquals("project.work", desk.surface(), "the desk keeps its own surface and keeper");
    NamedAgents.Agent builder = NamedAgents.find("ui-builder").orElseThrow();
    assertFalse(builder.desk());
    assertEquals("project.ui-builder", builder.surface());
    assertEquals(List.of(builder), NamedAgents.hosted(), "only the others have a row");
    assertTrue(NamedAgents.find("front-desk").isEmpty());
    assertTrue(NamedAgents.find(null).isEmpty());
  }

  /**
   * The key is the daemon's protocol's {@code NamedAgents} (capability 4), which the hosts call; the
   * registry only has to hold names that protocol accepts, since the daemon refuses any other as a
   * path segment and a file name.
   */
  @Test
  void everyRegisteredNameIsOneTheDaemonAcceptsAsAKey() {
    for (NamedAgents.Agent agent : NamedAgents.ALL) {
      assertTrue(
          eu.wohlben.qits.projectsdaemon.protocol.NamedAgents.validName(agent.name()),
          agent.name());
      assertEquals(
          agent.name(),
          eu.wohlben.qits.projectsdaemon.protocol.NamedAgents.namedAgentName(
                  eu.wohlben.qits.projectsdaemon.protocol.NamedAgents.namedAgentKey(agent.name()))
              .orElseThrow());
    }
    assertEquals(
        "agent:ui-builder",
        eu.wohlben.qits.projectsdaemon.protocol.NamedAgents.namedAgentKey(NamedAgents.UI_BUILDER));
    assertTrue(
        eu.wohlben.qits.projectsdaemon.protocol.NamedAgents.namedAgentName(
                SessionCredentials.FRONT_DESK_KEY)
            .isEmpty());
  }

  // ---- the sessions frame ------------------------------------------------------------------------

  @Test
  void aPromptedAgentIsAskedForWithItsSurfaceAndResumeIdOnACapableDaemon() {
    Sessions frame =
        RefinementSessions.compose(
            project(),
            null,
            List.of(),
            id -> null,
            NOW,
            IDLE,
            List.of(agent("ui-builder", NOW.minusSeconds(60), "sess-ui-1")),
            true);

    Sessions.Session entry = frame.find("agent:ui-builder").orElseThrow();
    assertEquals("project.ui-builder", entry.surface());
    assertEquals(DaemonProtocol.DesiredState.RUNNING, entry.state());
    assertEquals("sess-ui-1", entry.resumeSessionId(), "every prompt resumes the same session");
    assertNull(entry.entity(), "tied to no work item");
    assertTrue(
        frame.find(SessionCredentials.FRONT_DESK_KEY).isEmpty(),
        "the desk's own entry is untouched by an agent's");
  }

  @Test
  void aDaemonBelowCapabilityFourIsNeverAskedForANamedAgent() {
    Sessions frame =
        RefinementSessions.compose(
            project(),
            null,
            List.of(),
            id -> null,
            NOW,
            IDLE,
            List.of(agent("ui-builder", NOW, "sess-ui-1")),
            false);

    assertTrue(frame.find("agent:ui-builder").isEmpty());
    assertTrue(frame.sessions().isEmpty());
  }

  @Test
  void anAgentOutsideItsIdleWindowOrNeverPromptedIsLeftOutSoItCostsNoSession() {
    Sessions frame =
        RefinementSessions.compose(
            project(),
            null,
            List.of(),
            id -> null,
            NOW,
            IDLE,
            List.of(
                agent("ui-builder", NOW.minus(IDLE).minusSeconds(1), "sess-ui-1"),
                agent("other", null, null)),
            true);

    assertTrue(frame.sessions().isEmpty());
  }

  @Test
  void aBlankStoredSessionIdLaunchesFresh() {
    Sessions.Session entry =
        RefinementSessions.compose(
                project(),
                null,
                List.of(),
                id -> null,
                NOW,
                IDLE,
                List.of(agent("ui-builder", NOW, " ")),
                true)
            .find("agent:ui-builder")
            .orElseThrow();
    assertNull(entry.resumeSessionId());
  }

  @Test
  void theSixArgumentComposeNamesNoAgent() {
    Sessions frame =
        RefinementSessions.compose(
            project(), "desk-sess", List.of(), id -> null, NOW, IDLE);
    assertTrue(frame.sessions().stream().noneMatch(s -> s.key().startsWith("agent:")));
  }

  // ---- the credentials frame ---------------------------------------------------------------------

  private static Map<String, Credential> byKey(SessionCredentials frame) {
    return frame.credentials().stream()
        .collect(Collectors.toMap(Credential::key, credential -> credential));
  }

  @Test
  void aNamedAgentHoldingATokenRunsOnItsOwnUnderItsKey() {
    FrontDesk desk = new FrontDesk();
    desk.projectId = "p1";
    desk.sessionTokenValue = "qits_tok_session";
    desk.sessionTokenSubject = "tok-session";
    NamedAgent builder = agent("ui-builder", NOW, null);
    builder.tokenId = "n-1";
    builder.tokenValue = "qits_tok_builder";
    builder.tokenSubject = "tok-builder";
    NamedAgent unminted = agent("other", NOW, null);

    Map<String, Credential> keys =
        byKey(
            DeskSessionCredentials.compose(
                project(), desk, List.of(), List.of(builder, unminted)));

    Credential held = keys.get("agent:ui-builder");
    assertEquals("qits_tok_builder", held.token());
    assertEquals("tok-builder", held.subject(), "its own subject, never the desk's");
    assertFalse(keys.containsKey("agent:other"), "no token, no key: the daemon fails closed");
    assertEquals(
        "qits_tok_session",
        keys.get(SessionCredentials.FRONT_DESK_KEY).token(),
        "the desk's keys are unchanged");
    assertEquals(3, keys.size());
  }

  @Test
  void theThreeArgumentComposeNamesNoAgent() {
    assertTrue(DeskSessionCredentials.compose(project(), null, List.of()).credentials().isEmpty());
  }

  // ---- the listing's vocabulary ------------------------------------------------------------------

  private static final NamedAgents.Agent BUILDER = NamedAgents.find("ui-builder").orElseThrow();

  @Test
  void anAgentNobodyPromptedReadsStopped() {
    NamedAgentHost.AgentState state = NamedAgentHost.state(BUILDER, null, null, null, true, false);
    assertEquals(new NamedAgentHost.AgentState(
        "ui-builder", "project.ui-builder", "STOPPED", null, null), state);
  }

  @Test
  void aHeldPromptReadsStartingWhileTheDeskIsWanted() {
    NamedAgent row = agent("ui-builder", NOW, "sess-ui-1");
    row.pendingPrompt = "Pitch a calmer header.";
    assertEquals("STARTING", NamedAgentHost.state(BUILDER, row, null, null, true, true).state());
    assertEquals(
        "STOPPED",
        NamedAgentHost.state(BUILDER, row, null, null, false, true).state(),
        "a desk nobody wants is not coming up");
    assertEquals(
        "sess-ui-1", NamedAgentHost.state(BUILDER, row, null, null, true, true).sessionId());
  }

  @Test
  void theDaemonsWordDecidesWhileItNamesTheAgent() {
    NamedAgent row = agent("ui-builder", NOW, "sess-old");
    NamedAgentHost.Hosted running = new NamedAgentHost.Hosted("RUNNING", "cmd-1", "sess-ui-1");

    NamedAgentHost.AgentState idle = NamedAgentHost.state(BUILDER, row, running, "IDLE", true, true);
    assertEquals("IDLE", idle.state());
    assertEquals("cmd-1", idle.commandId(), "the terminal proxy's command");
    assertEquals("sess-ui-1", idle.sessionId());
    assertEquals(
        "BUSY", NamedAgentHost.state(BUILDER, row, running, "BUSY", true, true).state());
    assertEquals(
        "BUSY",
        NamedAgentHost.state(BUILDER, row, running, "WAITING", true, true).state(),
        "a turn waiting on a person is not over");
    assertEquals(
        "IDLE",
        NamedAgentHost.state(BUILDER, row, running, null, true, true).state(),
        "no activity reported yet: running, between turns");
    assertEquals(
        "STARTING",
        NamedAgentHost.state(
                BUILDER, row, new NamedAgentHost.Hosted("STARTING", null, null), null, true, true)
            .state());
    assertEquals(
        "FAILED",
        NamedAgentHost.state(
                BUILDER, row, new NamedAgentHost.Hosted("FAILED", null, null), null, true, true)
            .state());
    assertEquals(
        "sess-old",
        NamedAgentHost.state(
                BUILDER, row, new NamedAgentHost.Hosted("STOPPED", null, null), null, true, true)
            .sessionId(),
        "the stored session stands while the daemon names none");
  }
}
