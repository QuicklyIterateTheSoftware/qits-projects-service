package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.dto.AgentMcpAttachmentDto;
import eu.wohlben.qits.projects.dto.AgentSurfaceConfigurationDto;
import eu.wohlben.qits.projects.entity.AgentHarness;
import eu.wohlben.qits.projects.entity.AgentPermissionMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * What every session surface ships as — the constants the two daemons hardcode today, copied here so
 * they can become rows.
 *
 * <p><b>The whole safety of this feature is that these values are what the daemons already
 * render.</b> Turning the store on must change nothing: {@code project.tickets} carries the tickets
 * desk's text block byte for byte, {@code project.epics} carries an <em>empty</em> system prompt
 * (which is a value and not an absence — the epics desk steers with nothing, deliberately), every
 * surface carries skip-permissions because that is what every launch does unconditionally today, and
 * each attaches exactly the MCP servers its host daemon's {@code serversFor} attaches at the scope
 * that surface launches with. Everything downstream of here assumes "configured" and "hardcoded" are
 * the same thing on day one.
 *
 * <p><b>Where each value was read from</b>, so the next person can check rather than trust:
 *
 * <ul>
 *   <li>{@code qits-projects-daemon/qits-coding-agents/…/agents/AgentLaunchService.java} — the
 *       tickets prompt, the projects-side repository tool list, this daemon's {@code
 *       serversFor}, the bootstrap turn, and {@code skipPermissions()} on all three render paths.
 *   <li>{@code qits-workspace-daemon/qits-coding-agents/…/agents/AgentLaunchService.java} — the
 *       workspace-side repository tool list (its reads plus the three named write exceptions), the
 *       observability and actions lists, and that daemon's {@code serversFor}.
 *   <li>{@code projects-daemon/…/DaemonAgentDefaults} and its {@code application.properties} —
 *       {@code CLAUDE} as the harness nobody configured otherwise, and {@code
 *       qits.agent.activity-tracking-enabled=true}.
 * </ul>
 *
 * <p><b>Two things here will read as surprising and are what the code does today.</b> First, remote
 * control is seeded <em>on</em> for the chat surfaces and off for the interactive ones — the exact
 * opposite of the intuition that the flag is an interactive-only feature. It is: the chat shapes run
 * {@code --print --input-format stream-json}, where {@code --remote-control} is dropped by the
 * harness, so the daemons enable it over the SDK control channel instead ({@code
 * StreamJsonChatProtocol}, named after the checked-out branch), and an interactive launch enables
 * nothing at all. Seeding the intuition rather than the behaviour would have turned six sessions'
 * remote bridge off on the day the store shipped. Second, the two composed surfaces carry
 * <em>different</em> bootstrap sentences ("this project" vs "this workspace") because their two
 * daemons spell the constant differently; that divergence is real and is preserved rather than
 * tidied away here.
 *
 * <p>Framework-free by construction: constants and pure functions, no CDI, no configuration reading,
 * so a unit test can assert the seed against the daemons' literals without a database or a
 * container. The prompts are Java text blocks for the reason the daemons keep them as text blocks —
 * a literal is what a test can assert byte for byte.
 */
public final class AgentSurfaceDefaults {

  private AgentSurfaceDefaults() {}

  // ---------------------------------------------------------------------------------------------
  // The vocabulary
  // ---------------------------------------------------------------------------------------------

  /** The refinement agent on a project's epics overview. Projects daemon, {@code PROJECT} scope. */
  public static final String PROJECT_EPICS = "project.epics";

  /** The triage agent on a project's tickets overview. Projects daemon, {@code PROJECT} scope. */
  public static final String PROJECT_TICKETS = "project.tickets";

  /** The refining route's chat tab. Workspace daemon, {@code REPOSITORY} scope. */
  public static final String EPIC_CHAT = "epic.chat";

  /** The refining route's agent tab. Workspace daemon, {@code REPOSITORY} scope, interactive. */
  public static final String EPIC_AGENT = "epic.agent";

  /** The workspace detail route's chat tab. Workspace daemon, {@code REPOSITORY} scope. */
  public static final String WORKSPACE_CHAT = "workspace.chat";

  /** The workspace detail route's agents tab. Workspace daemon, interactive. */
  public static final String WORKSPACE_AGENT = "workspace.agent";

  /** The composed task-prompt run nobody presses a button for. Projects daemon, read-only marked. */
  public static final String EPIC_AUTONOMOUS = "epic.autonomous";

  /** The agent a ticket dispatch starts in a freshly cut workspace. Workspace daemon. */
  public static final String TICKET_DISPATCH = "ticket.dispatch";

  /**
   * The eight surfaces the platform has, in the order the editor lists them: the two project desks,
   * the four a human opens in a workspace container, then the two composed runs.
   *
   * <p>Adding a ninth is a line here and a shipped default below — no migration, no schema change.
   * That openness is the point: an unknown surface reads as {@link #neutralDefault} rather than
   * 404ing, so a daemon that knows a surface this store has not been told about still launches.
   */
  public static final List<String> SURFACES =
      List.of(
          PROJECT_EPICS,
          PROJECT_TICKETS,
          EPIC_CHAT,
          EPIC_AGENT,
          WORKSPACE_CHAT,
          WORKSPACE_AGENT,
          EPIC_AUTONOMOUS,
          TICKET_DISPATCH);

  // ---------------------------------------------------------------------------------------------
  // The platform's own MCP servers
  // ---------------------------------------------------------------------------------------------

  /** qits-projects' server: repositories, epics, tickets. */
  public static final String SERVER_REPOSITORY = "repository";

  /** qits-observability's server, renamed from {@code repository} when both services claimed it. */
  public static final String SERVER_OBSERVABILITY = "observability";

  /** The action-library server. No surface attaches it today; the {@code ACTIONS} scope does. */
  public static final String SERVER_ACTIONS = "actions";

  /**
   * The three reserved server keys. An external catalog entry claiming one of these would silently
   * displace a platform server in the rendered {@code mcpServers} object, which is why it is a
   * validation and never a merge.
   */
  public static final List<String> BUILT_IN_SERVERS =
      List.of(SERVER_REPOSITORY, SERVER_OBSERVABILITY, SERVER_ACTIONS);

  // ---------------------------------------------------------------------------------------------
  // The tool pre-approval lists, copied from the two daemons
  // ---------------------------------------------------------------------------------------------

  /**
   * The projects daemon's {@code READ_ONLY_REPOSITORY_TOOLS} — its whole {@code repository}
   * pre-approval, reads only. Eleven ids, and the snake_case half is not a slip: the epic and ticket
   * tools declare those names on this service's own MCP server and the id must match character for
   * character or the pre-approval matches nothing.
   */
  public static final List<String> PROJECTS_REPOSITORY_TOOLS =
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

  /**
   * The workspace daemon's {@code REPOSITORY_TOOLS} — its reads, then the three named write
   * exceptions concatenated in the order that daemon concatenates them.
   *
   * <p>It is a longer and <em>differently ordered</em> list than {@link
   * #PROJECTS_REPOSITORY_TOOLS} for the same server key, which is precisely why the pre-approval
   * lists are stored per attachment rather than as one constant keyed by server: the two daemons
   * disagree, deliberately and with reasons written down in each bucket's javadoc, and a single
   * constant could not seed both faithfully.
   */
  public static final List<String> WORKSPACE_REPOSITORY_TOOLS =
      Stream.of(
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
                  "mcp__repository__get_epic"),
              // TICKET_THREAD_TOOLS — commenting is additive and a comment stays editable.
              List.of(
                  "mcp__repository__add_ticket_comment", "mcp__repository__update_ticket_comment"),
              // TICKET_RESOLUTION_TOOLS — the dispatch is told to resolve its ticket once released.
              List.of("mcp__repository__transition_ticket"),
              // TASK_IMPLEMENTATION_TOOLS — the epic dispatch marks tasks as the work lands.
              List.of("mcp__repository__mark_task_implemented"))
          .flatMap(List::stream)
          .toList();

  /** The workspace daemon's {@code READ_ONLY_OBSERVABILITY_TOOLS} — the five telemetry reads. */
  public static final List<String> OBSERVABILITY_TOOLS =
      List.of(
          "mcp__observability__telemetryErrors",
          "mcp__observability__telemetryTrace",
          "mcp__observability__telemetrySlowSpans",
          "mcp__observability__telemetrySearchLogs",
          "mcp__observability__telemetryMetrics");

  /**
   * The workspace daemon's {@code READ_ONLY_ACTION_TOOLS}. No surface attaches the {@code actions}
   * server today — only the {@code ACTIONS} scope does, and no surface launches at that scope — so
   * this list is here as the shipped pre-approval for an operator who attaches it, not as part of
   * any seed.
   */
  public static final List<String> ACTION_TOOLS =
      List.of(
          "mcp__actions__listGlobalActions",
          "mcp__actions__getGlobalAction",
          "mcp__actions__listRepositoryActions",
          "mcp__actions__getRepositoryAction");

  /** The shipped pre-approval for a built-in server, or an empty list for a key nobody ships. */
  public static List<String> shippedToolsFor(String serverKey) {
    return switch (serverKey == null ? "" : serverKey) {
      case SERVER_REPOSITORY -> PROJECTS_REPOSITORY_TOOLS;
      case SERVER_OBSERVABILITY -> OBSERVABILITY_TOOLS;
      case SERVER_ACTIONS -> ACTION_TOOLS;
      default -> List.of();
    };
  }

  // ---------------------------------------------------------------------------------------------
  // The prompts
  // ---------------------------------------------------------------------------------------------

  /**
   * The tickets desk's steering, copied byte for byte from the projects daemon's {@code
   * AgentLaunchService.TICKETS_DESK_PROMPT}.
   *
   * <p>Copied as a text block with its line continuations intact rather than reflowed: the daemon
   * renders it as a shell-quoted argument and the workspace suite asserts the rendered command line
   * as a literal, so a re-wrap here would be a silent behaviour change that only a live launch could
   * catch. If you are editing this to say something new, edit the row through the editor — the
   * constant is the shipped default and moves only when the daemon's does.
   */
  public static final String TICKETS_DESK_PROMPT =
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

  /**
   * The projects daemon's {@code TASK_PROMPT_BOOTSTRAP} — the one-sentence turn {@code
   * epic.autonomous} is seeded with. It carries the user's authority while the {@code taskPrompt}
   * MCP tool carries the content, which is the whole point of the push→fetch inversion.
   */
  public static final String PROJECT_TASK_PROMPT_BOOTSTRAP =
      "Fetch the current task prompt for this project with the taskPrompt tool, then implement what"
          + " it describes.";

  /**
   * The workspace daemon's {@code TASK_PROMPT_BOOTSTRAP}, which says "workspace" where the projects
   * one says "project". The divergence is real — two constants in two repositories — and is kept
   * rather than harmonised, because harmonising it here would change what one of the two surfaces
   * pushes on the day the store ships.
   */
  public static final String WORKSPACE_TASK_PROMPT_BOOTSTRAP =
      "Fetch the current task prompt for this workspace with the taskPrompt tool, then implement"
          + " what it describes.";

  // ---------------------------------------------------------------------------------------------
  // The seed
  // ---------------------------------------------------------------------------------------------

  /**
   * The projects daemon's {@code repository} server at {@code PROJECT} scope: {@code
   * ?projectId=<id>}, no narrowing beyond it, so the session sees every repository in the project.
   */
  private static AgentMcpAttachmentDto projectScopedRepository(boolean readOnly) {
    return new AgentMcpAttachmentDto(
        SERVER_REPOSITORY, true, false, false, readOnly, PROJECTS_REPOSITORY_TOOLS);
  }

  /**
   * The projects daemon's {@code repository} server at {@code REPOSITORY} scope: {@code
   * ?projectId=<id>&repositoryId=<name>}, narrowed to the one repository the container checked out.
   */
  private static AgentMcpAttachmentDto repositoryScopedRepository(boolean readOnly) {
    return new AgentMcpAttachmentDto(
        SERVER_REPOSITORY, true, true, false, readOnly, PROJECTS_REPOSITORY_TOOLS);
  }

  /**
   * The workspace daemon's pair at {@code REPOSITORY} scope — the shape all four human workspace
   * surfaces and the dispatch launch with. {@code repository} carries all three narrowings; {@code
   * observability} carries two and no {@code projectId}, because that service has no notion of one
   * and its tool filter hides everything unless both of its narrowings are present.
   *
   * <p>The {@code actions} server is deliberately absent. It is attached only at the {@code ACTIONS}
   * scope — the "configure this repository" session — and no surface in the vocabulary launches at
   * that scope, so seeding it here would attach a server no session attaches today.
   */
  private static List<AgentMcpAttachmentDto> workspacePair(boolean readOnly) {
    return List.of(
        new AgentMcpAttachmentDto(
            SERVER_REPOSITORY, true, true, true, readOnly, WORKSPACE_REPOSITORY_TOOLS),
        new AgentMcpAttachmentDto(
            SERVER_OBSERVABILITY, false, true, true, readOnly, OBSERVABILITY_TOOLS));
  }

  private static AgentSurfaceConfigurationDto surface(
      String key,
      boolean remoteControl,
      String systemPrompt,
      String initialPrompt,
      List<AgentMcpAttachmentDto> servers) {
    return new AgentSurfaceConfigurationDto(
        key,
        // CLAUDE: nothing sets qits.agent.default-type in either daemon's shipped configuration, and
        // DaemonAgentDefaults falls back to CLAUDE when it is unset.
        AgentHarness.CLAUDE,
        // Empty for every surface. The one flow that passes a model today — the workspace daemon's
        // PromptRefinementService, via DaemonAgentDefaults.refinementModel, defaulting to "haiku" —
        // is not a session surface at all: it is a one-shot `run()` that consumes stdout, not a
        // launch, and no key in the vocabulary names it. Seeding its model onto any surface would
        // render a --model flag where none renders today, which is the one thing this seed must not
        // do. See the report on task a3d4a7ff.
        "",
        // Not wired at all today: no launch shape in either daemon passes --effort.
        "",
        remoteControl,
        // Every launch shape in both daemons calls skipPermissions(), unconditionally.
        AgentPermissionMode.SKIP_PERMISSIONS,
        // qits.agent.activity-tracking-enabled=true in the projects daemon's shipped properties, and
        // the workspace daemon's @ConfigProperty carries the same defaultValue. One daemon-wide
        // boolean today; per surface from here.
        true,
        systemPrompt,
        initialPrompt,
        servers,
        true);
  }

  /**
   * The eight shipped configurations, keyed by surface and in {@link #SURFACES} order.
   *
   * <p>Read this table beside the two {@code AgentLaunchService}s; every value in it came from one.
   */
  public static final Map<String, AgentSurfaceConfigurationDto> SHIPPED = shipped();

  private static Map<String, AgentSurfaceConfigurationDto> shipped() {
    Map<String, AgentSurfaceConfigurationDto> map = new LinkedHashMap<>();
    // The epics desk steers with NOTHING — systemPromptFor(EPICS) returns null — and that emptiness
    // is the reason the desk axis could be added without touching a running launch. Remote control
    // is on: it is a chat, and the projects daemon's claudeChatProtocol bridges every chat.
    map.put(
        PROJECT_EPICS,
        surface(PROJECT_EPICS, true, "", "", List.of(projectScopedRepository(false))));
    // The one surface with a system prompt today.
    map.put(
        PROJECT_TICKETS,
        surface(
            PROJECT_TICKETS, true, TICKETS_DESK_PROMPT, "", List.of(projectScopedRepository(false))));
    // The four workspace surfaces send byte-identical launch requests today — which is the whole
    // reason the surface had to become a value that travels before any of this could be configured.
    // They are seeded identically here, and that identity is what an editor can now break.
    map.put(EPIC_CHAT, surface(EPIC_CHAT, true, "", "", workspacePair(false)));
    map.put(EPIC_AGENT, surface(EPIC_AGENT, false, "", "", workspacePair(false)));
    map.put(WORKSPACE_CHAT, surface(WORKSPACE_CHAT, true, "", "", workspacePair(false)));
    map.put(WORKSPACE_AGENT, surface(WORKSPACE_AGENT, false, "", "", workspacePair(false)));
    // The two composed runs: a chat with the bootstrap turn pushed over stdin and every server url
    // read-only marked, so this service's own ReadOnlyRepositoryToolFilter hides the mutating tools.
    map.put(
        EPIC_AUTONOMOUS,
        surface(
            EPIC_AUTONOMOUS,
            true,
            "",
            PROJECT_TASK_PROMPT_BOOTSTRAP,
            List.of(repositoryScopedRepository(true))));
    map.put(
        TICKET_DISPATCH,
        surface(
            TICKET_DISPATCH, true, "", WORKSPACE_TASK_PROMPT_BOOTSTRAP, workspacePair(true)));
    return Map.copyOf(map);
  }

  /**
   * What a surface reads as when the store holds no row for it.
   *
   * <p>A surface in the vocabulary answers its seeded default; anything else answers {@link
   * #neutralDefault} — never a 404. That is the rule the whole delivery rests on: a daemon shipped
   * ahead of this store, or one that knows a ninth surface, still launches rather than failing on a
   * configuration lookup.
   */
  public static AgentSurfaceConfigurationDto shippedDefault(String surfaceKey) {
    AgentSurfaceConfigurationDto known = SHIPPED.get(surfaceKey);
    return known != null ? known : neutralDefault(surfaceKey);
  }

  /**
   * The answer for a surface nobody has shipped a default for: the harness's own everything, no
   * prompts, no servers, and the permission mode every launch uses today.
   *
   * <p><b>No MCP servers, and that is the conservative choice rather than an oversight.</b> This
   * service cannot know what an unknown surface is addressing, and attaching the {@code repository}
   * server speculatively would hand a session tools against a scope nobody chose. A session with no
   * servers is a session that can still be talked to; one with the wrong servers is not obviously
   * anything.
   */
  public static AgentSurfaceConfigurationDto neutralDefault(String surfaceKey) {
    return new AgentSurfaceConfigurationDto(
        surfaceKey,
        AgentHarness.CLAUDE,
        "",
        "",
        false,
        AgentPermissionMode.SKIP_PERMISSIONS,
        true,
        "",
        "",
        List.of(),
        true);
  }
}
