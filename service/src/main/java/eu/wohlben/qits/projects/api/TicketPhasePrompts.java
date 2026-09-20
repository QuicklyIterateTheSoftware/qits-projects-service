package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.epics.entity.TicketStatus;
import eu.wohlben.qits.epics.entity.WorkEntity;
import java.util.Optional;

/**
 * The first turn a dispatched agent is given on a ticket — one template per phase, chosen by the
 * ticket's <b>status</b> and never by anything the caller said.
 *
 * <h2>The status picks the phase, and that is the whole design</h2>
 *
 * <p>{@code REPORTED → REFINED → IMPLEMENTED → VERIFIED → DONE}, where a status is what has been
 * <em>achieved</em> and the phase that runs while it holds is what happens <em>next</em> ({@link
 * TicketStatus}). So REPORTED starts the refine phase, REFINED starts implement, IMPLEMENTED starts
 * verify, and VERIFIED and DONE start nothing at all — the work is over and closing is a person's
 * move. {@link #promptFor(WorkEntity)} is that reading, and it is the <b>only</b> place in this service
 * that turns a status into words.
 *
 * <p>Two things follow from the prompt being derived rather than passed in, and both are the point
 * rather than a side effect. Pressing "assign agent" on a half-finished ticket <b>resumes</b> it at
 * the phase it actually stands in instead of starting it over — a ticket that was refined last week
 * gets the implement turn, not a second refinement of a description that is already written. And
 * there is exactly one mapping to keep true: a phase whose words move, moves here, and no door
 * holds a second copy of the vocabulary that could disagree with this one.
 *
 * <h2>Three phases, one workspace, separated by a context reset</h2>
 *
 * <p>All three templates run in the <em>same</em> workspace on the same {@code ticket/<slug>}
 * branch — the dispatch door stands one up and the far side adopts the one already there — so what
 * separates the phases is a fresh session rather than a fresh checkout. That is why the implement
 * template forbids integrating the workspace in the imperative: integrating it is the one act that
 * would destroy the thing its successor needs, and it is also what the <em>previous</em>
 * instruction on this door told every agent to do.
 *
 * <h2>Each template ends the same way, and that ending is load-bearing</h2>
 *
 * <p>The transition is the <b>agent's claim</b>, made explicitly with {@code transition_ticket}, and
 * it is reversible in both directions through the same door; where the agent could not finish, it
 * says what is missing on the thread and <b>leaves the status where it is</b>. That is the shape the
 * instruction this class replaces already argued for — an unsure agent needs a cheap correct answer
 * rather than a coin flip — and it matters more with five statuses than it did with two: a wrong
 * forward move now skips a whole phase, and the phase it skips is the one that would have caught it.
 *
 * <h2>Two seams have to hold, or all three of these are dead letters</h2>
 *
 * <p>This paragraph moved here from {@code TicketDispatchController.instruction(...)} along with the
 * words it is about, because it is about the words and not about the door. Both halves are checked
 * rather than assumed.
 *
 * <p>qits-workspace-daemon's {@code AgentLaunchService} lists {@code transition_ticket} in its
 * {@code TICKET_RESOLUTION_TOOLS} bucket, so the tool exists for a kimi session too — there {@code
 * enabledTools} is the whole tool surface rather than a pre-approval, and a tool that is not listed
 * does not exist for that session. And a dispatch keeps connecting <em>without</em> the {@code
 * agentReadOnly=true} marker — it goes through the daemon's {@code launchChat}, which never sets it
 * — so {@link eu.wohlben.qits.projects.mcp.ReadOnlyRepositoryToolFilter} does not hide the ticket
 * writes from an unattended run. If a dispatch ever starts marking itself read-only, every
 * transition and every comment below goes silent along with it.
 *
 * <p><b>The refine template names two tools that bucket does <em>not</em> list</b>, and that is
 * stated here rather than left to be discovered: {@code update_ticket} and {@code put_dossier_page}
 * are in neither daemon's {@code repository} bucket (the workspace one carries the two comment
 * tools and {@code transition_ticket} and no other write). Every surface ships CLAUDE with {@code
 * SKIP_PERMISSIONS} today, so an unlisted tool is still reachable and the sentence is actionable as
 * it stands — the same reading the epic instruction's dossier sentence carries. A surface moved to
 * <b>kimi</b> needs both added to qits-workspace-daemon's bucket and to {@code
 * AgentSurfaceDefaults}' copy of it on the same day, or the refine phase has been told to write its
 * result into a field it cannot write.
 *
 * <h2>Every dispatched turn opens with a pointer to the project's flow brief</h2>
 *
 * <p>{@link #FLOW_BRIEF_POINTER} is prepended at the {@code Phase.render(WorkEntity)} seam —
 * <b>once, for all three templates</b> — and never inside {@link #refine}, {@link #implement} or
 * {@link #verify}. That placement is the same argument this class opens with: there is one mapping
 * and no second copy of the vocabulary, so a fourth template added beside those three inherits the
 * pointer instead of being a template somebody forgot to prepend it to. {@link
 * EpicDispatchController} reads the same constant rather than holding a second literal, which is
 * what makes the two doors' pointer text byte-identical by construction rather than by review.
 *
 * <p><b>It goes first</b> because the brief it points at is context for everything that follows:
 * the phase's own instructions are read against how work moves through this platform, not before
 * it.
 *
 * <p><b>The path is absolute</b> because that is the one form knowable from here. A workspace
 * container clones the project's repository at {@code /workspace} exactly ({@code
 * Provisioner.WORKSPACE_DIR} in qits-workspace-daemon), while the agent's working directory is not
 * guaranteed — so a relative path would be a guess made on this side about a shell on the other.
 *
 * <p><b>The absence clause is not padding.</b> These prompts are platform-wide rather than
 * qits-only: every project's dispatch carries this sentence, and only a project whose repository
 * carries the file has a brief to read. Without "if that file is not there", the first thing an
 * agent on every other project does is fail to follow an instruction, which is exactly the tone
 * this turn must not open in. Do not drop it.
 */
final class TicketPhasePrompts {

  private TicketPhasePrompts() {}

  /**
   * The pointer every dispatched agent's first turn opens with, on a ticket phase and on an epic
   * alike. Package-private so {@link EpicDispatchController} — in this same package — reads the one
   * constant rather than repeating the words; see the class javadoc for why it is worded and placed
   * as it is.
   */
  static final String FLOW_BRIEF_POINTER =
      "Read /workspace/docs/development-flow.md first: a short brief on how work moves through this"
          + " platform — branch per slug, release request per repository, the quality gates, the"
          + " transitions, and when the workspace is resolved. If that file is not there, this"
          + " project carries no brief; proceed without it.";

  /**
   * The phase a status starts, or empty where it starts none. Private, and the single {@code
   * switch} the rest of this class and the door both read through — {@link #promptFor} renders it
   * and {@link #phaseNameFor} names it, so the words and the naming cannot come apart.
   */
  private enum Phase {
    REFINE("refine"),
    IMPLEMENT("implement"),
    VERIFY("verify");

    private final String word;

    Phase(String word) {
      this.word = word;
    }

    /**
     * The single seam every phase's words come through, which is why the flow-brief pointer is
     * prepended <b>here</b> rather than in the three templates — see the class javadoc. A fourth
     * phase added to this switch carries the pointer without anybody remembering to add it.
     */
    private String render(WorkEntity ticket) {
      String phaseTurn =
          switch (this) {
            case REFINE -> refine(ticket);
            case IMPLEMENT -> implement(ticket);
            case VERIFY -> verify(ticket);
          };
      return FLOW_BRIEF_POINTER + " " + phaseTurn;
    }
  }

  /** The one mapping: what has been achieved decides what runs next. */
  private static Optional<Phase> phaseOf(WorkEntity ticket) {
    // The merged row stores the word, so it is read back into the lifecycle's own enum before the
    // mapping is made — the same reading TicketService makes before it asks TicketLifecycle
    // anything, and what keeps this switch exhaustive over the five statuses rather than open over
    // ck_entity_status' nine words.
    return switch (TicketStatus.valueOf(ticket.status)) {
      case REPORTED -> Optional.of(Phase.REFINE);
      case REFINED -> Optional.of(Phase.IMPLEMENT);
      case IMPLEMENTED -> Optional.of(Phase.VERIFY);
      // Nothing runs: the work is over and a person closes it.
      case VERIFIED, DONE -> Optional.empty();
    };
  }

  /**
   * A phase that runs: its own word, for the comment the door stamps on the thread, beside the turn
   * its agent is started with. One value rather than two lookups, so the door cannot name one phase
   * and start another.
   */
  record Started(String phase, String instruction) {}

  /**
   * The phase this ticket's status starts, or <b>empty</b> when it starts none. Empty is an answer
   * and not a failure — it is what the dispatch door refuses on, because a ticket past the work has
   * no phase to begin and standing a workspace up for it would put a container on a branch nobody is
   * going to push.
   */
  static Optional<Started> startedBy(WorkEntity ticket) {
    return phaseOf(ticket).map(phase -> new Started(phase.word, phase.render(ticket)));
  }

  /**
   * The agent's first turn alone, which is what every assertion about the words reads. Same mapping
   * as {@link #startedBy}, and deliberately expressed through it rather than beside it.
   */
  static Optional<String> promptFor(WorkEntity ticket) {
    return startedBy(ticket).map(Started::instruction);
  }

  // ---- the three templates ------------------------------------------------------------------

  /**
   * <b>REFINE</b>, run while the ticket is {@link TicketStatus#REPORTED}. What it has to produce is
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
   * costs one re-press and is the cheap correct answer.
   */
  private static String refine(WorkEntity ticket) {
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
        + " REPORTED.";
  }

  /**
   * <b>IMPLEMENT</b>, run while the ticket is {@link TicketStatus#REFINED}. The brief already
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
   * <p><b>The ending</b>, the same as the other two: transition to IMPLEMENTED as the claim, and
   * blocked or part-released means saying so on the thread and leaving the ticket REFINED.
   */
  private static String implement(WorkEntity ticket) {
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
        + " door. If you are blocked, or released only part of it, say on the thread what is"
        + " missing and leave the ticket REFINED.";
  }

  /**
   * <b>VERIFY</b>, run while the ticket is {@link TicketStatus#IMPLEMENTED}. One claim is being
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
   * and this is the move that stands in for one ({@code TicketLifecycle}'s own reasoning). That is
   * how implementation starts again.
   *
   * <p><b>Closing is a person's move.</b> VERIFIED is as far as this phase goes — DONE is somebody
   * deciding there is nothing left on the thread, which is a judgement about the ticket rather than
   * a report about the work, and the agent has no standing to make it.
   */
  private static String verify(WorkEntity ticket) {
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
        + " answer, say so on the thread and leave the ticket IMPLEMENTED.";
  }
}
