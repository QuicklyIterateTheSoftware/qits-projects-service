package eu.wohlben.qits.projects.entitieshost;

import eu.wohlben.qits.entities.control.DropOutcome;
import eu.wohlben.qits.entities.control.Mover;
import eu.wohlben.qits.entities.control.EntityWrite;
import eu.wohlben.qits.entities.control.EntityCommentService;
import eu.wohlben.qits.entities.control.WorkEntityService;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.EntityStatus;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.entities.entity.WorkOutcome;
import eu.wohlben.qits.entities.entity.TicketType;
import eu.wohlben.qits.entities.error.NotFoundException;
import eu.wohlben.qits.projects.api.ProjectChangeHint;
import eu.wohlben.qits.projects.api.ProjectChangePublisher;
import eu.wohlben.qits.projects.control.UnattendedGateTickets;
import eu.wohlben.qits.projects.control.WorkspaceAgentDispatch;
import eu.wohlben.qits.projects.control.gate.AutomationsGate;
import eu.wohlben.qits.projects.refinementhost.EntityResolutions;
import io.quarkus.arc.DefaultBean;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * The shipped {@link UnattendedGateTickets}: a red gate on a request nobody is watching becomes a
 * MAINTENANCE ticket in this same deployable's entities module, and the request ending closes it.
 *
 * <h2>Why it is an adapter at all, when nothing here is a network hop</h2>
 *
 * <p>{@code entitieshost} is named for {@code maintenancehost} and {@code workspacehost} and means the
 * same thing they do — the layer that owes {@code domain} an implementation of one of its ports —
 * even though the far side is a bean two modules over rather than an HTTP address. The seam exists
 * because <b>{@code entities} depends on {@code domain} nowhere and must keep not depending on it</b>:
 * it has its own package, its own error types and its own physical database, and it is the module
 * most likely to be lifted out next. {@code service} is the layer that may cross, the precedent
 * {@code projects.api.WorkDispatchController} already sets, and putting the class in a {@code *host} package is
 * that crossing declared in the package name rather than smuggled into a control class.
 *
 * <p>It is {@code @DefaultBean} for the reason every adapter here is: a test that wants to watch
 * what was filed supplies its own implementation and wins the injection without excluding this one.
 *
 * <h2>The dedupe, which is the part that would bite</h2>
 *
 * <p>The caller remembers the ticket on the request row and hands it back; this class decides
 * whether that ticket is still a place to put a failure. Anything still open → the failure is a
 * <b>comment</b>. Closed (DONE or DROPPED), or deleted, or naming nothing → a fresh ticket. That is
 * what keeps a repository that re-gates red twenty times over to one ticket, on exactly the
 * repository somebody is already trying to fix.
 *
 * <h2>Filed as MAINTENANCE, and closed as one</h2>
 *
 * <p>The ticket is typed {@link TicketType#MAINTENANCE} rather than BUG, and the word is what lets
 * {@link #ended} close it: a ticket still carrying it is the platform's own, and when its request
 * reaches FINALIZED, WITHDRAWN or OBSOLETE it goes DROPPED with a comment saying which. A person who
 * retypes it has taken it over, and then the ending is only said on the thread. Before this, every
 * such ticket stayed open after its request had long finished, and the backlog that built up was
 * made entirely of reports about things that had already stopped being true.
 *
 * <p><b>The type is no longer the only consent (qits-885).</b> Agents are dispatched onto MAINTENANCE
 * tickets now, and an agent working on one has taken it over exactly as a person who retyped it
 * has — without retyping anything. Worse, it is the agent's own fix that ends the request: the fix
 * releases, its tag reaches main, the request goes FINALIZED, and the drop used to land seconds
 * before the agent's IMPLEMENTED, which the closed ticket then refused (qits-1231). So before
 * dropping, {@link #ended} asks qits-workspaces through {@link WorkspaceAgentDispatch#hasActiveAgent}
 * whether an ACTIVE agent works on the ticket; if one does, the ending is said on the thread and
 * the ticket stays where its phase put it, for that agent to move. <b>An answer of "not known" is a
 * retry, not a drop</b>: the drop is the one outcome here the agent cannot undo, while a retry costs
 * the reconcile sweep one more visit. An assembly with no workspaces context at all has no agents to
 * ask about, and drops as before.
 *
 * <h2>Nothing here throws</h2>
 *
 * <p>The port says so and the reason is the call site: the gate has already decided and, on the
 * healed arm, the release has already happened. Every failure — a ticket store mid-cutover, a
 * project id naming no project, a ticket that vanished under us — is one behaviour: a WARN and an
 * empty answer, which the caller reads as "leave the link alone".
 */
@ApplicationScoped
@DefaultBean
public class TicketUnattendedGateTickets implements UnattendedGateTickets {

  private static final Logger LOG = Logger.getLogger(TicketUnattendedGateTickets.class);

  /**
   * Who the ticket is reported by. Not a person and not the robot that asked for the release: this
   * service is what noticed, and {@code createdBy} is never client-supplied anywhere else either.
   */
  static final String REPORTER = "qits-projects";

  @Inject WorkEntityService entities;

  @Inject EntityCommentService tickets;

  @Inject ProjectChangePublisher publisher;

  @Inject EntityResolutions resolutions;

  /**
   * Who is working on a ticket, asked before the platform drops it (qits-885). {@code Instance} like
   * every optional port here — {@code api.EntityDispatch} is the precedent — so an assembly without
   * qits-workspaces still closes its tickets.
   */
  @Inject Instance<WorkspaceAgentDispatch> workspaces;

  @Override
  public Optional<String> rejected(Rejection rejection) {
    try {
      WorkEntity open = reusableTicket(rejection.existingTicketId());
      if (open != null) {
        tickets.addComment(open.id, comment(rejection), REPORTER);
        redraw(rejection.projectId());
        LOG.infof(
            "Release request %s failed its gate again with nobody watching; commented on ticket %s",
            rejection.requestId(), open.id);
        return Optional.of(open.id);
      }
      WorkEntity filed =
          entities
              .create(
                  Archetype.TICKET,
                  rejection.projectId(),
                  EntityWrite.ticket(
                      title(rejection),
                      // The impetus is the one-sentence statement of what occurs; the long report
                      // goes in the description, where it always has. Both are written here because
                      // this filer is both the reporter and the only thing that will ever know
                      // these facts — nothing later can reconstruct which run came back red.
                      impetus(rejection),
                      body(rejection),
                      TicketType.MAINTENANCE.name(),
                      // No assignee. Nobody was watching this release; inventing an owner for the
                      // ticket would be the same guess one directory over.
                      null),
                  REPORTER)
              .entity();
      redraw(rejection.projectId());
      LOG.warnf(
          "Release request %s of %s was rejected with nobody watching it; filed MAINTENANCE ticket %s"
              + " (%s)",
          rejection.requestId(), named(rejection), filed.id, filed.slug);
      return Optional.of(filed.id);
    } catch (RuntimeException e) {
      LOG.warnf(
          e,
          "Could not file a gate-failure ticket for release request %s: %s",
          rejection.requestId(),
          rejection.detail());
      return Optional.empty();
    }
  }

  @Override
  public void released(String ticketId, String requestId, String repoName, String version) {
    try {
      WorkEntity ticket = reusableTicket(ticketId);
      if (ticket == null) {
        // Closed already — DONE or DROPPED — or gone. Either way somebody has finished with it and
        // a comment on a closed thread is noise.
        return;
      }
      tickets.addComment(
          ticket.id,
          "The release request `"
              + requestId
              + "`"
              + (repoName == null ? "" : " of `" + repoName + "`")
              + " passed its gate and released as **"
              + version
              + "**.\n\n"
              + "This ticket stays where it is for now: the request is still open until its tag"
              + " reaches main, and while it is typed MAINTENANCE the platform closes it itself"
              + " when the request is finalized. Retype it if there is something on this thread"
              + " that should outlive the request.",
          REPORTER);
      redraw(ticket.projectId);
      LOG.infof(
          "Release request %s released as %s; said so on ticket %s", requestId, version, ticket.id);
    } catch (RuntimeException e) {
      LOG.warnf(e, "Could not say on ticket %s that request %s released", ticketId, requestId);
    }
  }

  /**
   * Close the ticket of a request that ended, if it is still the platform's to close — see the port.
   *
   * <p><b>Five outcomes and one of them is "try again".</b> A ticket that is already closed (DONE or
   * DROPPED) or no longer exists has nothing left to do and answers true without a word: somebody
   * finished with it, and a comment on a closed thread is noise. A ticket a person retyped away from
   * MAINTENANCE is told the request ended and left open, and answers true too — the platform's part
   * is over. So is a MAINTENANCE ticket an ACTIVE agent is working on (qits-885): it is told the
   * request ended and left in whatever status its phase gave it, because that agent's IMPLEMENTED is
   * on its way and a dropped ticket would refuse it (qits-1231). Otherwise the ending goes on the
   * thread and the ticket goes DROPPED through {@link EntityResolutions}, which is the one door that
   * also tears down a refinement the ticket may hold. Only a failure that could go differently next
   * time answers false, and the caller's sweep asks again: the store unreachable, a write refused
   * mid-cutover — <b>or qits-workspaces unable to say whether an agent is on the ticket</b>, which
   * moves and says nothing, because a drop made on a guess is the one outcome the agent cannot undo.
   *
   * <p><b>"Not found" and "could not read" are told apart here</b>, where {@link #reusableTicket}
   * does not need to: the filer's worst case for a misread is a duplicate ticket, while this method's
   * would be a stamp that stops the sweep from ever closing a ticket that is still there. So only the
   * entities module's own {@link NotFoundException} reads as gone; any other failure is retried.
   *
   * <p>The ticket is moved before the ending is said on it, so a move that failed has written
   * nothing and its retry cannot repeat a sentence already on the thread.
   *
   * <p>The agent lookup is an HTTP call and is made from {@link #close} between the ticket store's
   * {@code requiringNew} blocks, never inside one: with the caller's transaction suspended there is
   * no transaction on the thread at all while qits-workspaces is asked.
   *
   * <p>{@link ActivateRequestContext} because the callers are the bus consumers, the release worker
   * and the scheduler — none with a request scope — and the transition's preview reads the row
   * outside any transaction, which Panache refuses to do without one.
   */
  @Override
  @ActivateRequestContext
  public boolean ended(
      String ticketId, String requestId, String repoName, Ending ending, String detail) {
    if (ticketId == null || ticketId.isBlank()) {
      return true;
    }
    try {
      // With any caller's transaction SUSPENDED, whoever the caller is. The ticket store is another
      // database and the two are never enlisted together (WorkEntityService: every write its own
      // requiringNew, no XA); its reads and writes bring their own transactions, and the one read
      // that does not — the transition's preview — must see no transaction rather than the
      // caller's. Inside the eventstream funnel's projects-database transaction that preview
      // answered "Ticket not found" for a ticket that was there (2026-09-29).
      return QuarkusTransaction.suspendingExisting()
          .call(() -> close(ticketId, requestId, repoName, ending, detail));
    } catch (RuntimeException e) {
      LOG.warnf(
          e,
          "Could not close ticket %s of release request %s, which ended as %s; the sweep will"
              + " try again",
          ticketId,
          requestId,
          ending);
      return false;
    }
  }

  /** {@link #ended}'s body, with no transaction of the caller's on the thread. */
  private boolean close(
      String ticketId, String requestId, String repoName, Ending ending, String detail) {
    WorkEntity ticket;
    try {
      // Its own transaction: whatever the caller had is suspended by now, so there is none here.
      ticket =
          QuarkusTransaction.requiringNew().call(() -> entities.get(Archetype.TICKET, ticketId));
    } catch (RuntimeException e) {
      if (!isNotFound(e)) {
        throw e;
      }
      LOG.debugf(
          "The ticket %s of ended release request %s is gone; nothing to close",
          ticketId, requestId);
      return true;
    }
    if (EntityStatus.DONE.name().equals(ticket.status)
        || EntityStatus.DROPPED.name().equals(ticket.status)) {
      return true;
    }
    if (ticket.ticketType != TicketType.MAINTENANCE) {
      tickets.addComment(
          ticket.id,
          endedSentence(requestId, repoName, ending, detail)
              + "\n\nThis ticket is no longer MAINTENANCE, so it is left open: whoever retyped it"
              + " has taken it over.",
          REPORTER);
      redraw(ticket.projectId);
      LOG.infof(
          "Release request %s ended as %s; ticket %s is %s now, so it was only told",
          requestId, ending, ticket.id, ticket.ticketType);
      return true;
    }
    // An agent on the ticket has taken it over as surely as a retype (qits-885): its own fix is
    // what ended the request, and its IMPLEMENTED is seconds behind. Asked here, after every cheaper
    // way out and before the drop, outside any transaction (see ended).
    Optional<Boolean> agent = agentOn(ticket.id);
    if (agent.isEmpty()) {
      // Neither move nor comment: the retry then starts from a clean thread. Unstamped, so the
      // sweep asks again — a drop on a guess would be the one outcome the agent cannot undo.
      LOG.warnf(
          "Release request %s ended as %s, but whether an agent works on its ticket %s is not"
              + " known; leaving it for the sweep",
          requestId, ending, ticket.id);
      return false;
    }
    if (agent.get()) {
      tickets.addComment(
          ticket.id,
          endedSentence(requestId, repoName, ending, detail)
              + "\n\nAn agent is working on this ticket, so the platform leaves it where its phase"
              + " put it for that agent to transition.",
          REPORTER);
      redraw(ticket.projectId);
      LOG.infof(
          "Release request %s ended as %s; an agent works on its ticket %s, so it was only told",
          requestId, ending, ticket.id);
      return true;
    }
    // The move FIRST and the comment after it: a failed move then leaves nothing written, so the
    // retry cannot say the same sentence twice; a failed comment after a move that landed is one
    // WARN, and the ticket is closed all the same — a retry would find it DROPPED and do nothing.
    // WHY it closed travels in the move itself (qits-248): OBSOLETE, the subject — a stuck release
    // request — went away by itself. The comment is the narrative, and no longer the only record.
    resolutions.transition(
        Archetype.TICKET,
        ticket.id,
        EntityStatus.DROPPED.name(),
        Mover.machine(REPORTER),
        DropOutcome.of(WorkOutcome.OBSOLETE, null));
    try {
      tickets.addComment(
          ticket.id,
          endedSentence(requestId, repoName, ending, detail)
              + "\n\nThere is no stuck request left for this ticket to be about, so the platform"
              + " drops it. If the failure comes back, a fresh request's red gate files a fresh"
              + " ticket.",
          REPORTER);
    } catch (RuntimeException e) {
      LOG.warnf(e, "Dropped ticket %s but could not say why on its thread", ticket.id);
    }
    redraw(ticket.projectId);
    LOG.infof(
        "Release request %s ended as %s; dropped its MAINTENANCE ticket %s",
        requestId, ending, ticket.id);
    return true;
  }

  /**
   * Whether an ACTIVE agent works on the ticket, or empty when qits-workspaces could not say. An
   * assembly with no implementation of the port has no agents anywhere, which is a definite "no".
   */
  private Optional<Boolean> agentOn(String ticketId) {
    if (!workspaces.isResolvable()) {
      return Optional.of(false);
    }
    Optional<Boolean> answer = workspaces.get().hasActiveAgent(ticketId);
    // The port says never null; a null is read as what it is, an answer nobody gave.
    return answer == null ? Optional.empty() : answer;
  }

  /** The entities module's 404, however the transaction wrapper handed it back. */
  private static boolean isNotFound(Throwable e) {
    for (Throwable cause = e; cause != null; cause = cause.getCause()) {
      if (cause instanceof NotFoundException) {
        return true;
      }
    }
    return false;
  }

  /** What happened to the request, in one sentence that names the version or the successor. */
  static String endedSentence(String requestId, String repoName, Ending ending, String detail) {
    String request =
        "The release request "
            + code(requestId)
            + (repoName == null ? "" : " of " + code(repoName));
    return switch (ending) {
      case FINALIZED ->
          request
              + " is **FINALIZED**: "
              + (detail == null ? "its release" : code(detail))
              + " reached main.";
      case OBSOLETE ->
          request
              + " is **OBSOLETE**: "
              + (detail == null
                  ? "a later request"
                  : "release request " + code(detail))
              + " folds its release in and releases past it.";
      case WITHDRAWN ->
          request + " was **WITHDRAWN**" + (detail == null ? "." : ": " + detail);
    };
  }

  // ---- the pieces ------------------------------------------------------------------------------

  /**
   * The ticket to put this failure on, or null where there is none to put it on. The probe is
   * <b>not closed</b> rather than any one status, and that is the whole of the rule: a closed
   * thread is one a person has finished with, and that is the only condition under which a fresh
   * failure deserves a fresh ticket rather than a comment.
   *
   * <p><b>There are two words that close a thread and the probe names both.</b> DONE says the
   * matter was dealt with; DROPPED says a decision was taken not to deal with it. They disagree
   * about everything except the one fact this method asks about — that a person is finished with
   * the thread — and commenting a fresh red gate onto either would be arguing with that decision in
   * a place nobody is reading. A gate that goes red again after the thread was dropped is new
   * evidence against the decision, which is a report of its own and not a footnote on the old one.
   *
   * <p><b>An IMPLEMENTED gate ticket is still the one to reuse</b>, and it is the case worth
   * stating because it reads like a closed one. IMPLEMENTED says a fix was released and that verify
   * is running — so a gate going red again while it holds is exactly the evidence that phase exists
   * to gather, and it belongs on that thread. Filing a second ticket there would split the
   * verification from the fix it is verifying and hide the most useful fact either one has. The
   * same reading covers REPORTED, REFINED and VERIFIED: none of them claims anybody is done.
   */
  private WorkEntity reusableTicket(String ticketId) {
    if (ticketId == null || ticketId.isBlank()) {
      return null;
    }
    WorkEntity ticket;
    try {
      // In a transaction of its own, and that is not decoration: the release arm runs on the
      // release-request worker, a plain thread with no request scope and no session on it, where a
      // bare Panache read throws rather than answering. WorkEntityService's writes each open their own;
      // this read had none, and the loop was closed on a log line and nowhere else.
      ticket = QuarkusTransaction.requiringNew().call(() -> entities.get(Archetype.TICKET, ticketId));
    } catch (RuntimeException e) {
      // A ticket somebody deleted. "There is no ticket" is the honest reading and files a fresh one.
      LOG.debugf("Gate-failure ticket %s could not be read; treating it as gone", ticketId);
      return null;
    }
    // The merged row stores the status word, so the two closing words are compared by name against
    // the column.
    return EntityStatus.DONE.name().equals(ticket.status)
            || EntityStatus.DROPPED.name().equals(ticket.status)
        ? null
        : ticket;
  }

  static String title(Rejection rejection) {
    return "The maintenance release request of " + named(rejection) + " is failing its gate";
  }

  /**
   * The one sentence that says what occurs, in the shape {@code WorkEntity.impetus} asks for — the
   * whole report is the description, and the length rule there is why it is not repeated here.
   *
   * <p>It names the repository and not the request id: a request is re-armed and superseded while
   * the impetus must go on reading true for the life of the ticket, and what brought the ticket
   * about is that this repository's gate is red with nobody watching.
   */
  static String impetus(Rejection rejection) {
    return "The maintenance release request of `"
        + named(rejection)
        + "` fails its gate with nobody watching it, so the repository stops moving.";
  }

  /**
   * The body, and every fact in it is one this service already holds: the repository, the branches
   * being folded, the fold itself, the run that came back red and this service's own rejection
   * sentence verbatim, so the ticket and the release request never say two different things.
   *
   * <p>A failed release-request automation (qits-1561) says so instead of a red build, and names the
   * three ways out: push, re-run, waive.
   *
   * <p>It deliberately does not guess at the cause. Reading the build log is the job of whoever —
   * or whatever — picks this ticket up; "Assign agent" is one press away on this very page.
   */
  static String body(Rejection rejection) {
    StringBuilder text = new StringBuilder();
    text.append("A release of `")
        .append(named(rejection))
        .append("` was asked for by ")
        .append(askedBy(rejection))
        .append(" — ")
        .append(
            rejection.participants() != null && rejection.participants().size() > 1
                ? "machines"
                : "a machine")
        .append(", so nobody is waiting on it — and ");
    if (isAutomation(rejection)) {
      text.append("a release-request automation failed on its fold.")
          .append(" The repository stops moving here until somebody answers the automation: the")
          .append(" request re-arms by itself on the next push to a participating branch.\n\n");
    } else {
      text.append("its build came back red.")
          .append(" The repository stops moving here until somebody fixes the build: the request")
          .append(" re-arms by itself on the next push to a participating branch.\n\n");
    }
    text.append("| | |\n|---|---|\n");
    row(text, "Repository", code(named(rejection)));
    row(text, "Repository id", code(rejection.repoId()));
    row(text, "Release request", code(rejection.qualifiedId()));
    row(
        text,
        "Branches folded",
        rejection.branches() == null || rejection.branches().isEmpty()
            ? "_none recorded_"
            : rejection.branches().stream().map(TicketUnattendedGateTickets::code).reduce(
                (a, b) -> a + ", " + b).orElse("—"));
    row(text, "Folded sha", code(rejection.mergedSha()));
    row(
        text,
        isAutomation(rejection) ? "Automation run" : "Run",
        (isAutomation(rejection) && rejection.runId() == null
                ? "_none recorded_"
                : code(rejection.runId()))
            + " ("
            + rejection.status()
            + ")");
    text.append("\n**The gate's own words:** ").append(rejection.detail()).append("\n\n");
    text.append("Reported by qits-projects because the release request has no human or agent")
        .append(" participant.");
    if (isAutomation(rejection)) {
      text.append(" Read the automation run's log to find out why it failed; this report")
          .append(" deliberately does not guess. Then push a fix to a participating branch, re-run")
          .append(" the automation (`qits maintenance automation run`), or waive the automations")
          .append(" at this fold.\n");
    } else {
      text.append(" Read the run's log in qits-ci to find out why the build failed; this report")
          .append(" deliberately does not guess.\n");
    }
    return text.toString();
  }

  /** The machine participants, each in bold, joined with "and" — or a placeholder where none. */
  static String askedBy(Rejection rejection) {
    List<String> participants = rejection.participants();
    if (participants == null || participants.isEmpty()) {
      return "**an unnamed caller**";
    }
    return participants.stream()
        .map(participant -> "**" + participant + "**")
        .collect(java.util.stream.Collectors.joining(" and "));
  }

  /** One further red verdict on a ticket that is already open. */
  static String comment(Rejection rejection) {
    if (isAutomation(rejection)) {
      return "The gate is still red. "
          + (rejection.runId() == null
              ? "A release-request automation"
              : "Automation run " + code(rejection.runId()))
          + " "
          + rejection.status()
          + " for the fold "
          + code(rejection.mergedSha())
          + " of release request "
          + code(rejection.qualifiedId())
          + ".\n\n> "
          + rejection.detail();
    }
    return "The gate is still red. Run "
        + code(rejection.runId())
        + " finished "
        + rejection.status()
        + " for the fold "
        + code(rejection.mergedSha())
        + " of release request "
        + code(rejection.qualifiedId())
        + ".\n\n> "
        + rejection.detail();
  }

  /** Whether a failed release-request automation rejected the fold, rather than a red CI run. */
  private static boolean isAutomation(Rejection rejection) {
    return AutomationsGate.KIND.equals(rejection.gate());
  }

  private static void row(StringBuilder text, String label, String value) {
    text.append("| ").append(label).append(" | ").append(value).append(" |\n");
  }

  private static String code(String value) {
    return value == null ? "—" : "`" + value + "`";
  }

  /** The repository as a person would name it; the row id is the fallback and never a name. */
  private static String named(Rejection rejection) {
    return rejection.repoName() != null ? rejection.repoName() : rejection.repoId();
  }

  /**
   * Open browsers on this project redraw their ticket list. Best effort inside a best-effort port:
   * a hint that was not fired costs a refresh, and must not cost the ticket.
   */
  private void redraw(String projectId) {
    try {
      publisher.fire(projectId, ProjectChangeHint.Topic.TICKETS);
    } catch (RuntimeException e) {
      LOG.debugf(e, "Could not fire the TICKETS hint for project %s", projectId);
    }
  }
}
