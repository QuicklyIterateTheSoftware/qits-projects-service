package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.entities.control.Archetypes;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.EntityStatus;
import eu.wohlben.qits.entities.entity.WorkEntity;
import java.util.Optional;

/**
 * The first turn a dispatched agent is given — one template per <b>phase</b> and per
 * <b>archetype</b>, the phase chosen by the entity's status and never by anything the caller said.
 * Until qits-394 this was {@code TicketPhasePrompts} and knew only tickets; the epic's one
 * implementation turn lived in {@code EpicDispatchController} (removed in qits-399). Both are here now, because the
 * status→phase rule is one rule for every archetype with a lifecycle.
 *
 * <h2>The status picks the phase, and that is the whole design</h2>
 *
 * <p>{@code REPORTED → REFINED → IMPLEMENTED → VERIFIED → DONE}, where a status is what has been
 * <em>achieved</em> and the phase that runs while it holds is what happens <em>next</em> ({@link
 * EntityStatus}). So REPORTED starts the refine phase, REFINED starts implement, IMPLEMENTED starts
 * verify, and VERIFIED and DONE start nothing at all — the work is over and closing is a person's
 * move. {@link EntityStatus#DROPPED} starts nothing either, for the opposite reason: the work was
 * decided against, so there is no phase left to run and there never will be. {@link #phaseOf} is
 * that reading, the <b>only</b> place in this service that turns a status into a phase, and it does
 * not look at the archetype: an epic and a ticket at the same status run the same phase.
 *
 * <p>Two things follow from the prompt being derived rather than passed in, and both are the point
 * rather than a side effect. Pressing dispatch on a half-finished entity <b>resumes</b> it at the
 * phase it actually stands in instead of starting it over. And there is exactly one mapping to keep
 * true: the SPA learns the phase a press would start from {@link #nextPhase} (served by {@code
 * GET /entities/{id}/dispatch}) and never re-derives it.
 *
 * <h2>The words are per archetype, on purpose</h2>
 *
 * <p>An epic's refine phase and a ticket's want different words: a ticket is refined into its
 * {@code description} and a dossier page at most, an epic into a feature/task tree and a dossier; a
 * ticket's implement phase comments on a thread, an epic's marks tasks with {@code
 * mark_task_implemented} and has no thread; a ticket verifies that what was reported no longer
 * occurs, an epic that what it promised now holds. {@link #render} takes the archetype and picks
 * the template, so no template has to read sensibly for both — and a kind with no templates is an
 * {@link IllegalStateException} there rather than a ticket's words handed to something else.
 *
 * <h2>Three phases, one workspace, separated by a context reset</h2>
 *
 * <p>All three templates run in the <em>same</em> workspace on the same branch — {@code
 * ticket/<slug>} or {@code epic/<slug>} — so what separates the phases is a fresh session rather
 * than a fresh checkout. That is why both implement templates forbid integrating the workspace in
 * the imperative: integrating it is the one act that would destroy the ground the verify phase
 * needs. (The epic's old single-shot instruction said the opposite, "integrate the workspace and see
 * the release through"; it is retired with that door's own implementation.)
 *
 * <h2>Each template ends the same way, and that ending is load-bearing</h2>
 *
 * <p>The transition is the <b>agent's claim</b>, made explicitly with {@code transition_ticket} or
 * {@code transition_epic}, and it is reversible in both directions through the same door; where the
 * agent could not finish, it says what is missing and <b>leaves the status where it is</b>. An
 * unsure agent needs a cheap correct answer rather than a coin flip, and a wrong forward move skips a
 * whole phase — the one that would have caught it. Whether the <em>next</em> phase then starts by
 * itself is not these templates' business: that is the continue-or-stop bit the press recorded
 * ({@code WorkEntity.dispatchContinues}), read by {@link PhaseAdvance}.
 *
 * <p><b>The ticket templates name {@code block_ticket} in that same clause, and the epic templates
 * do not</b>: an epic has no block flag and no thread, so its "could not finish" arm says what is
 * missing in the agent's report and leaves the status alone.
 *
 * <p><b>{@code block_ticket} is not in either daemon's bucket</b>, which puts it with {@code
 * update_ticket} and {@code put_dossier_page} rather than with {@code transition_ticket} — see the
 * seams section below, which names all three together and states what a move to kimi costs.
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
 * transition and every comment below goes silent along with it.
 *
 * <p><b>These templates name tools that bucket does <em>not</em> list</b>, and that is stated here
 * rather than left to be discovered: {@code update_ticket}, {@code put_dossier_page}, {@code
 * block_ticket}, {@code transition_epic} (new with qits-394), the epic tree writes the epic refine
 * template names, and the two dossier reads. Every surface ships CLAUDE with {@code
 * SKIP_PERMISSIONS} today, so an unlisted tool is still reachable and the sentences are actionable
 * as they stand. A surface moved to <b>kimi</b> needs them added to qits-workspace-daemon's bucket
 * and to {@code AgentSurfaceDefaults}' copy of it on the same day, or the phases have been told to
 * write with tools that do not exist for them. {@code mark_task_implemented}, {@code get_epic} and
 * {@code list_epics} are already listed.
 *
 * <h2>Every dispatched turn opens with a pointer to the project's flow brief</h2>
 *
 * <p>{@link #FLOW_BRIEF_POINTER} is prepended at the {@link #render} seam — <b>once, for every
 * template of every archetype</b> — and never inside a template. There is one mapping and no second
 * copy of the vocabulary, so a template added beside these inherits the pointer instead of being a
 * template somebody forgot to prepend it to.
 *
 * <p><b>It goes first</b> because the brief it points at is context for everything that follows.
 * <b>The path is absolute</b> because a workspace container clones the project's repository at
 * {@code /workspace} exactly ({@code Provisioner.WORKSPACE_DIR} in qits-workspace-daemon), while the
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

  /** A phase a status starts. Its {@link #word} is what a comment, a log line and the SPA read. */
  enum Phase {
    REFINE("refine"),
    IMPLEMENT("implement"),
    VERIFY("verify");

    final String word;

    Phase(String word) {
      this.word = word;
    }
  }

  /**
   * The one mapping: what has been achieved decides what runs next — or empty where nothing does,
   * including every row of a kind with no lifecycle (a feature, a task), whose status is null.
   */
  static Optional<Phase> phaseOf(WorkEntity entity) {
    if (entity.status == null || Archetypes.legalStatuses(entity.archetype).isEmpty()) {
      return Optional.empty();
    }
    // The merged row stores the word, so it is read back into the lifecycle's own enum before the
    // mapping is made — what keeps this switch exhaustive over the six words rather than open over
    // whatever the String column holds.
    return switch (EntityStatus.valueOf(entity.status)) {
      case REPORTED -> Optional.of(Phase.REFINE);
      case REFINED -> Optional.of(Phase.IMPLEMENT);
      case IMPLEMENTED -> Optional.of(Phase.VERIFY);
      // Nothing runs. VERIFIED and DONE are past the work and closing is a person's; DROPPED is the
      // work decided against, which is the one case where no phase runs because none ever will.
      case VERIFIED, DONE, DROPPED -> Optional.empty();
    };
  }

  /** The word of the phase a dispatch press would start now, or empty — what the SPA is served. */
  static Optional<String> nextPhase(WorkEntity entity) {
    return phaseOf(entity).map(phase -> phase.word);
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
  static Optional<Started> startedBy(WorkEntity entity) {
    return phaseOf(entity)
        .map(phase -> new Started(phase.word, render(entity.archetype, phase, entity)));
  }

  /** The agent's first turn alone, which is what every assertion about the words reads. */
  static Optional<String> promptFor(WorkEntity entity) {
    return startedBy(entity).map(Started::instruction);
  }

  /**
   * <b>The archetype-aware lookup</b>, and the single seam every template comes through — which is
   * why the flow-brief pointer is prepended here and nowhere else.
   */
  static String render(Archetype archetype, Phase phase, WorkEntity entity) {
    String phaseTurn =
        switch (archetype) {
          case TICKET ->
              switch (phase) {
                case REFINE -> refineTicket(entity);
                case IMPLEMENT -> implementTicket(entity);
                case VERIFY -> verifyTicket(entity);
              };
          case EPIC ->
              switch (phase) {
                case REFINE -> refineEpic(entity);
                case IMPLEMENT -> implementEpic(entity);
                case VERIFY -> verifyEpic(entity);
              };
          case FEATURE, TASK ->
              throw new IllegalStateException(
                  "A " + archetype + " has no lifecycle, so it has no phase prompts");
        };
    return FLOW_BRIEF_POINTER + " " + phaseTurn;
  }

  // ---- the three ticket templates -----------------------------------------------------------

  /**
   * <b>REFINE</b>, run while the ticket is {@link EntityStatus#REPORTED}. What it has to produce is
   * a ticket somebody else could implement from, and what it has to survive is the temptation not to
   * bother.
   *
   * <p>The load-bearing sentences, and why each one is worded as it is:
   *
   * <p><b>"the impetus is what was asked for and the thread is the rest".</b> A REPORTED ticket is
   * an impetus and nothing else — one or two sentences in the reporter's words — and the field is
   * deliberately small ({@code WorkEntity.impetus}). An agent handed a small field assumes it has been
   * handed a small problem, so the next sentence sends it further than the impetus goes: the ticket
   * is the report, not the investigation.
   *
   * <p><b>The two shapes, split by type.</b> A BUG is refined by finding the root cause and a
   * reproduction; an IMPROVEMENT has no root cause to find and is refined by making the case for the
   * change against what the code does now. Both are named because a template that said only
   * "investigate" would get a bug's treatment applied to an improvement, which produces a page of
   * description of existing behaviour and no argument at all.
   *
   * <p><b>"INTO THE TICKET'S DESCRIPTION … not a comment, not a new artifact".</b> This is the one
   * sentence the next phase depends on literally: {@code description} is the implement phase's
   * brief, and a refinement that landed as a comment, a markdown file in the tree or a document
   * somewhere else leaves a REFINED ticket whose description is still empty. The negatives are
   * spelled out because each of them is a plausible, tidy-looking thing to do instead.
   *
   * <p><b>The dossier is named as the exception and bounded by it.</b> A ticket owns dossier pages
   * now (epics V8) precisely so the refine phase has somewhere to put what prose cannot hold — an
   * error scenario crossing several services, a sequence that needs a figure. Naming the tool rather
   * than the concept, and giving the two examples, is what keeps it from becoming the default: an
   * agent told it may write pages writes pages.
   *
   * <p><b>"DO NOT IMPLEMENT ANYTHING" is the phase's boundary and is stated as one.</b> It is the
   * instruction most likely to be eroded — an agent that has just found the bug wants to fix it, and
   * the fix is usually small — so it is given its reason rather than left as a preference:
   * implementing from an understanding that was never written down is exactly what this lifecycle
   * exists to stop, and a fix that lands here arrives with no brief, no thread and no verification
   * behind it. The following sentence supplies the missing motive: the implement phase is a separate
   * session that will start from what this one leaves behind, so the work is not being refused, it
   * is being handed over.
   *
   * <p><b>The ending.</b> Done means somebody else could implement from the ticket alone; the
   * transition to REFINED is the agent's claim that this is so, and it is reversible. An agent that
   * could not get there says what is missing on the thread and leaves the ticket REPORTED — which
   * costs one re-press and is the cheap correct answer. Where the obstacle is <em>outside</em> the
   * ticket it also calls {@code block_ticket}, and the examples given are the whole of what that
   * means here: this phase's ordinary failure is not knowing enough yet, which is a re-press and
   * not a block.
   */
  private static String refineTicket(WorkEntity ticket) {
    return "Refine ticket \""
        + ticket.title
        + "\" ("
        + ticket.ticketType
        + ", slug "
        + ticket.slug
        + "). It is REPORTED, so the phase that runs now is refinement. Read it first with"
        + " get_ticket (id "
        + ticket.id
        + ") — the impetus is what was asked for, in the reporter's own words, and the comment"
        + " thread is the rest."
        + " Explore the code further than the impetus goes: it is the report, not the"
        + " investigation. For a BUG, find the root cause and a way to reproduce it; for an"
        + " IMPROVEMENT, make the case for the change against what the code does now."
        + " Write the result INTO THE TICKET'S DESCRIPTION with update_ticket — not a comment, not"
        + " a file in the repository, not a document anywhere else: the description is where the"
        + " next phase reads its brief, and a refinement written somewhere else leaves that brief"
        + " empty."
        + " Where prose cannot hold it — an error scenario crossing several services, a sequence"
        + " that needs a figure — write a dossier page against this ticket with put_dossier_page"
        + " (ticketId "
        + ticket.id
        + ") and point at it from the description."
        + " DO NOT IMPLEMENT ANYTHING in this phase: no fix, no refactor, no commit. That is the"
        + " boundary of the phase and not a preference — implementing from an understanding that"
        + " was never written down is exactly what this lifecycle exists to stop — and the work is"
        + " not being refused but handed over: the implement phase is a separate session that"
        + " starts from what you leave behind."
        + " You are done when somebody else could implement this ticket from the ticket alone."
        + " Then transition_ticket to REFINED: the transition is your claim that it is refined, and"
        + " it is reversible in both directions through the same door. If you could not get there,"
        + " say on the thread with add_ticket_comment what is missing and leave the ticket"
        + " REPORTED."
        + " And where what stopped you is outside this ticket — a decision only a person can take,"
        + " access you have not got, an answer owed by somebody else — call block_ticket with that"
        + " as the reason as well: the ticket stays REPORTED, which is the phase to resume, and the"
        + " block is what stops it being handed out as ready work until the obstacle is cleared.";
  }

  /**
   * <b>IMPLEMENT</b>, run while the ticket is {@link EntityStatus#REFINED}. The brief already
   * exists, so what this template is mostly about is the two ways the phase is got wrong.
   *
   * <p><b>"Comment as the work goes … NOT at the end."</b> This deliberately replaces the previous
   * instruction's "keep one comment current with {@code update_ticket_comment}". The epic asks for a
   * <em>thread</em> and not a scratchpad: a running commentary is a record of how the work was done,
   * which is what the verify phase and the next reader need, while one comment rewritten in place
   * keeps only the last state and quietly deletes every decision on the way to it. The tool named is
   * {@code add_ticket_comment} for that reason, and {@code update_ticket_comment} is deliberately
   * not named here — it stays what it is for elsewhere, a correction of a note that turned out
   * wrong.
   *
   * <p><b>"say so on the thread rather than rewriting it".</b> New insight that contradicts the
   * refined description is exactly what is worth keeping, and rewriting the description would hide
   * it: the description is what the work was agreed against, and an implementation that edits its
   * own brief leaves nobody able to see that the two ever differed.
   *
   * <p><b>"RELEASING IS THE GOAL."</b> The platform's own definition of done, and the one an agent
   * left to itself gets wrong — merged is not done and a green build is certainly not done, because
   * the next phase verifies against the <em>live platform</em> and there is nothing to verify until
   * the change is deployed to it. IMPLEMENTED means released and deployed; the sentence says both.
   *
   * <p><b>"DO NOT INTEGRATE THE WORKSPACE", in the imperative and with its reason.</b> Verification
   * happens in this workspace, after the release, and integrating it ends the workspace — so an
   * agent that integrates destroys its own successor's ground. It has to be said this loudly for a
   * reason that is documented rather than guessed at: the instruction this template replaces said
   * the <em>opposite</em> ("integrate the workspace and see the release through"), it is on every
   * thread this door has already stamped, and an agent that has read either sentence will act on
   * it. A quietly dropped prohibition here produces a phase that destroys its successor and nothing
   * anywhere would notice.
   *
   * <p><b>The ending</b>, the same as the other two: transition to IMPLEMENTED as the claim, and a
   * phase that could not finish means saying so on the thread and leaving the ticket REFINED.
   *
   * <p><b>This is the template the block clause was extracted from</b>, and the change is worth
   * naming because the sentence it replaces looked like it already did the job. It read "If you are
   * blocked, or released only part of it, say on the thread what is missing and leave the ticket
   * REFINED" — a comment and nothing else, so "blocked" was a word on a thread that no surface
   * reads: the ticket stayed REFINED, which is exactly what {@code list_tickets} advertises as
   * ready to be picked up, and the next agent asked to take on the outstanding work picked up the
   * one thing that was known to be stuck. The flag is what that sentence was always describing, and
   * the two halves are now separate for that reason — the status says where the work got to, the
   * flag says whether it can go on, and the word "blocked" is off the first clause so it cannot
   * read as an alternative to the transition.
   */
  private static String implementTicket(WorkEntity ticket) {
    return "Implement ticket \""
        + ticket.title
        + "\" ("
        + ticket.ticketType
        + ", slug "
        + ticket.slug
        + "). It is REFINED, so the phase that runs now is implementation. Read it first with"
        + " get_ticket (id "
        + ticket.id
        + ") — the refined description is the brief, and where it points at a dossier page, read"
        + " that too with list_dossier_pages and get_dossier_page."
        + " Comment as the work goes with add_ticket_comment — when you decide something, when"
        + " something surprises you, when a part lands — rather than writing one report at the end:"
        + " the thread is the record of how this was done, and a note written afterwards from"
        + " memory is not that."
        + " Where what you find contradicts the refined description, say so on the thread rather"
        + " than rewriting the description: the description is what the work was agreed against."
        + " RELEASING IS THE GOAL: this ticket is implemented when the change is released and"
        + " deployed to the platform — not when it is merged, and not when the build is green."
        + " DO NOT INTEGRATE THE WORKSPACE. Verification happens here, in this workspace, after the"
        + " release, and integrating it ends the workspace the next phase needs."
        + " Once the change is released and deployed, transition_ticket to IMPLEMENTED: the"
        + " transition is your claim, and it is reversible in both directions through the same"
        + " door. If you could not finish, or released only part of it, say on the thread what is"
        + " missing and leave the ticket REFINED: the status is the phase to resume, and moving it"
        + " would claim work that did not land."
        + " And where what stopped you is outside this ticket — a change owed by another repository"
        + " that has not released, a decision only a person can take, access you have not got —"
        + " call block_ticket with that as the reason as well: the thread is read by whoever opens"
        + " the ticket, while everything that hands out work reads the status, so without the flag"
        + " this ticket goes on advertising itself as ready and the next agent walks into the same"
        + " wall. Not for work that is merely hard or half done, which is a phase in progress.";
  }

  /**
   * <b>VERIFY</b>, run while the ticket is {@link EntityStatus#IMPLEMENTED}. One claim is being
   * made here — that what was reported no longer occurs — and the template's job is to keep it from
   * being made cheaply.
   *
   * <p><b>"ON THE PLATFORM … a passing test suite is not the claim being made."</b> The live
   * platform is the subject: a ticket reported something that happened there, and a green suite says
   * something about the code rather than about the deployment the reporter was looking at. The
   * negative is stated because the suite is the nearest thing to hand and reporting it reads like
   * verification.
   *
   * <p><b>The ORDER of the next two sentences is part of the design.</b> Reproduce where reproducing
   * is possible; fall back to reading the relevant code changes only where the situation is
   * conceptually unreproducible — a race that needed a particular night, a scheduler window that has
   * passed, a failure whose trigger cannot be summoned on demand. The fallback comes second and is
   * bounded by those examples precisely so it is not offered as the easy path: an agent given both
   * options in either order takes the one it can finish in one turn, and the code-reading arm is
   * always that one. The three examples are what make "unreproducible" a test rather than a mood.
   *
   * <p><b>"Say which of the two you did and why."</b> The two arms are not the same evidence, so the
   * thread has to carry which was used — otherwise VERIFIED means one of two quite different things
   * and the next reader cannot tell which.
   *
   * <p><b>The failure arm is a transition BACKWARD, and it is the only one in these three
   * templates.</b> A verification that fails moves the ticket to REFINED, because what it has
   * established is that the ticket needs deciding again; there is no reject verb in this lifecycle
   * and this is the move that stands in for one ({@code EntityLifecycle}'s own reasoning). That is
   * how implementation starts again.
   *
   * <p><b>The block arm here is bounded harder than in the other two</b>, because this phase has a
   * near neighbour that is not a block: a verification that <em>failed</em> is the backward move to
   * REFINED, and only a verification that could not be <em>attempted</em> — nothing deployed to
   * look at, an environment that is down, something unreachable — is waiting on an obstacle. The
   * template says both, in that order, so "it did not work" cannot be reported as a block and
   * quietly stop the ticket moving at all.
   *
   * <p><b>Closing is a person's move.</b> VERIFIED is as far as this phase goes — DONE is somebody
   * deciding there is nothing left on the thread, which is a judgement about the ticket rather than
   * a report about the work, and the agent has no standing to make it.
   */
  private static String verifyTicket(WorkEntity ticket) {
    return "Verify ticket \""
        + ticket.title
        + "\" ("
        + ticket.ticketType
        + ", slug "
        + ticket.slug
        + "). It is IMPLEMENTED, so the change is released and deployed and the phase that runs now"
        + " is verification. Read it first with get_ticket (id "
        + ticket.id
        + ") — the impetus is what was reported, the description is what was decided, and the"
        + " thread is what the implementation says it did."
        + " Verify ON THE PLATFORM that what this ticket reported no longer occurs: the live"
        + " platform is the subject here, and a passing test suite is not the claim being made."
        + " Where the situation is conceptually unreproducible — a race that needed a particular"
        + " night, a scheduler window that has passed, a failure whose trigger cannot be summoned"
        + " on demand — verify instead by READING THE RELEVANT CODE CHANGES and stating why they"
        + " make the reported situation impossible."
        + " Say on the thread with add_ticket_comment which of the two you did and why."
        + " Then transition_ticket to VERIFIED: the transition is your claim, and it is reversible"
        + " in both directions through the same door."
        + " If it still occurs, transition_ticket BACK TO REFINED and say on the thread what"
        + " failed — that is how implementation starts again."
        + " Closing the ticket is a person's move and not yours. If you could not establish either"
        + " answer, say so on the thread and leave the ticket IMPLEMENTED."
        + " And where you could not because something outside this ticket is in the way — the"
        + " change is not deployed yet, the environment that would show it is down, you cannot"
        + " reach what you need to look at — call block_ticket with that as the reason as well: the"
        + " ticket stays IMPLEMENTED, which is the phase to resume, and the block says the"
        + " verification is waiting rather than that it failed. A verification that actually failed"
        + " is the move back to REFINED and not a block.";
  }

  // ---- the three epic templates -------------------------------------------------------------

  /**
   * <b>REFINE</b> for an epic, run while it is {@link EntityStatus#REPORTED}. What it has to
   * produce is a plan somebody else could implement from — which for an epic is three things, not
   * one: a description that argues the change, a feature/task tree that breaks it down, and a
   * dossier that holds the detail. This is what the {@code Refinement} room's agent has always been
   * asked for, said to an agent in a workspace.
   *
   * <p><b>"INTO THE EPIC".</b> The ticket template's negative list, for the same reason: the
   * implement phase reads {@code get_epic} and the dossier and nothing else, so a plan written as a
   * file in the tree or a document elsewhere leaves a REFINED epic with nothing to build from.
   *
   * <p><b>The dossier is the default here, where for a ticket it is the exception.</b> An epic's
   * description is the pitch and deliberately short; the paths, names and exact values belong in
   * the dossier, and the implement template sends its agent there — so this one has to put them
   * there.
   *
   * <p><b>The transition freezes the scope</b> ({@code EntityLifecycle.requireReported}): from
   * REFINED the description, the tree and the dossier stop being editable. The sentence says so,
   * because the claim is bigger than a ticket's — and says it is reversible, because moving back to
   * REPORTED is how an epic's scope is reopened.
   */
  private static String refineEpic(WorkEntity epic) {
    return "Refine epic \""
        + epic.title
        + "\" (slug "
        + epic.slug
        + "). It is REPORTED, so the phase that runs now is refinement. Read it first with get_epic"
        + " (id "
        + epic.id
        + ") — the description is the pitch and the feature/task tree is the plan so far — and"
        + " read its dossier with list_dossier_pages and get_dossier_page."
        + " Explore the code the epic touches further than the description goes."
        + " Make the description argue the change — what is wrong today and what the epic decides"
        + " — with update_epic. Break the work into features with add_feature and update_feature,"
        + " and each feature into tasks with add_task and update_task, each task naming the one"
        + " repository its change lands in, with dependsOn links wherever one must land before"
        + " another."
        + " Write the detail the description leaves out — paths, names, exact values, examples,"
        + " sequences — into the epic's DOSSIER with put_dossier_page (epicId "
        + epic.id
        + "): the dossier is what the implement phase builds from."
        + " Everything goes INTO THE EPIC — its description, its tree and its dossier — not a file"
        + " in the repository, not a document anywhere else: that is where the next phase reads"
        + " its brief."
        + " DO NOT IMPLEMENT ANYTHING in this phase: no fix, no refactor, no commit. The implement"
        + " phase is a separate session that starts from what you leave behind."
        + " You are done when somebody else could implement every task from the epic and its"
        + " dossier alone. Then transition_epic to REFINED: it freezes the scope — the description,"
        + " the tree and the dossier stop being editable — and it is your claim that the plan is"
        + " complete; it is reversible through the same door. If you could not get there, say in"
        + " your report what is missing and leave the epic REPORTED.";
  }

  /**
   * <b>IMPLEMENT</b> for an epic, run while it is {@link EntityStatus#REFINED}. The words the old
   * single-shot epic dispatch sent (qits-394 retired its implementation), with three changes that
   * follow from the epic now having phases after this one.
   *
   * <p>What is kept, each for the reason it always had: read the epic live with {@code get_epic};
   * read the DOSSIER for the detail the epic leaves out, and know it is read-only from here (the
   * REPORTED guard); work the features and tasks in {@code dependsOn} order; mark each task with
   * {@code mark_task_implemented} <em>as it lands</em>, so a run that dies halfway leaves a true
   * record; and released is the definition of done.
   *
   * <p>What changed. <b>Release every touched repository</b> is spelled out, because an epic's
   * tasks land in several repositories and each has its own release request. <b>DO NOT INTEGRATE
   * THE WORKSPACE</b> replaces "integrate the workspace": verification now happens in this same
   * workspace. And <b>the closing move is the agent's transition to IMPLEMENTED</b> where it used to
   * stop short of it: the verify phase starts from that claim. The reason it was withheld is still
   * true — moving an epic to IMPLEMENTED stamps every task still unmarked — so the sentence makes it
   * conditional on every task being marked, and says why.
   */
  private static String implementEpic(WorkEntity epic) {
    return "Implement epic \""
        + epic.title
        + "\" (slug "
        + epic.slug
        + "). It is REFINED, so the phase that runs now is implementation. Read it first with"
        + " get_epic (id "
        + epic.id
        + ") — the description and its feature/task tree are the brief."
        + " The epic is the pitch; its DOSSIER is what changes and how it works — the paths, names,"
        + " exact values, examples and figures the description leaves out. List it with"
        + " list_dossier_pages and read a page with get_dossier_page whenever a task's detail is"
        + " unclear, before deciding it yourself. The scope and the dossier are read-only while the"
        + " epic is REFINED, so if the plan is wrong or silent on something you had to decide, say"
        + " that in your report rather than trying to correct it."
        + " Work the features and their tasks in order, respecting the dependsOn links between"
        + " them."
        + " Mark each task implemented with mark_task_implemented as it lands, rather than in a"
        + " batch at the end."
        + " RELEASING IS THE GOAL: a task has landed when its change is released and deployed — not"
        + " when it is merged, and not when the build is green. Release every repository you"
        + " touched through its own release request, and see each release through."
        + " DO NOT INTEGRATE THE WORKSPACE. Verification happens here, in this workspace, after the"
        + " releases, and integrating it ends the workspace the next phase needs."
        + " Once every task is marked and every touched repository is released and deployed,"
        + " transition_epic to IMPLEMENTED: the transition is your claim, it is reversible, and it"
        + " stamps every task still unmarked as implemented — so never make it with a task"
        + " outstanding. If you could not finish — blocked, refused, or released only in part — say"
        + " in your report what is missing and leave the epic REFINED.";
  }

  /**
   * <b>VERIFY</b> for an epic, run while it is {@link EntityStatus#IMPLEMENTED}. The ticket
   * template's shape — the live platform is the subject, reproduce before reading code, say which,
   * fail backwards to REFINED — applied to what an epic claims: not that a reported fault is gone,
   * but that what the description promised now holds, feature by feature.
   *
   * <p>No thread, so "say which" goes in the agent's report. Closing (DONE) stays a person's move,
   * exactly as for a ticket; VERIFIED is as far as this phase goes, and reaching it asks for the
   * release of {@code epic/<slug>} ({@link PhaseAdvance}).
   */
  private static String verifyEpic(WorkEntity epic) {
    return "Verify epic \""
        + epic.title
        + "\" (slug "
        + epic.slug
        + "). It is IMPLEMENTED, so its changes are released and deployed and the phase that runs"
        + " now is verification. Read it first with get_epic (id "
        + epic.id
        + ") — the description is what was decided, the tree is what was built, and the dossier"
        + " (list_dossier_pages, get_dossier_page) says how it should behave."
        + " Verify ON THE PLATFORM that what the epic promised now holds, feature by feature: the"
        + " live platform is the subject here, and a passing test suite is not the claim being made."
        + " Where a behaviour is conceptually unreproducible on demand — a race that needed a"
        + " particular night, a scheduler window that has passed — verify it instead by READING THE"
        + " RELEVANT CODE CHANGES and stating why they make it hold."
        + " Say in your report which features you confirmed live and which by reading code, and"
        + " why."
        + " Then transition_epic to VERIFIED: the transition is your claim, and it is reversible in"
        + " both directions through the same door."
        + " If something does not hold, transition_epic BACK TO REFINED and say in your report what"
        + " failed — that is how implementation starts again."
        + " Closing the epic is a person's move and not yours. If you could not establish either"
        + " answer, say so and leave the epic IMPLEMENTED.";
  }
}
