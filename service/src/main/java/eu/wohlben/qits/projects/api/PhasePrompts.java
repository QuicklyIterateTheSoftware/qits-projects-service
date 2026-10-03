package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.entities.control.Archetypes;
import eu.wohlben.qits.entities.control.EntityStateMachine;
import eu.wohlben.qits.entities.control.EntityStateMachine.Phase;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.EntityStatus;
import eu.wohlben.qits.entities.entity.WorkEntity;
import java.util.Optional;

/**
 * The first turn a dispatched agent is given — one template per <b>phase</b> and per
 * <b>archetype</b>, the phase chosen by the entity's status and never by anything the caller said.
 * Until qits-394 this was {@code TicketPhasePrompts} and knew only tickets; the epic's one
 * implementation turn lived in {@code EpicDispatchController} (removed in qits-399).
 *
 * <h2>The status picks the phase, and that is the whole design</h2>
 *
 * <p>{@code REPORTED → REFINED → IMPLEMENTING → IMPLEMENTED → VERIFYING → VERIFIED → DONE}, where a
 * status is what has been <em>achieved</em> — or, for the two "-ING" statuses, a fact the platform
 * recorded: an implementation or a verification was started (qits-749) — and the phase that runs
 * while it holds is what happens <em>next</em> ({@link EntityStatus}). So REPORTED starts the refine
 * phase, REFINED and IMPLEMENTING both run implement, IMPLEMENTED and VERIFYING both run verify (a
 * press on an "-ING" entity resumes its phase rather than refusing), and VERIFIED and DONE start
 * nothing at all — the work is
 * over and closing is a person's move. {@link EntityStatus#DROPPED} starts nothing either: the work
 * was decided against. That mapping is declared once, on the state machine ({@code
 * EntityStateMachine.phaseStartedBy}); {@link #phaseOf} is the <b>only</b> place in this service
 * that asks it, and it does not look at the archetype: an epic and a ticket at the same status run
 * the same phase. Nothing here moves an entity into an "-ING" status — the dispatch press and the FLOW
 * hand-off do ({@link EntityDispatch}, {@link PhaseAdvance}); a template only says how to leave it.
 *
 * <p>Because the prompt is derived rather than passed in, pressing dispatch on a half-finished
 * entity <b>resumes</b> it at the phase it stands in, and the SPA learns the phase a press would
 * start from {@link #nextPhase} (served by {@code GET /entities/{id}/dispatch}) and never re-derives
 * it. The words are per archetype — a ticket is refined into its description, an epic into a
 * feature/task tree and a dossier — so {@link #render} takes the archetype, and a kind with no
 * templates is an {@link IllegalStateException} there rather than a ticket's words handed to
 * something else.
 *
 * <h2>The template rule (qits-592)</h2>
 *
 * <p>These turns used to run to 1,600–2,600 characters each, most of it copied from somewhere the
 * agent reads anyway. They are short now, on four rules:
 *
 * <ol>
 *   <li><b>Nothing copied from a tool description or from the flow brief.</b> What a status means,
 *       which moves are legal, what a block does and how a release request works are stated where
 *       the agent meets them — {@code transition_ticket}/{@code transition_epic}, {@code
 *       block_entity}, {@code /workspace/docs/development-flow.md} — and a second copy here is one
 *       free to drift from the first. A template carries only what is specific to its phase: what
 *       to produce, where to write it, and the one or two mistakes that phase is prone to.
 *   <li><b>One ending, for every phase and archetype.</b> Either the forward transition — {@code
 *       transition_ticket to <next>} or {@code transition_epic to <next>}, the agent's claim that the
 *       phase is done — or {@code block_entity} with what is missing or what was found. There is no
 *       "leave the status where it is and comment" arm: a comment is read only by whoever opens the
 *       thread, while everything that hands out work reads the status and the flag, so an
 *       unfinished phase that only commented went on advertising itself as ready work.
 *   <li><b>No backward move.</b> A move back is a correction of a claim that turned out wrong, not
 *       how a phase reports failure. A verification that fails blocks the entity where it stands with
 *       what still occurs; a person decides what happens next, and closing is a person's move too.
 *   <li><b>At most 900 characters per phase turn</b>, not counting {@link #FLOW_BRIEF_POINTER} and
 *       the substituted title, type, qualified id, slug and id. {@code PhasePromptsTest} renders
 *       all six and enforces it.
 * </ol>
 *
 * <p>What each template still says is what the phase gets wrong without it. Refine: <em>do not
 * implement</em>, with its reason (the implement phase is a separate session that starts from what
 * is written), and the result goes into the description ({@code update_ticket}) or the epic's
 * tree and dossier — a dossier page only for what prose cannot hold on a ticket. Implement: comment
 * as the work goes; a contradiction goes on the thread, not into the brief it contradicts; done is
 * released and deployed, not merged and not green; and <em>do not integrate the workspace</em>,
 * because all three phases run in the same workspace on {@code ticket/<slug>} or {@code
 * epic/<slug>}, separated by a context reset, and integrating it destroys the ground the verify
 * phase needs. Verify: on the live platform first, reading the change only when the situation cannot
 * be reproduced on demand — that order is the design, since the code-reading arm is always the one
 * an agent can finish in one turn — and the thread says which was done. An epic's implement turn
 * also makes the move to IMPLEMENTED conditional on every task being marked, because that move
 * stamps every unmarked task, and asks for {@code mark_task_implementing} as each task starts, so
 * the board shows which of them are under way (qits-749).
 *
 * <p>Every turn names its entity by <b>qualified id</b> ({@code qits-297}) beside the slug and the
 * row id, and both implement turns give the commit-subject convention with that id written in
 * (qits-301): a subject of the form {@code term(qits-297): message} is what ties a commit back to the
 * work it was made for, and the one place an agent learns the id is here. Every caller passes it —
 * the dispatch door the same value it names the workspace with, the phase advance the same read —
 * and a row with no number or no project slug passes null, which drops both cleanly.
 *
 * <p>Whether the <em>next</em> phase then starts by itself is not these templates' business: that is
 * the continue-or-stop bit the press recorded ({@code WorkEntity.dispatchContinues}), read by {@link
 * PhaseAdvance}.
 *
 * <h2>Two seams have to hold, or all of these are dead letters</h2>
 *
 * <p>qits-workspace-daemon's {@code AgentLaunchService} lists {@code transition_ticket} in its
 * {@code TICKET_RESOLUTION_TOOLS} bucket, so the tool exists for a kimi session too — there {@code
 * enabledTools} is the whole tool surface rather than a pre-approval, and a tool that is not listed
 * does not exist for that session. And a dispatch keeps connecting <em>without</em> the {@code
 * agentReadOnly=true} marker — it goes through the daemon's {@code launchChat}, which never sets it
 * — so {@link eu.wohlben.qits.projects.mcp.ReadOnlyRepositoryToolFilter} does not hide the entity
 * writes from an unattended run. If a dispatch ever starts marking itself read-only, every
 * transition and every block below goes silent along with it.
 *
 * <p><b>These templates name tools that bucket does <em>not</em> list</b>: {@code update_ticket},
 * {@code put_dossier_page}, {@code block_entity} and {@code unblock_entity} (qits-592), {@code
 * transition_epic} (qits-394), the epic tree writes the epic refine template names, and the dossier
 * reads. {@code add_comment} and {@code update_comment} are in {@code TICKET_THREAD_TOOLS} (qits-551),
 * and {@code mark_task_implemented}, {@code get_epic} and {@code list_epics} are already listed.
 * {@code mark_task_implementing} (qits-749) is in {@code AgentSurfaceDefaults}' copy beside its
 * sibling, and joins the daemon's bucket in that repository's own release.
 * Every surface ships CLAUDE with {@code SKIP_PERMISSIONS} today, so an unlisted tool is still
 * reachable and the sentences are actionable as they stand. A surface moved to <b>kimi</b> needs
 * them added to qits-workspace-daemon's bucket and to {@code AgentSurfaceDefaults}' copy of it on
 * the same day, or the phases have been told to write — and to block — with tools that do not exist
 * for them.
 *
 * <h2>Every dispatched turn opens with a pointer to the project's flow brief</h2>
 *
 * <p>{@link #FLOW_BRIEF_POINTER} is prepended at the {@link #render} seam — <b>once, for every
 * template of every archetype</b> — and never inside a template, so a template added beside these
 * inherits it. <b>It goes first</b> because the brief is context for everything that follows. <b>The
 * path is absolute</b> because a workspace container clones the project's repository at {@code
 * /workspace} exactly ({@code Provisioner.WORKSPACE_DIR} in qits-workspace-daemon), while the
 * agent's working directory is not guaranteed. <b>The absence clause is not padding</b>: these
 * prompts are platform-wide, and only a project whose repository carries the file has a brief to
 * read — without "if that file is not there", the first thing an agent on every other project does
 * is fail to follow an instruction. Do not drop it.
 */
final class PhasePrompts {

  private PhasePrompts() {}

  /** The pointer every dispatched agent's first turn opens with; see the class javadoc. */
  static final String FLOW_BRIEF_POINTER =
      "Read /workspace/docs/development-flow.md first: a short brief on how work moves through this"
          + " platform — branch per slug, release request per repository, the quality gates, the"
          + " transitions, and when the workspace is resolved. If that file is not there, this"
          + " project carries no brief; proceed without it.";

  /**
   * The one mapping: what has been achieved decides what runs next — or empty where nothing does,
   * including every row of a kind with no lifecycle (a feature, a task), whose status is null. The
   * mapping itself is the state machine's ({@link EntityStateMachine#phaseStartedBy}): REPORTED
   * starts refine, REFINED and IMPLEMENTING implement, IMPLEMENTED and VERIFYING verify, and
   * VERIFIED, DONE and DROPPED nothing —
   * VERIFIED and DONE because the work is over and closing is a person's, DROPPED because the work
   * was decided against. This method only reads the stored word back into the enum first.
   */
  static Optional<Phase> phaseOf(WorkEntity entity) {
    if (entity.status == null || Archetypes.legalStatuses(entity.archetype).isEmpty()) {
      return Optional.empty();
    }
    return EntityStateMachine.phaseStartedBy(EntityStatus.valueOf(entity.status));
  }

  /** The word of the phase a dispatch press would start now, or empty — what the SPA is served. */
  static Optional<String> nextPhase(WorkEntity entity) {
    return phaseOf(entity).map(phase -> phase.word());
  }

  /**
   * A phase that runs: its own word, for the comment and the log line, beside the turn its agent is
   * started with. One value rather than two lookups, so a caller cannot name one phase and start
   * another.
   */
  record Started(String phase, String instruction) {}

  /**
   * The phase this entity's status starts with its archetype's turn, or <b>empty</b> when it starts
   * none. Empty is an answer and not a failure — the dispatch path refuses on it, and the advance
   * delivers nothing on it.
   */
  static Optional<Started> startedBy(WorkEntity entity, String qualifiedId) {
    return phaseOf(entity).map(phase -> start(entity, phase, qualifiedId));
  }

  /**
   * {@code phase}'s turn for {@code entity}, for a caller that has already decided the phase (the
   * dispatch door decides it before its refusals, and renders once it holds the qualified id it
   * names the workspace with, so the two cannot disagree).
   */
  static Started start(WorkEntity entity, Phase phase, String qualifiedId) {
    return new Started(phase.word(), render(entity.archetype, phase, entity, qualifiedId));
  }

  /** The agent's first turn alone, which is what every assertion about the words reads. */
  static Optional<String> promptFor(WorkEntity entity, String qualifiedId) {
    return startedBy(entity, qualifiedId).map(Started::instruction);
  }

  /**
   * <b>The archetype-aware lookup</b>, and the single seam every template comes through — which is
   * why the flow-brief pointer is prepended here and nowhere else.
   *
   * @param qualifiedId the entity's {@code <project-slug>-<number>} ({@code qits-297}), or null where
   *     it cannot be had — the turn then names the row by slug and id alone and carries no
   *     commit-subject sentence, since there is no id to put in one (qits-301)
   */
  static String render(Archetype archetype, Phase phase, WorkEntity entity, String qualifiedId) {
    String q = qualifiedId == null || qualifiedId.isBlank() ? null : qualifiedId;
    String phaseTurn =
        switch (archetype) {
          case TICKET ->
              switch (phase) {
                case REFINE -> refineTicket(entity, q);
                case IMPLEMENT -> implementTicket(entity, q);
                case VERIFY -> verifyTicket(entity, q);
              };
          case EPIC ->
              switch (phase) {
                case REFINE -> refineEpic(entity, q);
                case IMPLEMENT -> implementEpic(entity, q);
                case VERIFY -> verifyEpic(entity, q);
              };
          case FEATURE, TASK ->
              throw new IllegalStateException(
                  "A " + archetype + " has no lifecycle, so it has no phase prompts");
          case CAMPAIGN ->
              throw new IllegalStateException(
                  "A CAMPAIGN starts through its executor, so it has no phase prompts");
        };
    return FLOW_BRIEF_POINTER + " " + phaseTurn;
  }

  /**
   * The status a phase's agent claims when it is done — the state machine's {@link
   * EntityStateMachine#endOf}, the same value the served registry's {@code phases} names.
   */
  private static String end(Phase phase) {
    return EntityStateMachine.endOf(phase).name();
  }

  /** {@code "qits-297, "} for the header's parenthesis, or nothing where there is no id. */
  private static String named(String qualifiedId) {
    return qualifiedId == null ? "" : qualifiedId + ", ";
  }

  /**
   * <b>The commit-subject convention</b> (qits-301, epic qits-297), given literally with the real id
   * so the agent copies a form rather than reconstructing one. Worded as the convention and not as a
   * rule, because only a repository that opts into qits-githost's receive guard refuses a commit
   * without it. Nothing where there is no id: a sentence telling the agent to write {@code
   * feat(null): …} is worse than none.
   */
  private static String commitSubjects(String qualifiedId) {
    return qualifiedId == null
        ? ""
        : " Each commit subject names the work: term("
            + qualifiedId
            + "): message, e.g. feat("
            + qualifiedId
            + "): add the export.";
  }

  // ---- the three ticket templates -----------------------------------------------------------

  /**
   * <b>REFINE</b>, run while the ticket is {@link EntityStatus#REPORTED}. The impetus is one or two
   * sentences in the reporter's words, and an agent handed a small field assumes a small problem, so
   * the turn sends it past the impetus. The three types are named because each is refined
   * differently: a BUG by root cause and reproduction, an IMPROVEMENT by the case against what the
   * code does now, a MAINTENANCE ticket (a red gate the platform filed about itself) by the run it
   * names. The result goes into the description because that is the implement phase's brief. "Do
   * not implement" carries its reason, since an agent that has just found the bug wants to fix it.
   */
  private static String refineTicket(WorkEntity ticket, String q) {
    return "Refine ticket \""
        + ticket.title
        + "\" ("
        + ticket.ticketType
        + ", "
        + named(q)
        + "slug "
        + ticket.slug
        + ", id "
        + ticket.id
        + "). Read it with get_ticket: the impetus is the report and the thread is the rest."
        + " Investigate past the impetus. For a BUG, find the root cause and a reproduction. For an"
        + " IMPROVEMENT, make the case against what the code does now. For a MAINTENANCE ticket,"
        + " find why its named run failed. Write the result into the ticket's description with"
        + " update_ticket. Use put_dossier_page (ticketId "
        + ticket.id
        + ") only for what prose cannot hold. Do not implement anything: the implement phase is a"
        + " separate session that starts from what you write. When someone else could implement"
        + " from the ticket alone, transition_ticket to "
        + end(Phase.REFINE)
        + ". If you cannot get there,"
        + " block_entity with what is missing.";
  }

  /**
   * <b>IMPLEMENT</b>, run while the ticket is REFINED or IMPLEMENTING. A running thread rather
   * than one report at the end, because the thread is the record the verify phase reads; a
   * contradiction goes on it rather than into the description, which is what the work was agreed
   * against. Released and deployed, because the next phase verifies the live platform. And no
   * integration, because verification runs in this same workspace next.
   */
  private static String implementTicket(WorkEntity ticket, String q) {
    return "Implement ticket \""
        + ticket.title
        + "\" ("
        + ticket.ticketType
        + ", "
        + named(q)
        + "slug "
        + ticket.slug
        + ", id "
        + ticket.id
        + "). Read it with get_ticket: its description, plus any dossier page it points at, is the"
        + " brief. Comment with add_ticket_comment as the work goes. If the brief turns out wrong,"
        + " say so on the thread and do not rewrite it. Done means released and deployed, not"
        + " merged and not green."
        + commitSubjects(q)
        + " Do not integrate the workspace, because verification runs here"
        + " next. Once the change is live, transition_ticket to "
        + end(Phase.IMPLEMENT)
        + ". If you cannot get"
        + " there, block_entity with what is missing.";
  }

  /**
   * <b>VERIFY</b>, run while the ticket is {@link EntityStatus#IMPLEMENTED} or {@link
   * EntityStatus#VERIFYING}. The live platform is
   * the subject; reading the change is the fallback and comes second, bounded to what cannot be
   * reproduced on demand. The thread says which, because the two are different evidence. A check
   * that fails — or could not be made — blocks the ticket where it stands with what was found; it
   * does not move it back, and VERIFIED is as far as an agent goes.
   */
  private static String verifyTicket(WorkEntity ticket, String q) {
    return "Verify ticket \""
        + ticket.title
        + "\" ("
        + ticket.ticketType
        + ", "
        + named(q)
        + "slug "
        + ticket.slug
        + ", id "
        + ticket.id
        + "). Read it with get_ticket, then check on the live platform that what it reported no"
        + " longer occurs. Fall back to reading the change only if the situation cannot be"
        + " reproduced on demand. Say on the thread with add_ticket_comment which of the two you"
        + " did. If it holds, transition_ticket to "
        + end(Phase.VERIFY)
        + "; closing is a person's move. If it"
        + " still occurs, or you could not check, block_entity with what you found.";
  }

  // ---- the three epic templates -------------------------------------------------------------

  /**
   * <b>REFINE</b> for an epic, run while it is {@link EntityStatus#REPORTED}. A plan is three
   * things: a description that argues the change, a feature/task tree (one repository per task,
   * {@code dependsOn} where order matters), and a dossier holding the paths, names and exact values
   * the implement phase builds from. The claim to REFINED freezes all three ({@code
   * EntityLifecycle.requireReported}), which the turn says because the claim is bigger than a
   * ticket's; the thread stays writable after the freeze.
   */
  private static String refineEpic(WorkEntity epic, String q) {
    return "Refine epic \""
        + epic.title
        + "\" ("
        + named(q)
        + "slug "
        + epic.slug
        + ", id "
        + epic.id
        + "). Read it with get_epic and read its dossier. Investigate the code it touches. Make the"
        + " description argue the change (update_epic). Break the work into features and tasks"
        + " (add_feature, add_task), each task naming one repository and its dependsOn links. Put"
        + " the paths, names and exact values in the dossier (put_dossier_page, epicId "
        + epic.id
        + "). Record decisions and open questions on its thread with add_comment (entityId "
        + epic.id
        + "). Do not implement anything: the implement phase is a separate session that starts from"
        + " what you write. When every task could be built from the epic alone, transition_epic to "
        + end(Phase.REFINE)
        + ", which freezes the scope. If you cannot get there, block_entity with what is"
        + " missing.";
  }

  /**
   * <b>IMPLEMENT</b> for an epic, run while it is {@link EntityStatus#REFINED} or {@link
   * EntityStatus#IMPLEMENTING}. The tree and dossier are frozen, so corrections go on the thread.
   * Tasks in {@code dependsOn} order, each marked with {@code mark_task_implementing} as it starts
   * and {@code mark_task_implemented} as it lands so a run that dies halfway leaves a true record; landed
   * means released and deployed, one release request per repository. The claim to IMPLEMENTED is
   * conditional on every task being marked because the move stamps any task still unmarked.
   */
  private static String implementEpic(WorkEntity epic, String q) {
    return "Implement epic \""
        + epic.title
        + "\" ("
        + named(q)
        + "slug "
        + epic.slug
        + ", id "
        + epic.id
        + "). Read it with get_epic: its tree and dossier are the brief. Both are read-only now:"
        + " record progress and corrections on its thread with add_comment (entityId "
        + epic.id
        + ") as the work goes. Work the tasks in dependsOn order: mark_task_implementing when you"
        + " start one, mark_task_implemented as it lands. Landed means released and deployed, through a"
        + " release request per repository, not merged and not green."
        + commitSubjects(q)
        + " Do not integrate the"
        + " workspace, because verification runs here next. When every task is marked,"
        + " transition_epic to "
        + end(Phase.IMPLEMENT)
        + "; that move stamps any unmarked task. If you cannot get"
        + " there, block_entity with what is missing.";
  }

  /**
   * <b>VERIFY</b> for an epic, run while it is {@link EntityStatus#IMPLEMENTED} or {@link
   * EntityStatus#VERIFYING}: the ticket's verify
   * turn applied to what an epic claims — not that a reported fault is gone, but that what it
   * promised holds, feature by feature. What was confirmed, and how, goes on the epic's thread.
   * Reaching VERIFIED asks for the release of {@code epic/<slug>} ({@link PhaseAdvance}); closing
   * stays a person's move.
   */
  private static String verifyEpic(WorkEntity epic, String q) {
    return "Verify epic \""
        + epic.title
        + "\" ("
        + named(q)
        + "slug "
        + epic.slug
        + ", id "
        + epic.id
        + "). Read it with get_epic, then check on the live platform, feature by feature, that what"
        + " it promised holds. Fall back to reading the change only if a behaviour cannot be"
        + " reproduced on demand. Record on its thread with add_comment (entityId "
        + epic.id
        + ") what you confirmed and how. If it holds, transition_epic to "
        + end(Phase.VERIFY)
        + "; closing is a"
        + " person's move. If something does not hold, or you could not check, block_entity with"
        + " what you found.";
  }
}
