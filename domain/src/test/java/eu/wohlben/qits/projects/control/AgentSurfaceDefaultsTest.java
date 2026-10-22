package eu.wohlben.qits.projects.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
 *   <li>{@code eu.wohlben.qits:qits-coding-agents} — {@code AgentLaunchService.TASK_PROMPT_BOOTSTRAP}
 *       (the workspace daemon's bootstrap turn), {@code skipPermissions()} on every render path, and
 *       {@code AgentLaunchService.COMPOSED_RUN_PROMPT}, surfaced as {@code
 *       AgentSurfaceConfigurations.shippedSystemPrompt}: the one orchestration prompt both composed
 *       runs ship.
 *   <li>{@code qits-projects-daemon}'s {@code ControlSocket.TASK_PROMPT_BOOTSTRAP} — the projects
 *       daemon's bootstrap turn.
 *   <li>{@code projects-daemon/src/main/java/eu/wohlben/qits/projectsdaemon/DaemonAgentDefaults.java}
 *       and {@code application.properties} — the CLAUDE fallback and activity tracking on.
 *   <li>{@code AgentLaunchService.claudeChatProtocol} — remote control on every Claude chat, over the
 *       SDK control channel, and on no interactive launch.
 * </ul>
 */
public class AgentSurfaceDefaultsTest {

  // -------------------------------------------------------------------------------------------
  // The literals, copied from the daemons
  // -------------------------------------------------------------------------------------------

  /**
   * The projects daemon's bootstrap turn. Note the workspace daemon's says "workspace" where this
   * says "project" — two constants in two repositories, and the divergence is preserved.
   */
  private static final String PROJECT_BOOTSTRAP =
      "Read the work item for this project with the qits MCP server's work_details tool, then"
          + " implement what it describes.";

  private static final String WORKSPACE_BOOTSTRAP =
      "Read the work item this workspace is for with the qits MCP server's work_details tool, then"
          + " implement what it describes.";

  /**
   * The composed runs' orchestration prompt as the library's text block renders it — the same block,
   * with the same line continuations, so this literal and the shipped constant are two independent
   * copies of the bytes rather than one copy read twice.
   *
   * <p>One literal for both surfaces because there is one constant for both: the instruction is
   * about running an orchestrated session and does not depend on whether the run is scoped to an
   * epic across a project or to a ticket in one cut workspace.
   */
  private static final String COMPOSED_RUN_PROMPT =
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
   * The delivery desk's brief, copied from qits-1517 — a second, independent copy of the bytes, so an
   * edit to the shipped constant that the ticket did not make turns this red.
   */
  private static final String DELIVERY_DESK_BRIEF =
      """
      You are the delivery desk of this project. You keep its work moving while nobody is at a
      workstation: you do not take a look, you keep abreast. You hold qits:desk for this project only.
      The platform enforces the owner's rules; this brief tells you how to work inside them. Read them
      first, every pass:
        qits projects delivery --project <this project>

      You are woken by a turn "Run one pass of the delivery loop". Do one whole pass, then end your
      turn. Never loop or sleep inside a turn: the next tick is the next look. A pass unsticks running
      work first and starts new work last.

      Each pass, in this order:

      1. CI failures. List the open requests and the red ones:
           qits release-request list --project <this project>
           qits release-request list --project <this project> --state REJECTED
           qits release-request list --project <this project> --state FAILED
         For each red one, find its failed run (qits ci runs --release-request <id>) and read it
         (qits ci report show <run id> --project <this project> --repository <repository>).
         - Infrastructure, not code: exit 137 or -1, OOM or Killed, "unable to create native thread",
           a build image pull answering "not found", StepImageUnpinned, a lost step container. Retry it
           once: qits ci retry <run id> --project <this project> --repository <repository>. Say so on
           the item's thread, with the run id, so no later pass retries the same failure twice. Failing
           again the same way: report it on the thread and file a bug (step 8).
         - Code: prompt the agent that owns the branch, naming the failing step and test:
             qits workspaces agents prompt <work id> '<request id>: run <run id>, <test> fails: <why>'
         - A maintenance/* request has no agent: the platform opens a MAINTENANCE gate ticket for it.
           Retry an infrastructure failure once as above; leave a code failure to that ticket.
      2. Stalled agents. Read every running agent:
           qits workspaces agents list --state ACTIVE -o json
         Prompt one, with the concrete news, when what it waits on has already happened (its
         waitingOn names a request now FINALIZED, RELEASED, REJECTED, FAILED or WITHDRAWN, or a CI run
         that has finished), or when it has been WAITING or quiet for more than 30 minutes with nothing
         outstanding. Do not trust that it saw the news. Prompt once per piece of news, never twice.
      3. Member requests of dispatched items. For each open request of a repository that is not the
         wrapper, whose approval is WAITING and whose gates before approval have all passed:
         - read the fold against main: the files with qits repositories commit-changes --project
           <this project> --repository <repository> --commit <mergedSha> --parent main, and each one
           with qits repositories commit-diff (the same flags and --path <file>);
         - approve it at that sha: qits release-request approve --project <this project> --request <id>
           --sha <mergedSha> --note '<one line on what you read>'.
         The door admits you for an item that is IMPLEMENTING or later, on a green build only. A hold
         the door refuses you (config-directory, no-changes) is the owner's: step 9.
      4. Ship verified work. For each epic or ticket at VERIFIED, find the wrapper's release request
         whose sources name its branch. If its approval waits on manual-review and every other gate
         has passed:
         - read the fold: git fetch, then git diff origin/main..<mergedSha> --ignore-submodules=none,
           and read every file that is not a gitlink (docs/development-flow.md, for example);
         - approve it at that sha, saying in the note what you read.
         If the fold moved since you read it, read it again. A fold that adds only gitlink bumps to
         released versions, beside content you already read, needs no new reading. A fold that
         touches .config/qits/ also holds config-directory, which only the owner satisfies: ask
         (step 9). Never skip a fold silently.
      5. qits-agent-config requests. Approve real knowledge: how the platform works, what the owner
         wants, a pitfall of the code. A memory or skill that works around a platform behaviour is a
         defect, not knowledge: file a ticket naming the behaviour (step 8), then withdraw the request:
           qits release-request withdraw --project <this project> --repository qits-agent-config
             --request <id> --reason 'works around <behaviour>; filed as <ticket id>'
      6. Clear blocks. Read each blocked item's reason. If another branch causes it, prompt that
         branch's agent with the fix it owes. One waiting on an approval you may not give: say so once
         on its thread, then move on.
      7. Resume orphaned work. An epic or ticket at IMPLEMENTING, IMPLEMENTED or VERIFYING with no
         ACTIVE agent (qits workspaces agents list --state ACTIVE) is orphaned. Before any new
         backlog, for each:
         - check where its work stands: its branches, its release requests, and whether they shipped;
         - if its status is behind what shipped, move it to match (qits work status), and comment on
           its thread what you found and why it moved;
         - dispatch it: qits work dispatch --entity <id> --mode FLOW --rule resume-orphaned. It
           counts toward max_running.
      8. Bugs. A defect you meet while doing this becomes a ticket with its evidence (run id, request
         id, the log line), unless one is already open: check qits work list first.
           qits work create --archetype ticket, with project, title, ticketType BUG and impetus
      9. The owner. Ask only what needs the owner: one sentence per question, on the item's thread,
         then block the item with that sentence as the reason, so the board shows it waits on the
         owner. When the owner asks you for a status, answer short: what moved, what is stuck and why.
      10. Work the backlog.
         - Count the running items: epics and tickets not DONE or DROPPED with an active agent.
           Resumed orphans count. Check the runner's free slots (qits workspaces runners list).
         - While running < max_running and a slot is free, take the newest open epic or ticket by
           creation time (last in, first out, epics and tickets mixed). Skip blocked and parked ones.
         - First comment on it: "Dispatched by the delivery desk, newest first. Check that it still
           applies first: it may be fixed, superseded or obsolete. If so, drop it with the right outcome
           and a comment saying why."
         - Then: qits work dispatch --entity <id> --mode FLOW --rule backlog-newest-first.
         - A REFINED item: dispatch it with --rule start-refined only if start_refined is on. Otherwise
           leave it for the owner.
         - An unrefined item that needs the owner's design: ask the owner (step 9) instead of dispatching.
      11. Keep the backlog honest. When you meet an item that is plainly obsolete, check it against main
         and drop it with the right outcome and a comment saying why. If you are unsure, do not drop it.

      Check before you claim. Nothing is done because it merged or went green: a setting is applied
      when the running service answers with it, a desk change when the desk was rolled, a release when
      its version is deployed. Look at the live system (the deployment, the health endpoint, qits
      projects delivery, the events) before you report anything done.

      End each pass with a short summary of what it did, and name every orphan you found, so the
      owner hears about it.

      Never: decline a request, approve outside the rules you read, write under /workspace, move a
      gitlink, or open a release request yourself. Name every item by its qualified id. When a door
      answers 403 or 409, the answer is the rule: report it once on the item's thread and go on.\
      """;

  /**
   * The brief as it shipped until qits-1517, copied from qits-1440's dossier page "The desk's brief".
   * The boot migration recognises an unedited stored row by exact equality with it, so it must never
   * move by a byte.
   */
  private static final String PREVIOUS_DELIVERY_DESK_BRIEF =
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

  // -------------------------------------------------------------------------------------------
  // The vocabulary
  // -------------------------------------------------------------------------------------------

  @Test
  public void theVocabularyIsTheEightSurfacesSessionsLaunchWith() {
    assertEquals(
        List.of(
            "project.work",
            "epic.chat",
            "epic.agent",
            "workspace.chat",
            "workspace.agent",
            "epic.autonomous",
            "ticket.dispatch",
            "project.delivery"),
        AgentSurfaceDefaults.SURFACES,
        "the desk, the four a human opens, the two composed runs and the delivery desk last, as in"
            + " AgentSurface.KNOWN; another is a line here and a default below, never a migration");
    for (String surface : AgentSurfaceDefaults.SURFACES) {
      assertNotNull(
          AgentSurfaceDefaults.SHIPPED.get(surface), surface + " is in the vocabulary but unseeded");
    }
  }

  /**
   * The literal is a wire contract with {@code AgentSurface.PROJECT_WORK} in qits-coding-agents
   * 2026.927.4238, which both daemons validate against a closed list — and nothing else in this
   * repository would notice a misspelling, because the store accepts any key.
   */
  @Test
  public void theOneDeskLaunchesAsProjectWork() {
    assertEquals("project.work", AgentSurfaceDefaults.PROJECT_WORK);
  }

  /**
   * The two desks {@code project.work} replaced are retired outright (qits-404): no constant names
   * them any more, they carry no shipped default, and a lookup falls through to the neutral default
   * exactly as any other key nobody has heard of does.
   */
  @Test
  public void theTwoRetiredDesksAreGoneEntirelyAndFallThroughToTheNeutralDefault() {
    for (String retired : List.of("project.epics", "project.tickets")) {
      assertFalse(
          AgentSurfaceDefaults.SURFACES.contains(retired),
          retired + " must not be seeded or lead the listing");
      assertNull(AgentSurfaceDefaults.SHIPPED.get(retired), retired + " must carry no shipped default");
      AgentSurfaceConfigurationDto resolved = AgentSurfaceDefaults.shippedDefault(retired);
      assertEquals(retired, resolved.surface());
      assertTrue(resolved.shipped());
      assertEquals(List.of(), resolved.mcpServers(), retired + " resolves to the neutral default");
    }
    assertEquals(AgentSurfaceDefaults.SURFACES.size(), AgentSurfaceDefaults.SHIPPED.size());
  }

  /** qits-1174: one built-in server; the retired keys stay reserved for the catalog. */
  @Test
  public void qitsIsTheOneBuiltInServerAndTheRetiredKeysStayReserved() {
    assertEquals(List.of("qits"), AgentSurfaceDefaults.BUILT_IN_SERVERS);
    assertEquals(
        List.of("repository", "observability", "actions"), AgentSurfaceDefaults.RETIRED_SERVERS);
    assertEquals(
        List.of("repository", "observability", "actions", "qits"),
        AgentSurfaceDefaults.RESERVED_SERVER_KEYS);
  }

  /** The bare attachment: no narrowing, no mark, no tool list. */
  private static final AgentMcpAttachmentDto QITS =
      new AgentMcpAttachmentDto("qits", false, false, false, false, List.of());

  /** The same attachment with the unattended fence: the central server serves only reads. */
  private static final AgentMcpAttachmentDto QITS_READ_ONLY =
      new AgentMcpAttachmentDto("qits", false, false, false, true, List.of());

  /**
   * qits-1174: every shipped surface attaches the central {@code qits} server and nothing else. The
   * bearer on each call scopes it, so there is nothing for a narrowing to say.
   */
  @Test
  public void everyShippedSurfaceAttachesOnlyTheQitsServer() {
    for (String surface : AgentSurfaceDefaults.SURFACES) {
      List<AgentMcpAttachmentDto> servers = AgentSurfaceDefaults.SHIPPED.get(surface).mcpServers();
      assertEquals(1, servers.size(), surface + ": one platform server");
      assertEquals("qits", servers.get(0).server(), surface);
      assertEquals(List.of(), servers.get(0).allowedTools(), surface + ": the daemon's own list");
    }
  }

  // -------------------------------------------------------------------------------------------
  // What every surface shares
  // -------------------------------------------------------------------------------------------

  @Test
  public void everySurfaceShipsClaudeSkipPermissionsAndActivityTracking() {
    for (String surface : AgentSurfaceDefaults.SHIPPED.keySet()) {
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
  // The one project desk
  // -------------------------------------------------------------------------------------------

  /**
   * {@code project.work} ships as the epics desk it replaced did — an empty system prompt (a value,
   * not an absence: the desk steers with nothing, deliberately). The epics desk itself, and its literal, are gone
   * (qits-404); this is the surface that carries the answer forward now.
   */
  @Test
  public void theOneDeskShipsAnEmptySystemPromptWhichIsAValueAndNotAnAbsence() {
    AgentSurfaceConfigurationDto work = AgentSurfaceDefaults.SHIPPED.get("project.work");
    assertEquals("project.work", work.surface());
    assertNotNull(work.systemPrompt(), "empty, never null — the merged desk steers with nothing");
    assertEquals("", work.systemPrompt(), "not the tickets desk's prompt");
    assertEquals("", work.initialPrompt());
    assertEquals(List.of(QITS), work.mcpServers());
  }

  // -------------------------------------------------------------------------------------------
  // The four workspace surfaces
  // -------------------------------------------------------------------------------------------

  /**
   * They are seeded identically, and that identity is the fact this epic exists to make editable:
   * today the workspace daemon cannot tell these four apart at all.
   */
  @Test
  public void theFourWorkspaceSurfacesShipTheBareQitsServer() {
    for (String surface : List.of("epic.chat", "epic.agent", "workspace.chat", "workspace.agent")) {
      AgentSurfaceConfigurationDto shipped = AgentSurfaceDefaults.SHIPPED.get(surface);
      assertEquals(List.of(QITS), shipped.mcpServers(), surface);
      assertEquals("", shipped.systemPrompt(), surface + ": the workspace daemon has no desk axis");
      assertEquals("", shipped.initialPrompt(), surface + ": the caller composes its own first turn");
    }
  }

  // -------------------------------------------------------------------------------------------
  // The two composed runs
  // -------------------------------------------------------------------------------------------

  /**
   * Both of them steer with the one orchestration prompt, and neither ships an empty box.
   *
   * <p>The constant is asserted against the copied-in literal the way the tickets desk's is: a
   * shipped prompt is only worth anything if it is the library's bytes, and a test that read its
   * expectation off {@link AgentSurfaceDefaults} would agree with any edit.
   */
  @Test
  public void theTwoComposedRunsCarryTheComposedRunPromptByteForByte() {
    assertEquals(
        COMPOSED_RUN_PROMPT, AgentSurfaceDefaults.COMPOSED_RUN_PROMPT, "the constant and the copy");
    for (String surface : List.of("epic.autonomous", "ticket.dispatch")) {
      assertEquals(
          COMPOSED_RUN_PROMPT,
          AgentSurfaceDefaults.SHIPPED.get(surface).systemPrompt(),
          surface + ": one prompt for both composed runs, and two copies would drift");
    }
  }

  @Test
  public void theTwoComposedRunsCarryTheirBootstrapTurnAndAReadOnlyMarkedQitsServer() {
    AgentSurfaceConfigurationDto autonomous = AgentSurfaceDefaults.SHIPPED.get("epic.autonomous");
    assertEquals(PROJECT_BOOTSTRAP, autonomous.initialPrompt());
    assertEquals(
        List.of(QITS_READ_ONLY),
        autonomous.mcpServers(),
        "an unattended run: the qits url is read-only marked");

    AgentSurfaceConfigurationDto dispatch = AgentSurfaceDefaults.SHIPPED.get("ticket.dispatch");
    assertEquals(
        WORKSPACE_BOOTSTRAP,
        dispatch.initialPrompt(),
        "the workspace daemon's constant says 'workspace' where the projects one says 'project'");
    assertEquals(List.of(QITS_READ_ONLY), dispatch.mcpServers());
  }

  // -------------------------------------------------------------------------------------------
  // The delivery desk
  // -------------------------------------------------------------------------------------------

  /**
   * The literal is a wire contract with {@code AgentSurface.PROJECT_DELIVERY} in qits-coding-agents,
   * which appends it last to {@code KNOWN}; the order here mirrors that list.
   */
  @Test
  public void theDeliveryDeskLaunchesAsProjectDeliveryAndIsListedLast() {
    assertEquals("project.delivery", AgentSurfaceDefaults.PROJECT_DELIVERY);
    List<String> surfaces = AgentSurfaceDefaults.SURFACES;
    assertEquals("project.delivery", surfaces.get(surfaces.size() - 1));
    assertEquals(1, surfaces.stream().filter("project.delivery"::equals).count());
  }

  /**
   * The brief it ships is qits-1517's, and the one it shipped before is kept byte for byte for the
   * boot migration — and is not what ships.
   */
  @Test
  public void thePreviousBriefIsKeptByteForByteAndNoLongerShips() {
    assertEquals(
        PREVIOUS_DELIVERY_DESK_BRIEF,
        AgentSurfaceDefaults.PREVIOUS_DELIVERY_DESK_PROMPT,
        "the 2026-10-22 brief");
    assertNotEquals(
        AgentSurfaceDefaults.PREVIOUS_DELIVERY_DESK_PROMPT,
        AgentSurfaceDefaults.DELIVERY_DESK_PROMPT,
        "the migration would be a no-op");
  }

  /** The brief is the ticket's text, byte for byte, and the desk is woken by one turn. */
  @Test
  public void theDeliveryDeskShipsTheTicketsBriefAndOneTurn() {
    assertEquals(DELIVERY_DESK_BRIEF, AgentSurfaceDefaults.DELIVERY_DESK_PROMPT, "the constant");
    AgentSurfaceConfigurationDto delivery = AgentSurfaceDefaults.SHIPPED.get("project.delivery");
    assertEquals("project.delivery", delivery.surface());
    assertEquals(DELIVERY_DESK_BRIEF, delivery.systemPrompt());
    assertEquals("Run one pass of the delivery loop.", delivery.initialPrompt());
  }

  /** Everything but the surface key and the two prompts is the one front desk's. */
  @Test
  public void theDeliveryDeskCarriesTheOneDesksOtherFields() {
    AgentSurfaceConfigurationDto work = AgentSurfaceDefaults.SHIPPED.get("project.work");
    AgentSurfaceConfigurationDto delivery = AgentSurfaceDefaults.SHIPPED.get("project.delivery");
    assertEquals(
        new AgentSurfaceConfigurationDto(
            "project.delivery",
            work.harness(),
            work.model(),
            work.effort(),
            work.remoteControl(),
            work.permissionMode(),
            work.activityTracking(),
            delivery.systemPrompt(),
            delivery.initialPrompt(),
            work.mcpServers(),
            work.externalMcpServers(),
            work.shipped()),
        delivery);
    assertEquals(List.of(QITS), delivery.mcpServers());
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
            "project.work",
            "epic.chat",
            "workspace.chat",
            "epic.autonomous",
            "ticket.dispatch")) {
      assertTrue(
          AgentSurfaceDefaults.SHIPPED.get(chat).remoteControl(),
          chat + " is a chat, and claudeChatProtocol bridges every chat");
    }
    assertTrue(
        AgentSurfaceDefaults.SHIPPED.get("project.delivery").remoteControl(),
        "project.delivery carries the one desk's flag, as the library's shippedRemoteControl does");
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
        AgentSurfaceDefaults.SHIPPED.get("project.work"),
        AgentSurfaceDefaults.shippedDefault("project.work"));
    assertTrue(AgentSurfaceDefaults.SHIPPED.get("project.work").shipped());
  }
}
