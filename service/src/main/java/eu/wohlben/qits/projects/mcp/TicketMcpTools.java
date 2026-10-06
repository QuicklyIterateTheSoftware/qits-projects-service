package eu.wohlben.qits.projects.mcp;

import eu.wohlben.qits.entities.control.Mover;
import eu.wohlben.qits.entities.control.EntityWrite;
import eu.wohlben.qits.entities.control.EntityCommentService;
import eu.wohlben.qits.entities.control.WorkEntityService;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.entities.entity.EntityComment;
import eu.wohlben.qits.entities.error.NotFoundException;
import eu.wohlben.qits.projects.api.ProjectChangeHint;
import eu.wohlben.qits.projects.api.ProjectChangePublisher;
import eu.wohlben.qits.projects.api.QualifiedEntityIds;
import eu.wohlben.qits.projects.api.EntityBlocks;
import eu.wohlben.qits.projects.api.PhaseAdvance;
import io.quarkiverse.mcp.server.McpServer;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.WrapBusinessError;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import org.jboss.logging.Logger;

/**
 * The ticket half of the "repository" MCP server — the surface an agent files and works bugs and
 * improvements through, mounted on the same declared server as {@link RepositoryMcpTools} and
 * {@link EpicMcpTools} ({@code /projects/mcp}) for the reason that class's javadoc gives.
 *
 * <p><strong>Use case: the small thing found on the way.</strong> An agent working in a repository
 * notices something wrong that is not what it was asked to do. Filing it as an epic would put a
 * plan on the board where a note belongs; {@code create_ticket} is the note. The same surface reads
 * the project's tickets by status, so an agent asked to "fix the outstanding bugs" has somewhere to
 * look — READY_FOR_DEV is the work a person scheduled and so is ready to be picked up, and
 * IMPLEMENTING the work an agent was
 * already started on.
 *
 * <p><strong>Unlike epics, the transition IS here.</strong> {@code EpicMcpTools} deliberately
 * exposes no lifecycle move, because freezing a plan is a human decision about committing to scope.
 * Every ticket status is a statement about what has been achieved — refined, implemented, verified
 * — which is exactly the thing the agent that did the work knows and nobody else does yet; and
 * every move below DONE is reversible, so a wrong answer costs a call rather than a superseded
 * epic. The last move, to DONE, is still a person's: closing is a judgement about the thread, and
 * DONE is final — a follow-up is a new ticket.
 *
 * <p><strong>DROPPED is the other way out and it is not the same kind of claim.</strong> Every
 * other status reports what the work reached; this one reports that the work should not happen. An
 * agent that finds the ticket describes something that is not a problem, or has been made
 * meaningless since it was filed, knows that as surely as it knows a fix shipped, so the move is
 * here. What it must never be is the exit for a phase that went badly — a ticket that is merely
 * hard or unfinished is left where it stands with the missing piece said on the thread, because
 * DROPPED would assert a decision nobody took.
 *
 * <p>Scope comes from {@link ProjectScope} (the {@code X-QITS-Project} header), never from a tool
 * argument, and every id a tool is handed is checked back to that project — a ticket or comment in
 * another project reads as not found, so the model cannot work across project boundaries.
 *
 * <p>{@link WrapBusinessError} turns anything a tool throws into a tool result with {@code
 * isError=true} carrying the message, so a refusal is something the model can read and act on
 * rather than a JSON-RPC protocol error that kills the turn.
 *
 * <p>Every mutating tool fires a {@link ProjectChangeHint} on {@code TICKETS}, so a browser
 * watching the ticket list redraws as the agent works — and every one of them is registered in
 * {@link ReadOnlyRepositoryToolFilter}, which fails closed.
 *
 * <p><strong>No {@code @Transactional} here</strong>, for the reason {@link EpicMcpTools} states:
 * these tools straddle two persistence units and Narayana enlists only one local resource per
 * transaction. The ticket service opens its own, exactly as it does for the REST controllers.
 */
@ApplicationScoped
@WrapBusinessError
public class TicketMcpTools {

  private static final Logger LOG = Logger.getLogger(TicketMcpTools.class);

  /**
   * What the audit log records for a write with no forwarded identity. An MCP session is a machine
   * caller; naming it beats a null {@code changed_by} that reads as "unknown human".
   */
  private static final String AGENT = "mcp-agent";

  @Inject ProjectScope scope;

  /** The session's project slug, which is the qualifier in {@code <project-slug>-<number>}. */
  @Inject ProjectScopeGuard scopeGuard;

  @Inject WorkEntityService entities;

  @Inject EntityCommentService thread;

  /** Every ticket move goes through here, so a resolving one discards the ticket's refinement. */
  @Inject eu.wohlben.qits.projects.refinementhost.EntityResolutions resolutions;

  @Inject ProjectChangePublisher changePublisher;

  @Inject SecurityIdentity identity;

  /**
   * The phase a transition starts. Crossing into {@code projects.api} from here is the same
   * crossing {@code EntityDispatchController} declares: a workspace is {@code domain}'s, and this
   * module assembles both.
   */
  @Inject PhaseAdvance phaseAdvance;

  /**
   * The block door's whole rule, shared with {@code TicketController}'s route over the same write.
   * The same crossing into {@code projects.api} the field above declares.
   */
  @Inject EntityBlocks blocks;

  // --- Result shapes --------------------------------------------------------

  /**
   * A ticket as it appears in a list: no thread, just enough to choose one. It carries both
   * {@code impetus} (what was originally asked for) and {@code description} (what refinement
   * decided to do about it), because which of the two is present is how a reader tells a bare
   * report from a refined one without asking again.
   *
   * <p>{@code qualifiedId} ({@code qits-1337}) and never the bare number — the decision and its
   * reason are on {@link EpicMcpTools.EpicSummary}, and they are this server's rule rather than
   * that class's.
   */
  public record TicketSummary(
      String id,
      String qualifiedId,
      String slug,
      String title,
      String type,
      String status,
      boolean blocked,
      String assignee,
      String createdBy,
      String impetus,
      String description,
      List<String> acceptanceCriteria) {}

  /** One remark inside {@link TicketDetail}. */
  public record CommentDetail(String id, String author, String body, Instant createdAt) {}

  /** One ticket with its whole thread, oldest first. */
  public record TicketDetail(
      String id,
      String qualifiedId,
      String slug,
      String title,
      String type,
      String status,
      boolean blocked,
      String assignee,
      String createdBy,
      String impetus,
      String description,
      List<String> acceptanceCriteria,
      List<CommentDetail> comments) {}

  // --- Tickets --------------------------------------------------------------

  @McpServer("repository")
  @Tool(
      name = "list_tickets",
      description =
          "List the tickets of the project this session is scoped to, oldest first, without their"
              + " comments. A ticket is a small-scoped piece of work — a BUG or an IMPROVEMENT, or a"
              + " MAINTENANCE ticket the platform filed itself about a stuck release request and"
              + " closes itself when that request ends —"
              + " that is not big enough to be an epic. Its status says what has been achieved so"
              + " far: REPORTED (somebody said what is wrong), REFINED (the ticket says what to"
              + " do, and waits for a person to schedule it), READY_FOR_DEV (a person scheduled it:"
              + " implementation may start), IMPLEMENTING (an implementation was started — the platform sets it when an"
              + " agent is dispatched), IMPLEMENTED (the change is released and deployed), VERIFYING"
              + " (a verification was started — set the same way), VERIFIED"
              + " (it no longer"
              + " occurs on the platform), DONE (closed), DROPPED (a decision was taken not to do"
              + " this work, and nothing about it was implemented or verified). Call it with"
              + " status=\"READY_FOR_DEV\" to find the work that is ready to be picked up, or"
              + " status=\"REPORTED\" to find what still needs refining. Read `blocked` beside the"
              + " status: it is a flag and not a status, and a blocked ticket is one somebody"
              + " already found something in the way of — its thread says what — so it is the wrong"
              + " one to pick up even where its status says otherwise.")
  public List<TicketSummary> listTickets(
      @ToolArg(
              required = false,
              description =
                  "exact status to filter by: REPORTED, REFINED, READY_FOR_DEV, IMPLEMENTING,"
                      + " IMPLEMENTED, VERIFYING, VERIFIED, DONE or DROPPED. Omit for every ticket.")
          String status) {
    String projectSlug = projectSlug(); // once for the listing, never once per row
    return entities.listByProject(Archetype.TICKET, scope.requireProjectId(), status).stream()
        .map(ticket -> summarize(ticket, projectSlug))
        .toList();
  }

  @McpServer("repository")
  @Tool(
      name = "get_ticket",
      description =
          "Read one ticket of this project in full: its description plus every comment on it,"
              + " oldest first. Read it before working on a ticket — the thread is usually where"
              + " the reproduction and the decisions are.")
  public TicketDetail getTicket(@ToolArg(description = "id of a ticket in this project") String id) {
    WorkEntity ticket = requireTicketInProject(id);
    List<CommentDetail> comments =
        thread.listComments(ticket.id).stream()
            .map(c -> new CommentDetail(c.id, c.author, c.body, c.createdAt))
            .toList();
    return new TicketDetail(
        ticket.id,
        QualifiedEntityIds.render(projectSlug(), ticket.number),
        ticket.slug,
        ticket.title,
        ticket.ticketType.name(),
        ticket.status,
        ticket.blocked,
        ticket.assignee,
        ticket.createdBy,
        ticket.impetus,
        ticket.description,
        List.copyOf(ticket.acceptanceCriteria),
        comments);
  }

  @McpServer("repository")
  @Tool(
      name = "create_ticket",
      description =
          "File a new ticket on this project. It is created REPORTED, which means somebody has"
              + " said what is wrong and nothing more — refining it into a plan of work is the next"
              + " phase and not yours here. Use this for the small thing you found on the way — a"
              + " defect, or something that works and could work better — rather than proposing an"
              + " epic for it: an epic is a plan, a ticket is a note. The impetus is the whole of"
              + " what a filed ticket needs; the description is optional at intake and is"
              + " ordinarily left empty, because it is the refinement's output rather than yours.")
  public TicketSummary createTicket(
      @ToolArg(description = "short label for lists and breadcrumbs") String title,
      @ToolArg(description = "BUG for something behaving wrongly, IMPROVEMENT for something that"
              + " works and could be better. MAINTENANCE is reserved for the tickets the platform"
              + " files itself; a person files BUG or IMPROVEMENT")
          String type,
      @ToolArg(
              description =
                  "why this ticket exists, in your own words: either \"{some error} occurs {in"
                      + " some context}\" or \"{an existing part} should be {something to"
                      + " introduce or improve}\". Almost always one sentence, rarely a paragraph,"
                      + " very rarely two. For a bug you may include the steps to reproduce, and"
                      + " they do not count against that length. Keep to it: an impetus that grows"
                      + " into an essay is indistinguishable from the refined description and stops"
                      + " being a record of what was originally asked for.")
          String impetus,
      @ToolArg(
              required = false,
              description =
                  "the refinement's long-form Markdown output — what to do about the impetus."
                      + " Omit it at intake unless you are recording a decision that has already"
                      + " been made.")
          String description,
      @ToolArg(required = false, description = "who is looking at it; omit for nobody")
          String assignee,
      @ToolArg(
              required = false,
              description =
                  "acceptance criteria, in order, when they are already known; usually written"
                      + " later with update_ticket while refining. Each item: no line break, at most"
                      + " one '.', and fewer than 20 whitespace characters.")
          List<String> acceptanceCriteria) {
    WorkEntity ticket =
        entities
            .create(
                Archetype.TICKET,
                scope.requireProjectId(),
                EntityWrite.ticket(title, impetus, description, type, assignee)
                    .withAcceptanceCriteria(acceptanceCriteria),
                changedBy())
            .entity();
    announce();
    return summarize(ticket, projectSlug());
  }

  @McpServer("repository")
  @Tool(
      name = "update_ticket",
      description =
          "Change a ticket's title, impetus, description, type, assignee or acceptance criteria."
              + " Omitted fields keep"
              + " their current value. Writing the description is how the refine phase does its"
              + " work: it is what turns a REPORTED ticket into one that says what to do, and the"
              + " transition to REFINED is the claim that it now does. The impetus is editable"
              + " because a report filed in haste is often the wrong words for the right problem —"
              + " but never rewrite it to say what you decided: it is the record of what was"
              + " originally asked for, and the description is where a decision goes. That includes"
              + " a decision not to do the work at all: say why here or on the thread, then move"
              + " the ticket to DROPPED — a dropped ticket whose description still reads as a plan"
              + " tells the next reader nothing about why it stopped. The status is not editable"
              + " here — use transition_ticket, which is the only thing that moves it.")
  public TicketSummary updateTicket(
      @ToolArg(description = "id of a ticket in this project") String id,
      @ToolArg(required = false, description = "new title; omit to keep it") String title,
      @ToolArg(
              required = false,
              description =
                  "a correction of what was originally asked for; omit to keep it. Same length"
                      + " rule as at create: one sentence, rarely more.")
          String impetus,
      @ToolArg(required = false, description = "new description; omit to keep it")
          String description,
      @ToolArg(
              required = false,
              description =
                  "BUG or IMPROVEMENT; omit to keep it. MAINTENANCE is reserved for the tickets the"
                      + " platform files itself, and retyping one of those to BUG takes it over: the"
                      + " platform then no longer closes it")
          String type,
      @ToolArg(required = false, description = "new assignee; omit to keep the current one")
          String assignee,
      @ToolArg(
              required = false,
              description =
                  "the whole list of acceptance criteria, in order, replacing the current one;"
                      + " omit to keep it, an empty list clears it. What the ticket is accepted"
                      + " against: each item one short Markdown statement a reviewer can check."
                      + " Write them while refining — moving to REFINED needs them — and they are"
                      + " frozen from READY_FOR_DEV on. Each item: no line break, at most one '.',"
                      + " and fewer than 20 whitespace characters.")
          List<String> acceptanceCriteria) {
    requireTicketInProject(id);
    // Omitted means unchanged on this surface: the clear flags the REST route carries are a
    // deliberate act in a form, and a model that meant "no value" would reach for a null it cannot
    // express here anyway.
    WorkEntity ticket =
        entities
            .update(
                Archetype.TICKET,
                id,
                EntityWrite.ticketEdit(
                        title, impetus, false, description, false, type, assignee, false)
                    .withAcceptanceCriteria(acceptanceCriteria),
                changedBy())
            .entity();
    announce();
    return summarize(ticket, projectSlug());
  }

  /**
   * <b>"Transition" here is a LIFECYCLE move, and it is not the other transition.</b> This tool
   * moves one ticket along {@code REPORTED → REFINED → READY_FOR_DEV → IMPLEMENTING → IMPLEMENTED →
   * VERIFYING → VERIFIED → DONE} (with the two skips, READY_FOR_DEV → IMPLEMENTED and IMPLEMENTED →
   * VERIFIED), or
   * off that
   * line into {@code DROPPED}: it writes {@code entity.status} and nothing else, and which moves
   * are legal is {@code EntityStateMachine}'s to say and argued there — DONE, the last, has no exits.
   *
   * <p>{@code transition_entities} ({@link EntityMcpTools}, over {@code EntityTransitionService})
   * is the ARCHETYPE transition, which the unified-entity epic introduced: it restates what KIND a
   * row is and whose child it is. The two share a word and share nothing else — a lifecycle move
   * never changes an archetype, an archetype transition never applies a lifecycle's adjacency rule,
   * and neither is reachable from the other. The word alone will not tell a later reader which one
   * a call site means; the noun after it will.
   */
  @McpServer("repository")
  @Tool(
      name = "transition_ticket",
      description =
          "Move a ticket along its lifecycle. A status is a claim about what has been ACHIEVED, so"
              + " only move to one you can honestly make: REPORTED — somebody said what is wrong;"
              + " REFINED — the ticket now says what to do and has acceptanceCriteria (update_ticket);"
              + " READY_FOR_DEV — a PERSON scheduled it"
              + " (scheduling is a person's decision: this tool is refused it, PERSON_APPROVAL);"
              + " IMPLEMENTING — the implementation was"
              + " started (a dispatch moves the ticket here for you); IMPLEMENTED — the change is"
              + " released AND deployed, not merely merged; VERIFYING — the verification was started"
              + " (a dispatch moves the ticket here for you); VERIFIED — you checked the platform and it no"
              + " longer occurs; DONE — closed, which is a person's call; DROPPED — a decision was"
              + " taken not to do this work at all. ALONG THE PIPELINE MOVES ARE ADJACENT ONLY,"
              + " forward or back: REPORTED <-> REFINED <-> READY_FOR_DEV -> IMPLEMENTING <->"
              + " IMPLEMENTED <-> VERIFYING <-> VERIFIED -> DONE, one step at a time (IMPLEMENTING"
              + " has no move back: the way out of started work is DROPPED), with TWO SKIPS:"
              + " READY_FOR_DEV -> IMPLEMENTED and IMPLEMENTED -> VERIFIED directly are allowed, for"
              + " work finished without ever being moved to IMPLEMENTING or VERIFYING. Asking for"
              + " the status the ticket already has is refused."
              + " DONE IS FINAL: it has no exits at all, so a DONE ticket never moves again — if"
              + " a done change later turns out wrong, file a NEW ticket with create_ticket."
              + " DROPPED is off that line: it is reachable from REPORTED, REFINED, READY_FOR_DEV,"
              + " IMPLEMENTING,"
              + " IMPLEMENTED, VERIFYING and VERIFIED — any status that is not already closed — and it goes back only to"
              + " REPORTED. DROP A TICKET WHEN THE WORK IT ASKS FOR SHOULD NOT BE DONE: it"
              + " describes something that turned out not to be a problem, or that the platform"
              + " has since made meaningless, or that was deliberately decided against — say which"
              + " on the thread before you move it. Do NOT drop a ticket merely because it is hard,"
              + " stale or you could not finish it: block_entity with what is missing instead, and"
              + " DROPPED claims a decision that nobody took. Do not drop one that is DONE either;"
              + " that move does not exist, because DONE is final. Moving back corrects a claim"
              + " that turned out wrong. It is not how a phase reports failure: a phase that cannot"
              + " finish, or a verification that fails, is block_entity. Below DONE every move but"
              + " IMPLEMENTING's is reversible, and DROPPED reopens to REPORTED, so a wrong answer costs one more"
              + " call.")
  public TicketSummary transitionTicket(
      @ToolArg(
              description =
                  "id of a ticket in this project")
          String id,
      @ToolArg(
              description =
                  "the status to move to: REPORTED, REFINED, READY_FOR_DEV, IMPLEMENTING,"
                      + " IMPLEMENTED, VERIFYING, VERIFIED, DONE or DROPPED. On the pipeline it must"
                      + " be a neighbour of the ticket's current status, or IMPLEMENTED from"
                      + " READY_FOR_DEV or VERIFIED from IMPLEMENTED (the skips); DROPPED is reachable from any status that is not DONE, and"
                      + " reopens only to REPORTED; DONE is final and moves nowhere")
          String target) {
    requireTicketInProject(id);
    String changedBy = changedBy();
    // Through EntityResolutions (qits-395): a resolving move discards the ticket's refinement first.
    WorkEntityService.Transition moved =
        resolutions.transition(Archetype.TICKET, id, target, Mover.machine(changedBy));
    WorkEntity ticket = moved.entity();
    announce();
    // The agent's claim IS the trigger for the next phase, and this is where it lands: after the
    // move is recorded, outside its transaction, so a transition that failed speaks to nobody. The
    // stamp is passed in because this surface's fallback is AGENT where the REST one's is null.
    try {
      phaseAdvance.afterTransition(ticket, moved.statusBefore(), changedBy);
    } catch (RuntimeException e) {
      // It says it must not throw; a throw is a bug in it and must not turn a recorded transition
      // into a tool error the model would read as "the move did not happen".
      LOG.warnf(e, "Could not start the phase ticket %s just moved into", ticket.id);
    }
    return summarize(ticket, projectSlug());
  }

  /**
   * <b>Two verbs and not one boolean argument</b>, which is the only shape decision in this pair.
   * A tool's description is where an agent learns <em>when</em> to reach for it, and the two cases
   * want opposite sentences: blocking is how an agent that stops says what it stopped on, and
   * unblocking has to be talked into being used at all rather than left for somebody else to
   * notice. One tool with {@code blocked=true|false} would carry both arguments in
   * one paragraph, where each half is advice about the other half's mistake.
   *
   * <p>Both land on {@code EntityBlocks}, which is the same write the REST door makes and holds the
   * whole rule — see that class. Since qits-592 {@code block_entity} and {@code unblock_entity}
   * ({@link CommentMcpTools}) are the general pair; these two stay with their names and their {@code
   * TicketSummary} answer, for the names pre-approved elsewhere.
   */
  @McpServer("repository")
  @Tool(
      name = "block_ticket",
      description =
          "Block this ticket when you are stopping and cannot take the phase further: say what is in"
              + " the way or what is missing. The status stays where it is, because that is the"
              + " phase to resume, and the next transition clears the block. A ticket that should"
              + " not be done at all is transition_ticket to DROPPED instead. Refused (409) on a"
              + " ticket that is VERIFIED, DONE or DROPPED. block_entity does the same for an epic"
              + " or a campaign.")
  public TicketSummary blockTicket(
      @ToolArg(description = "id of a ticket in this project") String ticketId,
      @ToolArg(
              description =
                  "what is in the way, and what would clear it — one or two sentences, named"
                      + " concretely enough that somebody else could act on it. It lands on the"
                      + " ticket's thread as a comment.")
          String reason) {
    WorkEntity ticket = requireTicketInProject(ticketId);
    WorkEntity blocked = blocks.apply(ticket, true, reason, changedBy());
    announce();
    return summarize(blocked, projectSlug());
  }

  @McpServer("repository")
  @Tool(
      name = "unblock_ticket",
      description =
          "Say that what was in the way of this ticket is gone and its phase can run again. Call it"
              + " as soon as you know the blocker cleared, whether or not you are the one who"
              + " blocked it: a ticket left blocked after the fact reads as work nobody can pick"
              + " up, and the listing surfaces cannot tell a stale block from a live one. It moves"
              + " no status — the ticket resumes at the phase it was already in. The note is"
              + " optional and is worth writing where the answer is not obvious from the thread:"
              + " say what cleared it, not merely that it did.")
  public TicketSummary unblockTicket(
      @ToolArg(description = "id of a ticket in this project") String ticketId,
      @ToolArg(
              required = false,
              description =
                  "what cleared the blocker; omit if the thread already says. It lands on the"
                      + " ticket's thread as a comment.")
          String note) {
    WorkEntity ticket = requireTicketInProject(ticketId);
    WorkEntity unblocked = blocks.apply(ticket, false, note, changedBy());
    announce();
    return summarize(unblocked, projectSlug());
  }

  // The two ticket comment tools predate the thread every entity has (qits-551) and stay as
  // delegates onto the same service, names and shapes unchanged: both names are pre-approved in
  // other repositories. add_comment / update_comment on CommentMcpTools are the general pair.

  @McpServer("repository")
  @Tool(
      name = "add_ticket_comment",
      description =
          "Add a remark to a ticket's thread — what you found, what you tried, why you resolved it"
              + " the way you did. The thread is what the next reader has to go on, so a resolution"
              + " with no comment is a resolution nobody can check.")
  public CommentDetail addTicketComment(
      @ToolArg(description = "id of a ticket in this project") String ticketId,
      @ToolArg(description = "the remark, Markdown") String body) {
    requireTicketInProject(ticketId);
    EntityComment comment = thread.addComment(ticketId, body, changedBy());
    announce();
    return new CommentDetail(comment.id, comment.author, comment.body, comment.createdAt);
  }

  @McpServer("repository")
  @Tool(
      name = "update_ticket_comment",
      description =
          "Rewrite a remark already on a ticket's thread. Use it to correct or extend the note you"
              + " left earlier once you know more — an edit is honest where a second comment"
              + " contradicting the first leaves the next reader to work out which one still"
              + " holds. The body is the whole of what an edit changes: it replaces the remark"
              + " outright, it moves the comment's updatedAt, and it does NOT move the author —"
              + " who wrote a remark and who last changed it are different facts, and the second"
              + " one is the audit log's.")
  public CommentDetail updateTicketComment(
      @ToolArg(description = "id of a comment on a ticket in this project") String id,
      @ToolArg(description = "the remark as it should now read, Markdown; it replaces the old body")
          String body) {
    requireCommentInProject(id);
    EntityComment comment = thread.updateComment(id, body, changedBy());
    announce();
    return new CommentDetail(comment.id, comment.author, comment.body, comment.createdAt);
  }

  // --- Scoping --------------------------------------------------------------

  /**
   * Ensures {@code ticketId} names a ticket of the scoped project. A ticket elsewhere reads as not
   * found rather than as forbidden — the model is told nothing about what other projects hold.
   */
  private WorkEntity requireTicketInProject(String ticketId) {
    WorkEntity ticket = entities.get(Archetype.TICKET, ticketId);
    if (!scope.requireProjectId().equals(ticket.projectId)) {
      throw new NotFoundException("Ticket not found in this project: " + ticketId);
    }
    return ticket;
  }

  /**
   * Ensures {@code commentId} names a comment on a ticket of the scoped project — the same check
   * one row deeper, and the refusal names the <em>comment</em> rather than the ticket it hangs
   * under, because that is the id the caller supplied and the only one it should learn anything
   * about.
   */
  private EntityComment requireCommentInProject(String commentId) {
    EntityComment comment;
    try {
      comment = thread.getComment(commentId);
    } catch (NotFoundException e) {
      // The ticket-era wording, kept: this tool's refusals are part of the shape it keeps.
      throw new NotFoundException("Ticket comment not found: " + commentId);
    }
    if (!scope.requireProjectId().equals(entities.find(comment.entityId).projectId)) {
      throw new NotFoundException("Ticket comment not found in this project: " + commentId);
    }
    return comment;
  }

  // --- Plumbing -------------------------------------------------------------

  /** Tell the project's browsers to re-read the tickets. */
  private void announce() {
    changePublisher.fire(scope.requireProjectId(), ProjectChangeHint.Topic.TICKETS);
  }

  /** The audit's {@code changed_by}: the forwarded user, else the agent marker. */
  private String changedBy() {
    if (identity == null || identity.isAnonymous() || identity.getPrincipal() == null) {
      return AGENT;
    }
    return identity.getPrincipal().getName();
  }

  /**
   * The project slug the qualified ids in this call are rendered with, <b>resolved once</b>. Every
   * ticket a tool answers is in the session's project, so one lookup covers a whole listing.
   */
  private String projectSlug() {
    return scopeGuard.scopedProjectSlug();
  }

  private static TicketSummary summarize(WorkEntity ticket, String projectSlug) {
    return new TicketSummary(
        ticket.id,
        QualifiedEntityIds.render(projectSlug, ticket.number),
        ticket.slug,
        ticket.title,
        ticket.ticketType.name(),
        ticket.status,
        ticket.blocked,
        ticket.assignee,
        ticket.createdBy,
        ticket.impetus,
        ticket.description,
        List.copyOf(ticket.acceptanceCriteria));
  }
}
