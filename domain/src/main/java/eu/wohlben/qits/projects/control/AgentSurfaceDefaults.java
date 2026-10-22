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
 * (which is a value and not an absence — the epics desk steers with nothing, deliberately) and so
 * does {@code project.work}, the one desk that replaced both (qits-403), every
 * surface carries skip-permissions because that is what every launch does unconditionally today, and
 * each attaches the one platform MCP server, {@code qits} (qits-1174). Everything downstream of here assumes "configured" and "hardcoded" are
 * the same thing on day one.
 *
 * <p><b>Where each value was read from</b>, so the next person can check rather than trust:
 *
 * <ul>
 *   <li>{@code qits-coding-agents/…/agents/AgentLaunchService.java} — the composed-run prompt, the
 *       bootstrap turn, and {@code skipPermissions()} on all three render paths.
 *   <li>{@code qits-projects-daemon/…/ControlSocket.java} — the projects daemon's bootstrap turn.
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

  /**
   * The one front desk at {@code :project/work} — the epics and tickets desks merged (qits-310).
   * Projects daemon, {@code PROJECT} scope. <b>Every desk session launches with this key</b>
   * (qits-403); the two desks it replaced, {@code project.epics} and {@code project.tickets}, are
   * retired (qits-404) — their rows are gone, along with the constants that used to name them.
   *
   * <p><b>It is a cross-repository WIRE CONTRACT, not a label.</b> {@code AgentSurface} in {@code
   * eu.wohlben.qits:qits-coding-agents} is a closed list and {@code AgentSurface.of} refuses anything
   * outside it, so both daemons answer a 400 to a spelling neither was released with. {@code
   * project.work} entered that list in qits-coding-agents 2026.927.4238 ({@code
   * AgentSurface.PROJECT_WORK}), and the daemons carrying it deploy before this service launches
   * with it. This service does not depend on that library, so the key is spelled here as a string;
   * nothing in this repository would notice a misspelling — the store is permissive on purpose
   * ({@code surface_key} carries no check constraint, V16, and an unknown key reads as {@link
   * #neutralDefault} rather than 404ing) — which is why {@code AgentSurfaceDefaultsTest} pins the
   * literal.
   *
   * <p><b>It ships as the epics desk shipped</b> — an empty system prompt — because the merged desk
   * inherits the refinement surface's
   * configuration, not the triage one's. V30 copied the stored {@code project.epics} row onto this
   * key for the same reason, so an estate that had edited the epics desk carried the edit across
   * before that row was retired (qits-404).
   */
  public static final String PROJECT_WORK = "project.work";

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
   * The delivery desk — the project's front desk running the delivery loop (qits-1440). Projects
   * daemon, {@code REPOSITORY} scope, interactive. A surface of its own rather than a second {@link
   * #PROJECT_WORK} session, because the daemon tells its live sessions apart by surface.
   *
   * <p>A wire contract like {@link #PROJECT_WORK}: {@code AgentSurface.PROJECT_DELIVERY} in
   * qits-coding-agents (qits-1495), appended last to {@code AgentSurface.KNOWN}. A daemon built
   * against an older library refuses the key when it reads the injected document, so this ships
   * only once the desk's daemon carries that release.
   */
  public static final String PROJECT_DELIVERY = "project.delivery";

  /**
   * The eight surfaces sessions launch with, in the order the editor lists them: the project's one
   * front desk, the four a human opens in a workspace container, the two composed runs, then the
   * delivery desk. This is what the boot seed writes and what the listing leads with. The order
   * mirrors {@code AgentSurface.KNOWN} in qits-coding-agents, which is why {@link
   * #PROJECT_DELIVERY} is appended last rather than placed beside the desk it runs in.
   *
   * <p>Adding one is a line here and a shipped default below — no migration, no schema change.
   * That openness is the point: an unknown surface reads as {@link #neutralDefault} rather than
   * 404ing, so a daemon that knows a surface this store has not been told about still launches.
   * (V30 was the one exception, and it was a data copy, not a schema change: see {@link
   * #PROJECT_WORK}.)
   */
  public static final List<String> SURFACES =
      List.of(
          PROJECT_WORK,
          EPIC_CHAT,
          EPIC_AGENT,
          WORKSPACE_CHAT,
          WORKSPACE_AGENT,
          EPIC_AUTONOMOUS,
          TICKET_DISPATCH,
          PROJECT_DELIVERY);

  // ---------------------------------------------------------------------------------------------
  // The platform's own MCP server
  // ---------------------------------------------------------------------------------------------

  /**
   * The central platform-access server — the {@code qits} CLI served over MCP (qits-630), one
   * process at {@code <env>-qits-platform-access-mcp-service}, and since qits-1174 the <b>only</b>
   * platform MCP server an agent gets. It takes no narrowing: it is scoped by the caller's bearer,
   * not by its url, so a session can do through it exactly what its credential may do. The
   * read-only mark does apply: the harness appends {@code agentReadOnly=true} to its url, and the
   * server then serves only its read tools. Its key is mirrored in qits-coding-agents' {@code
   * AgentConfigurationDocument.RESERVED_SERVER_KEYS}.
   */
  public static final String SERVER_QITS = "qits";

  /**
   * The built-in servers a surface can attach. The editor draws its built-in toggles from this list
   * (the surface listing answers it as {@code builtInServers}), so a key added here shows up there
   * with no SPA change.
   */
  public static final List<String> BUILT_IN_SERVERS = List.of(SERVER_QITS);

  /**
   * The per-service servers qits-1174 retired: qits-projects' own {@code repository} server,
   * {@code observability} and {@code actions}. No surface may attach them, and V42 deleted every
   * stored attachment to them.
   */
  public static final List<String> RETIRED_SERVERS =
      List.of("repository", "observability", "actions");

  /**
   * The keys an external catalog entry may not claim: the built-in server and the retired ones. An
   * entry under a built-in key would silently displace the platform's server in the rendered {@code
   * mcpServers} object. The retired keys stay reserved because a harness or an old document may
   * still treat them as the platform's own, and qits-coding-agents refuses an external entry under
   * any of them; a key accepted here would then break the launch.
   */
  public static final List<String> RESERVED_SERVER_KEYS =
      Stream.concat(RETIRED_SERVERS.stream(), BUILT_IN_SERVERS.stream()).toList();

  // ---------------------------------------------------------------------------------------------
  // The prompts
  // ---------------------------------------------------------------------------------------------

  /**
   * The steering the two composed runs launch with, copied byte for byte from the library's {@code
   * AgentLaunchService.COMPOSED_RUN_PROMPT} (surfaced there as {@code
   * AgentSurfaceConfigurations.shippedSystemPrompt}).
   *
   * <p>Copied as a text block with its line continuations intact rather than reflowed: the daemon
   * renders it as a shell-quoted argument, so a re-wrap here would be a silent behaviour change that
   * only a live launch could catch. If you are editing this to say something new, edit the row
   * through the editor — the constant is the shipped default and moves only when the library's does.
   *
   * <p><b>One constant for both surfaces, and that is the decision rather than an economy.</b> What
   * it says is how to run an orchestrated session — plan, delegate, verify — and none of that
   * depends on whether the run is an epic's task prompt spanning a project or a ticket inside one
   * freshly cut workspace. What differs between the two is the bootstrap turn and the servers, which
   * are already per surface. Two near-identical copies would drift the first time somebody edited
   * one, and the drift would be invisible: both render a launch nobody watches.
   */
  public static final String COMPOSED_RUN_PROMPT =
      """
      You are orchestrating this run rather than typing it. The task prompt says what to \
      build; the order it is built in, the checking that it works and the report at the end \
      are yours, and the bulk of the code is not.

      Read the task prompt and the repository first, then plan the work as a sequence of \
      self-contained programming tasks and hand each one to a subagent, stated completely — \
      the files, the change, what done looks like. Planning, sequencing and the final report \
      stay in this session. Choose each subagent's model by scope and expected difficulty: \
      Sonnet for mechanical, narrow, well-specified edits, and Opus for anything wide, \
      ambiguous or architecturally load-bearing.

      Delegating the work does not delegate the verification. A subagent's report is a claim \
      and not a result: build the project yourself, run the tests yourself, and read what \
      changed before you say the work is done.\
      """;

  /**
   * The projects daemon's {@code TASK_PROMPT_BOOTSTRAP} — the one-sentence turn {@code
   * epic.autonomous} is seeded with. It carries the user's authority while the work item, read
   * through the {@code qits} server's {@code work_details} tool, carries the content.
   */
  public static final String PROJECT_TASK_PROMPT_BOOTSTRAP =
      "Read the work item for this project with the qits MCP server's work_details tool, then"
          + " implement what it describes.";

  /**
   * The workspace daemon's {@code TASK_PROMPT_BOOTSTRAP}, which says "workspace" where the projects
   * one says "project". The divergence is real — two constants in two repositories — and is kept
   * rather than harmonised, because harmonising it here would change what one of the two surfaces
   * pushes on the day the store ships.
   */
  public static final String WORKSPACE_TASK_PROMPT_BOOTSTRAP =
      "Read the work item this workspace is for with the qits MCP server's work_details tool, then"
          + " implement what it describes.";

  /**
   * The delivery desk's brief — the system prompt {@link #PROJECT_DELIVERY} ships with, verbatim
   * from qits-1440's dossier page "The desk's brief". The owner's rules are enforced by the
   * platform (the dispatch and approve doors, {@code .config/qits/project.yml}'s {@code
   * front_desk.delivery}); this text only tells the desk how to work inside them. Every command
   * line in it was checked against the CLI that carries {@code qits work dispatch} and {@code qits
   * projects delivery}, and a command that changes shape changes it here and on the dossier page
   * together. Editing it afterwards is the owner's business, through the editor.
   *
   * <p>Line breaks are the dossier's, kept as real newlines: the desk reads it as prose with
   * numbered steps, so the layout is part of the text.
   */
  public static final String DELIVERY_DESK_PROMPT =
      """
      You are the delivery desk of this project. You keep its delivery loop moving while nobody is at a
      workstation. You hold qits:desk for this project only. The platform enforces the owner's rules; this brief
      tells you how to work inside them. Read them first, every pass:
        qits projects delivery --project <this project>

      You are woken by a turn "Run one pass of the delivery loop". Do one pass, then end your turn.
      Never loop or sleep inside a turn.

      Each pass, in this order:

      1. Ship verified work. For each epic or ticket of the project at VERIFIED, find the wrapper's release
         request whose sources name its branch (qits release-request list --project <this project>). If its
         approval waits on manual-review and every other gate has passed:
         - read the fold: git fetch, then git diff origin/main..<mergedSha> --ignore-submodules=none;
         - approve it at that sha: qits release-request approve --project <this project> --request <id>
           --sha <mergedSha> --note '<one line on what you read>'.
         If the fold moved since you read it, read it again. A fold that adds only gitlink bumps to
         released versions, beside content you already read, needs no new reading.
      2. Approve routine member requests. A member request held only by artifact-declarations: approve it
         once its gates pass. Anything else under .config/qits/ is the owner's; leave it.
      3. Clear blocks and waits. Read each blocked item's reason.
         - If another branch causes it, prompt that branch's agent with the fix it owes:
           qits workspaces agents prompt <work id> '<what and why>'.
         - An agent waiting on its own release rounds needs nothing. One waiting on an approval you may
           not give: say so once on its thread, then move on.
      4. Work the backlog.
         - Count the running items: epics and tickets not DONE or DROPPED with an active agent. Check the
           runner's free slots (qits workspaces runners list).
         - While running < max_running and a slot is free, take the newest open epic or ticket by
           creation time (last in, first out, epics and tickets mixed). Skip blocked and parked ones.
         - First comment on it: "Dispatched by the delivery desk, newest first. Check that it still
           applies first: it may be fixed, superseded or obsolete. If so, drop it with the right outcome
           and a comment saying why."
         - Then: qits work dispatch --entity <id> --mode FLOW --rule backlog-newest-first.
         - A REFINED item: dispatch it with --rule start-refined only if start_refined is on. Otherwise
           leave it for the owner.
         - An unrefined item that needs the owner's design: ask the owner (step 6) instead of dispatching.
      5. Keep the backlog honest. When you meet an item that is plainly obsolete, check it against main
         and drop it with the right outcome and a comment saying why. If you are unsure, do not drop it.
      6. Ask the owner only what needs the owner. One sentence per question, on the item's thread. Then
         block the item with that sentence as the reason, so the board shows it waits on the owner.

      Never: decline a request, approve outside the rules you read, write under /workspace, move a
      gitlink, or open a release request yourself. Name every item by its qualified id. When a door
      answers 403 or 409, the answer is the rule: report it once on the item's thread and go on.\
      """;

  /**
   * The turn that wakes the delivery desk. The projects daemon's tick sends its own spelling with an
   * instant appended; this is what a session launched on the surface starts with.
   */
  public static final String DELIVERY_DESK_INITIAL_PROMPT = "Run one pass of the delivery loop.";

  // ---------------------------------------------------------------------------------------------
  // The seed
  // ---------------------------------------------------------------------------------------------

  /**
   * The central {@code qits} server, the one platform server on every surface (qits-630,
   * qits-1174): no narrowing, because the bearer on each call scopes it (see {@link #SERVER_QITS}),
   * and an empty tool list, because on Claude the library pre-approves {@code mcp__qits__*} wherever
   * {@code qits} is attached; a wildcard stored here would reach Kimi's {@code enabledTools} as a
   * bare {@code *}. {@code readOnly} is the unattended fence: the harness appends {@code
   * agentReadOnly=true} and the server then serves only its read tools.
   */
  private static List<AgentMcpAttachmentDto> qits(boolean readOnly) {
    return List.of(new AgentMcpAttachmentDto(SERVER_QITS, false, false, false, readOnly, List.of()));
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
        // NO EXTERNAL MCP SERVERS ON ANY SHIPPED SURFACE, and there never can be one. The catalog is
        // operator-defined and starts empty; a shipped default naming an entry would name a row
        // nobody has created. External servers are attached by an edit, deliberately, and that is
        // also what keeps a fresh estate rendering byte-identically to the daemons' constants.
        List.of(),
        true);
  }

  /**
   * The shipped configurations, keyed by surface — one per entry in {@link #SURFACES}.
   *
   * <p>Read this table beside the two {@code AgentLaunchService}s; every value in it came from one.
   *
   * <p>{@code project.epics} and {@code project.tickets} — the two desks {@link #PROJECT_WORK}
   * replaced — are not here. They resolved to their shipped defaults for a time after qits-403
   * retired their launch (qits-403's {@code RETIRING} list), so a container born before the switch
   * could still read the document it was created with; qits-404 deleted both their stored rows and
   * the constants that named them, so they now fall through to {@link #neutralDefault} like any
   * other key nobody has heard of.
   */
  public static final Map<String, AgentSurfaceConfigurationDto> SHIPPED = shipped();

  private static Map<String, AgentSurfaceConfigurationDto> shipped() {
    Map<String, AgentSurfaceConfigurationDto> map = new LinkedHashMap<>();
    // The one front desk ships as the epics desk did: an empty prompt, like the library's.
    map.put(PROJECT_WORK, surface(PROJECT_WORK, true, "", "", qits(false)));
    // The four workspace surfaces send byte-identical launch requests today — which is the whole
    // reason the surface had to become a value that travels before any of this could be configured.
    // They are seeded identically here, and that identity is what an editor can now break.
    map.put(EPIC_CHAT, surface(EPIC_CHAT, true, "", "", qits(false)));
    map.put(EPIC_AGENT, surface(EPIC_AGENT, false, "", "", qits(false)));
    map.put(WORKSPACE_CHAT, surface(WORKSPACE_CHAT, true, "", "", qits(false)));
    map.put(WORKSPACE_AGENT, surface(WORKSPACE_AGENT, false, "", "", qits(false)));
    // The two composed runs: a chat steered by the one orchestration prompt both of them ship, with
    // the bootstrap turn pushed over stdin and the qits url read-only marked, so the central server
    // serves only its read tools. The prompt is shared and the bootstrap turn is not — see
    // COMPOSED_RUN_PROMPT for why that split is the right one.
    map.put(
        EPIC_AUTONOMOUS,
        surface(
            EPIC_AUTONOMOUS,
            true,
            COMPOSED_RUN_PROMPT,
            PROJECT_TASK_PROMPT_BOOTSTRAP,
            qits(true)));
    map.put(
        TICKET_DISPATCH,
        surface(
            TICKET_DISPATCH,
            true,
            COMPOSED_RUN_PROMPT,
            WORKSPACE_TASK_PROMPT_BOOTSTRAP,
            qits(true)));
    // The delivery desk: the one front desk's fields, steered by the brief and woken by one turn.
    map.put(
        PROJECT_DELIVERY,
        surface(
            PROJECT_DELIVERY,
            true,
            DELIVERY_DESK_PROMPT,
            DELIVERY_DESK_INITIAL_PROMPT,
            qits(false)));
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
   * service cannot know what an unknown surface is for, and attaching the {@code qits} server
   * speculatively would hand a session tools nobody chose. A session with no
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
        List.of(),
        true);
  }
}
