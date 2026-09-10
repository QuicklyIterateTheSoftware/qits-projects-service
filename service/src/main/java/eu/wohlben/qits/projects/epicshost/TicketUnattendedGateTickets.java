package eu.wohlben.qits.projects.epicshost;

import eu.wohlben.qits.epics.control.TicketService;
import eu.wohlben.qits.epics.entity.Ticket;
import eu.wohlben.qits.epics.entity.TicketStatus;
import eu.wohlben.qits.epics.entity.TicketType;
import eu.wohlben.qits.projects.api.ProjectChangeHint;
import eu.wohlben.qits.projects.api.ProjectChangePublisher;
import eu.wohlben.qits.projects.control.UnattendedGateTickets;
import io.quarkus.arc.DefaultBean;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * The shipped {@link UnattendedGateTickets}: a red gate on a request nobody is watching becomes a
 * BUG ticket in this same deployable's epics module.
 *
 * <h2>Why it is an adapter at all, when nothing here is a network hop</h2>
 *
 * <p>{@code epicshost} is named for {@code maintenancehost} and {@code workspacehost} and means the
 * same thing they do — the layer that owes {@code domain} an implementation of one of its ports —
 * even though the far side is a bean two modules over rather than an HTTP address. The seam exists
 * because <b>{@code epics} depends on {@code domain} nowhere and must keep not depending on it</b>:
 * it has its own package, its own error types and its own physical database, and it is the module
 * most likely to be lifted out next. {@code service} is the layer that may cross, the precedent
 * {@code TicketDispatchController} already sets, and putting the class in a {@code *host} package is
 * that crossing declared in the package name rather than smuggled into a control class.
 *
 * <p>It is {@code @DefaultBean} for the reason every adapter here is: a test that wants to watch
 * what was filed supplies its own implementation and wins the injection without excluding this one.
 *
 * <h2>The dedupe, which is the part that would bite</h2>
 *
 * <p>The caller remembers the ticket on the request row and hands it back; this class decides
 * whether that ticket is still a place to put a failure. Open → the failure is a <b>comment</b>.
 * Resolved, or deleted, or naming nothing → a fresh ticket. That is what keeps a repository that
 * re-gates red twenty times over to one ticket, on exactly the repository somebody is already trying
 * to fix.
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

  @Inject TicketService tickets;

  @Inject ProjectChangePublisher publisher;

  @Override
  public Optional<String> rejected(Rejection rejection) {
    try {
      Ticket open = openTicket(rejection.existingTicketId());
      if (open != null) {
        tickets.addComment(open.id, comment(rejection), REPORTER);
        redraw(rejection.projectId());
        LOG.infof(
            "Release request %s failed its gate again with nobody watching; commented on ticket %s",
            rejection.requestId(), open.id);
        return Optional.of(open.id);
      }
      Ticket filed =
          tickets.create(
              rejection.projectId(),
              title(rejection),
              body(rejection),
              TicketType.BUG.name(),
              // No assignee. Nobody was watching this release; inventing an owner for the ticket
              // would be the same guess one directory over.
              null,
              REPORTER);
      redraw(rejection.projectId());
      LOG.warnf(
          "Release request %s of %s was rejected with nobody watching it; filed BUG ticket %s (%s)",
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
      Ticket ticket = openTicket(ticketId);
      if (ticket == null) {
        // Resolved already, or gone. Either way somebody has dealt with it and a comment on a
        // closed thread is noise.
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
              + "This ticket is deliberately left OPEN: a green build says the fold passes now, not"
              + " that everything said on this thread is handled. Resolve it if there is nothing"
              + " left in it.",
          REPORTER);
      redraw(ticket.projectId);
      LOG.infof(
          "Release request %s released as %s; said so on ticket %s", requestId, version, ticket.id);
    } catch (RuntimeException e) {
      LOG.warnf(e, "Could not say on ticket %s that request %s released", ticketId, requestId);
    }
  }

  // ---- the pieces ------------------------------------------------------------------------------

  /**
   * The ticket to put this failure on, or null where there is none to put it on. A resolved ticket
   * is not one: the failure is back and it deserves a report somebody will see in their open list,
   * not a comment under a thread that reads as finished.
   */
  private Ticket openTicket(String ticketId) {
    if (ticketId == null || ticketId.isBlank()) {
      return null;
    }
    Ticket ticket;
    try {
      // In a transaction of its own, and that is not decoration: the release arm runs on the
      // release-request worker, a plain thread with no request scope and no session on it, where a
      // bare Panache read throws rather than answering. TicketService's writes each open their own;
      // this read had none, and the loop was closed on a log line and nowhere else.
      ticket = QuarkusTransaction.requiringNew().call(() -> tickets.get(ticketId));
    } catch (RuntimeException e) {
      // A ticket somebody deleted. "There is no ticket" is the honest reading and files a fresh one.
      LOG.debugf("Gate-failure ticket %s could not be read; treating it as gone", ticketId);
      return null;
    }
    return ticket.status == TicketStatus.OPEN ? ticket : null;
  }

  static String title(Rejection rejection) {
    return "The maintenance release request of " + named(rejection) + " is failing its gate";
  }

  /**
   * The body, and every fact in it is one this service already holds: the repository, the branches
   * being folded, the fold itself, the run that came back red and this service's own rejection
   * sentence verbatim, so the ticket and the release request never say two different things.
   *
   * <p>It deliberately does not guess at the cause. Reading the build log is the job of whoever —
   * or whatever — picks this ticket up; "Assign agent" is one press away on this very page.
   */
  static String body(Rejection rejection) {
    StringBuilder text = new StringBuilder();
    text.append("A release of `")
        .append(named(rejection))
        .append("` was asked for by **")
        .append(rejection.requester())
        .append("** — a machine, so nobody is waiting on it — and its gating build came back red.")
        .append(" The repository stops moving here until somebody fixes the build: the request")
        .append(" re-arms by itself on the next push to a participating branch.\n\n");
    text.append("| | |\n|---|---|\n");
    row(text, "Repository", code(named(rejection)));
    row(text, "Repository id", code(rejection.repoId()));
    row(text, "Release request", code(rejection.requestId()));
    row(
        text,
        "Branches folded",
        rejection.branches() == null || rejection.branches().isEmpty()
            ? "_none recorded_"
            : rejection.branches().stream().map(TicketUnattendedGateTickets::code).reduce(
                (a, b) -> a + ", " + b).orElse("—"));
    row(text, "Folded sha", code(rejection.mergedSha()));
    row(text, "Gating run", code(rejection.runId()) + " (" + rejection.status() + ")");
    text.append("\n**The gate's own words:** ").append(rejection.detail()).append("\n\n");
    text.append("Reported by qits-projects because the release request has no human requester.")
        .append(" Read the run's log in qits-ci to find out why the build failed; this report")
        .append(" deliberately does not guess.\n");
    return text.toString();
  }

  /** One further red verdict on a ticket that is already open. */
  static String comment(Rejection rejection) {
    return "The gate is still red. Gating run "
        + code(rejection.runId())
        + " finished "
        + rejection.status()
        + " for the fold "
        + code(rejection.mergedSha())
        + " of release request "
        + code(rejection.requestId())
        + ".\n\n> "
        + rejection.detail();
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
