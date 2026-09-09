package eu.wohlben.qits.projects.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.dto.AgentMcpAttachmentDto;
import eu.wohlben.qits.projects.dto.AgentSurfaceConfigurationDto;
import eu.wohlben.qits.projects.entity.AgentHarness;
import eu.wohlben.qits.projects.entity.AgentPermissionMode;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The seed against the constants it was taken from.
 *
 * <p><b>This is the whole safety of the feature.</b> Everything downstream assumes "configured" and
 * "hardcoded" are the same thing on day one: the store is turned on and no launch moves a byte. So
 * the values below are written out as literals rather than derived from anything — a test that read
 * its expectations off {@link AgentSurfaceDefaults} would rename itself along with the bug.
 *
 * <p><b>TODO (epic task a3d4a7ff, feature 61b46060): replace these literals with the real
 * equivalence test.</b> What this feature actually wants is each seeded configuration rendered
 * through the shared harness library and compared against the command that library renders with no
 * configuration at all — the byte-for-byte proof, per surface, per mode, per harness. That library,
 * {@code eu.wohlben.qits:qits-coding-agents}, is being extracted from the two daemons' in-repo
 * copies right now (feature 1f7b78a1) and is not published yet, so this repository cannot depend on
 * it and the cross-check cannot exist in this commit. What is here instead is the honest half: the
 * daemons' literals, copied in, asserted. When the library is released, delete the copies and render
 * instead.
 *
 * <p>Where each expectation came from, for whoever checks it:
 *
 * <ul>
 *   <li>{@code qits-projects-daemon/qits-coding-agents/src/main/java/eu/wohlben/qits/projectsdaemon/agents/AgentLaunchService.java}
 *       — {@code TASK_PROMPT_BOOTSTRAP} (:83), {@code READ_ONLY_REPOSITORY_TOOLS} (:111), {@code
 *       TICKETS_DESK_PROMPT} (:142), {@code renderChat}/{@code renderAutonomousChat}/{@code
 *       renderInteractive}'s {@code skipPermissions()} (:529, :598, :629), {@code
 *       systemPromptFor} (:650), {@code serversFor} (:690).
 *   <li>{@code qits-workspace-daemon/qits-coding-agents/src/main/java/eu/wohlben/qits/workspacedaemon/agents/AgentLaunchService.java}
 *       — {@code TASK_PROMPT_BOOTSTRAP} (:84), {@code READ_ONLY_ACTION_TOOLS} (:94), {@code
 *       READ_ONLY_REPOSITORY_TOOLS} (:118), {@code TICKET_THREAD_TOOLS} (:155), {@code
 *       TICKET_RESOLUTION_TOOLS} (:182), {@code TASK_IMPLEMENTATION_TOOLS} (:217), {@code
 *       REPOSITORY_TOOLS} (:224), {@code READ_ONLY_OBSERVABILITY_TOOLS} (:243), {@code serversFor}
 *       (:736).
 *   <li>{@code projects-daemon/src/main/java/eu/wohlben/qits/projectsdaemon/DaemonAgentDefaults.java}
 *       (:25) and {@code application.properties} (:104-105) — the CLAUDE fallback and activity
 *       tracking on.
 *   <li>{@code AgentLaunchService.claudeChatProtocol} (projects :540, workspace :622) — remote
 *       control on every Claude chat, over the SDK control channel, and on no interactive launch.
 * </ul>
 */
public class AgentSurfaceDefaultsTest {

  // -------------------------------------------------------------------------------------------
  // The literals, copied from the daemons
  // -------------------------------------------------------------------------------------------

  /** Copied from the projects daemon, character for character. */
  private static final List<String> PROJECTS_REPOSITORY_TOOLS =
      List.of(
          "mcp__repository__listRepositories",
          "mcp__repository__listBranches",
          "mcp__repository__listCommits",
          "mcp__repository__listCommitChanges",
          "mcp__repository__getCommitFileDiff",
          "mcp__repository__listActions",
          "mcp__repository__taskPrompt",
          "mcp__repository__list_epics",
          "mcp__repository__get_epic",
          "mcp__repository__list_tickets",
          "mcp__repository__get_ticket");

  /** Copied from the workspace daemon: its reads, then its three named write exceptions. */
  private static final List<String> WORKSPACE_REPOSITORY_TOOLS =
      List.of(
          "mcp__repository__listRepositories",
          "mcp__repository__listBranches",
          "mcp__repository__listWorkspaces",
          "mcp__repository__listCommits",
          "mcp__repository__listCommitChanges",
          "mcp__repository__getCommitFileDiff",
          "mcp__repository__listActions",
          "mcp__repository__taskPrompt",
          "mcp__repository__list_tickets",
          "mcp__repository__get_ticket",
          "mcp__repository__list_epics",
          "mcp__repository__get_epic",
          "mcp__repository__add_ticket_comment",
          "mcp__repository__update_ticket_comment",
          "mcp__repository__transition_ticket",
          "mcp__repository__mark_task_implemented");

  private static final List<String> OBSERVABILITY_TOOLS =
      List.of(
          "mcp__observability__telemetryErrors",
          "mcp__observability__telemetryTrace",
          "mcp__observability__telemetrySlowSpans",
          "mcp__observability__telemetrySearchLogs",
          "mcp__observability__telemetryMetrics");

  private static final List<String> ACTION_TOOLS =
      List.of(
          "mcp__actions__listGlobalActions",
          "mcp__actions__getGlobalAction",
          "mcp__actions__listRepositoryActions",
          "mcp__actions__getRepositoryAction");

  /**
   * The projects daemon's bootstrap turn. Note the workspace daemon's says "workspace" where this
   * says "project" — two constants in two repositories, and the divergence is preserved.
   */
  private static final String PROJECT_BOOTSTRAP =
      "Fetch the current task prompt for this project with the taskPrompt tool, then implement what"
          + " it describes.";

  private static final String WORKSPACE_BOOTSTRAP =
      "Fetch the current task prompt for this workspace with the taskPrompt tool, then implement"
          + " what it describes.";

  /**
   * The tickets desk prompt as the daemon's text block renders it — the same block, with the same
   * line continuations, so this literal and the shipped constant are two independent copies of the
   * bytes rather than one copy read twice.
   */
  private static final String TICKETS_DESK_PROMPT =
      """
      You are this project's tickets front desk: intake and triage for the small-scoped work \
      that sits beside the epic plans — bugs and improvements.

      Work through the repository MCP server's ticket tools. Survey with list_tickets and \
      get_ticket before anything else; a ticket carries its own comment thread, so get_ticket \
      is the whole conversation and not just the fields. File with create_ticket, typed BUG or \
      IMPROVEMENT, and say in the description how to see the problem, not only that it exists. \
      Assign with update_ticket. Discuss on the thread with add_ticket_comment, and correct \
      your own notes with update_ticket_comment rather than posting a second one after the \
      first. Resolve with transition_ticket once the work is confirmed done, and reopen the \
      same way when it turns out not to be: resolving is reversible, and nothing about a ticket \
      freezes.

      When something is too big for a ticket — when it needs a plan rather than a fix — say so \
      and point at the epics desk. Do not file an epic from here.\
      """;

  // -------------------------------------------------------------------------------------------
  // The vocabulary
  // -------------------------------------------------------------------------------------------

  @Test
  public void theVocabularyIsTheEightSurfacesTheProductHas() {
    assertEquals(
        List.of(
            "project.epics",
            "project.tickets",
            "epic.chat",
            "epic.agent",
            "workspace.chat",
            "workspace.agent",
            "epic.autonomous",
            "ticket.dispatch"),
        AgentSurfaceDefaults.SURFACES,
        "the six a human opens plus the two composed runs; a ninth is a line here and a default"
            + " below, never a migration");
    assertEquals(8, AgentSurfaceDefaults.SHIPPED.size());
    for (String surface : AgentSurfaceDefaults.SURFACES) {
      assertNotNull(
          AgentSurfaceDefaults.SHIPPED.get(surface), surface + " is in the vocabulary but unseeded");
    }
  }

  @Test
  public void theReservedServerKeysAreTheThreePlatformServers() {
    assertEquals(
        List.of("repository", "observability", "actions"), AgentSurfaceDefaults.BUILT_IN_SERVERS);
  }

  // -------------------------------------------------------------------------------------------
  // What every surface shares
  // -------------------------------------------------------------------------------------------

  @Test
  public void everySurfaceShipsClaudeSkipPermissionsAndActivityTracking() {
    for (String surface : AgentSurfaceDefaults.SURFACES) {
      AgentSurfaceConfigurationDto shipped = AgentSurfaceDefaults.SHIPPED.get(surface);
      assertEquals(
          AgentHarness.CLAUDE,
          shipped.harness(),
          surface + ": nothing sets qits.agent.default-type, so DaemonAgentDefaults falls to CLAUDE");
      assertEquals(
          AgentPermissionMode.SKIP_PERMISSIONS,
          shipped.permissionMode(),
          surface + ": every render path in both daemons calls skipPermissions(), unconditionally");
      assertTrue(
          shipped.activityTracking(),
          surface + ": qits.agent.activity-tracking-enabled defaults to true in both daemons");
      assertEquals("", shipped.model(), surface + ": no launch shape passes --model today");
      assertEquals("", shipped.effort(), surface + ": --effort is not wired at all today");
    }
  }

  /**
   * The one flow that passes a model today is not a surface.
   *
   * <p>{@code DaemonAgentDefaults.refinementModel} is read in exactly one place — the workspace
   * daemon's {@code PromptRefinementService.refine}, which runs the harness once through {@code
   * ProcessRunner} and consumes its stdout, defaulting to {@code "haiku"} on Claude. That is not a
   * session: it starts no command, has no transcript and appears in no commands list, so no key in
   * the vocabulary names it. Seeding its model onto a surface would render a {@code --model} flag
   * where none renders today, which is the one thing this seed must not do.
   */
  @Test
  public void noSurfaceCarriesTheRefinementModelBecauseRefinementIsNotASurface() {
    assertTrue(
        AgentSurfaceDefaults.SHIPPED.values().stream().allMatch(s -> s.model().isEmpty()),
        "PromptRefinementService's haiku default belongs to a one-shot run, not to a surface");
  }

  // -------------------------------------------------------------------------------------------
  // The two project desks
  // -------------------------------------------------------------------------------------------

  @Test
  public void theEpicsDeskShipsAnEmptySystemPromptWhichIsAValueAndNotAnAbsence() {
    AgentSurfaceConfigurationDto epics = AgentSurfaceDefaults.SHIPPED.get("project.epics");
    assertNotNull(epics.systemPrompt(), "empty, never null — systemPromptFor(EPICS) renders nothing");
    assertEquals("", epics.systemPrompt());
    assertEquals("", epics.initialPrompt());
    assertEquals(
        List.of(
            new AgentMcpAttachmentDto("repository", true, false, false, false, PROJECTS_REPOSITORY_TOOLS)),
        epics.mcpServers(),
        "the projects daemon attaches exactly one server, project-narrowed, at PROJECT scope");
  }

  @Test
  public void theTicketsDeskCarriesTheDeskPromptByteForByte() {
    AgentSurfaceConfigurationDto tickets = AgentSurfaceDefaults.SHIPPED.get("project.tickets");
    assertEquals(TICKETS_DESK_PROMPT, tickets.systemPrompt());
    assertEquals(
        TICKETS_DESK_PROMPT, AgentSurfaceDefaults.TICKETS_DESK_PROMPT, "the constant and the copy");
    assertEquals(
        List.of(
            new AgentMcpAttachmentDto("repository", true, false, false, false, PROJECTS_REPOSITORY_TOOLS)),
        tickets.mcpServers(),
        "the tickets desk is the same container and the same scope as the epics desk");
  }

  // -------------------------------------------------------------------------------------------
  // The four workspace surfaces
  // -------------------------------------------------------------------------------------------

  /**
   * They are seeded identically, and that identity is the fact this epic exists to make editable:
   * today the workspace daemon cannot tell these four apart at all.
   */
  @Test
  public void theFourWorkspaceSurfacesShipTheRepositoryAndObservabilityPair() {
    List<AgentMcpAttachmentDto> expected =
        List.of(
            new AgentMcpAttachmentDto(
                "repository", true, true, true, false, WORKSPACE_REPOSITORY_TOOLS),
            new AgentMcpAttachmentDto(
                "observability", false, true, true, false, OBSERVABILITY_TOOLS));
    for (String surface : List.of("epic.chat", "epic.agent", "workspace.chat", "workspace.agent")) {
      AgentSurfaceConfigurationDto shipped = AgentSurfaceDefaults.SHIPPED.get(surface);
      assertEquals(expected, shipped.mcpServers(), surface + ": serversFor(REPOSITORY)");
      assertEquals("", shipped.systemPrompt(), surface + ": the workspace daemon has no desk axis");
      assertEquals("", shipped.initialPrompt(), surface + ": the caller composes its own first turn");
    }
  }

  /**
   * The {@code actions} server is attached by no surface, and its absence is asserted rather than
   * assumed: it is wired only at the {@code ACTIONS} scope — the "configure this repository"
   * session — and no surface in the vocabulary launches at that scope.
   */
  @Test
  public void noSurfaceAttachesTheActionsServerThoughItsShippedToolsExist() {
    assertTrue(
        AgentSurfaceDefaults.SHIPPED.values().stream()
            .flatMap(s -> s.mcpServers().stream())
            .noneMatch(a -> "actions".equals(a.server())));
    assertEquals(ACTION_TOOLS, AgentSurfaceDefaults.shippedToolsFor("actions"));
  }

  // -------------------------------------------------------------------------------------------
  // The two composed runs
  // -------------------------------------------------------------------------------------------

  @Test
  public void theTwoComposedRunsCarryTheirBootstrapTurnAndReadOnlyMarkedServers() {
    AgentSurfaceConfigurationDto autonomous = AgentSurfaceDefaults.SHIPPED.get("epic.autonomous");
    assertEquals(PROJECT_BOOTSTRAP, autonomous.initialPrompt());
    assertEquals(
        List.of(
            new AgentMcpAttachmentDto("repository", true, true, false, true, PROJECTS_REPOSITORY_TOOLS)),
        autonomous.mcpServers(),
        "launchAutonomous renders at REPOSITORY scope with every url read-only marked");

    AgentSurfaceConfigurationDto dispatch = AgentSurfaceDefaults.SHIPPED.get("ticket.dispatch");
    assertEquals(
        WORKSPACE_BOOTSTRAP,
        dispatch.initialPrompt(),
        "the workspace daemon's constant says 'workspace' where the projects one says 'project'");
    assertTrue(
        dispatch.mcpServers().stream().allMatch(AgentMcpAttachmentDto::readOnly),
        "both of the pair are read-only marked on an autonomous run");
  }

  // -------------------------------------------------------------------------------------------
  // Remote control
  // -------------------------------------------------------------------------------------------

  /**
   * On for chat, off for interactive — the opposite of the intuition, and what the daemons do.
   *
   * <p>The chat shapes run {@code --print --input-format stream-json}, where {@code
   * --remote-control} is dropped by the harness, so both daemons enable it over the SDK control
   * channel instead ({@code claudeChatProtocol} → {@code StreamJsonChatProtocol}, named after the
   * checked-out branch). An interactive launch enables nothing. Seeding the intuition rather than
   * the behaviour would have switched six sessions' remote bridge off the day the store shipped.
   */
  @Test
  public void remoteControlIsOnForEveryChatSurfaceAndOffForTheInteractiveOnes() {
    for (String chat :
        List.of(
            "project.epics",
            "project.tickets",
            "epic.chat",
            "workspace.chat",
            "epic.autonomous",
            "ticket.dispatch")) {
      assertTrue(
          AgentSurfaceDefaults.SHIPPED.get(chat).remoteControl(),
          chat + " is a chat, and claudeChatProtocol bridges every chat");
    }
    for (String interactive : List.of("epic.agent", "workspace.agent")) {
      assertFalse(
          AgentSurfaceDefaults.SHIPPED.get(interactive).remoteControl(),
          interactive + " is an interactive launch, and nothing enables the bridge on one");
    }
  }

  // -------------------------------------------------------------------------------------------
  // The unknown surface
  // -------------------------------------------------------------------------------------------

  @Test
  public void anUnknownSurfaceReadsAsAShippedDefaultRatherThanNothing() {
    AgentSurfaceConfigurationDto unknown = AgentSurfaceDefaults.shippedDefault("ninth.surface");
    assertEquals("ninth.surface", unknown.surface());
    assertEquals(AgentHarness.CLAUDE, unknown.harness());
    assertEquals(AgentPermissionMode.SKIP_PERMISSIONS, unknown.permissionMode());
    assertTrue(unknown.shipped());
    assertEquals(
        List.of(),
        unknown.mcpServers(),
        "no servers: this service cannot know what an unknown surface addresses, and the wrong"
            + " servers are worse than none");
  }

  @Test
  public void aKnownSurfaceResolvesToItsSeededDefaultAndIsFlaggedShipped() {
    assertEquals(
        AgentSurfaceDefaults.SHIPPED.get("project.tickets"),
        AgentSurfaceDefaults.shippedDefault("project.tickets"));
    assertTrue(AgentSurfaceDefaults.SHIPPED.get("project.tickets").shipped());
  }
}
