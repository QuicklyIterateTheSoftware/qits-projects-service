package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.projects.control.ProjectService;
import eu.wohlben.qits.projects.desktriggers.DeskTriggerHistory;
import eu.wohlben.qits.projects.entity.DeskTriggerEvent;
import eu.wohlben.qits.projects.entity.DeskTriggerWindow;
import eu.wohlben.qits.projects.error.BadRequestException;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * <b>What the project's delivery desk was told, and whether it took it</b> (epic qits-1604, task
 * qits-1612): the desk trigger windows of one project, newest first, each with the message it
 * became and the lines it collected. The audit of the trigger engine ({@code desktriggers/}): an
 * OPEN window is what is collecting now, an OWED one says in {@code attempts}, {@code lastStatus}
 * and {@code lastDetail} why the desk has not taken it yet, DELIVERED or QUEUED name the desk
 * command that took it, and FAILED says it never will.
 *
 * <p>The roles match {@link ProjectReleaseRequestsController}'s: a person, an admin agent, a
 * platform service and a plain agent. The last matters most — the delivery desk's own token holds
 * {@code qits:agent}, and this is how the desk, or an agent verifying it, reads what it was sent;
 * the desk's transcript is not readable on that credential, so the window's {@code acceptedAt} and
 * {@code commandId} are the evidence of delivery.
 *
 * <p>An unknown project is a 404 rather than an empty list, {@link
 * ProjectReleaseRequestsController}'s reasoning: "nothing was sent" must not be something a
 * mistyped id can say.
 *
 * <p>The three schemas are <b>named</b> ({@code DeskTriggerMessages}, {@code DeskTriggerMessage},
 * {@code DeskTriggerEvent}), so no anonymous {@code Response} schema in {@code docs/openapi.yml} is
 * renumbered by their arrival — {@link ProjectReleaseRequestsController.GetProjectReleaseRequest}'s
 * reason. Every member is always present; one with no value is {@code null}, never left out.
 */
@Path("/projects/{projectId}/desk-triggers")
@Produces(MediaType.APPLICATION_JSON)
@jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:admin-agent", "qits:system", "qits:agent"})
public class ProjectDeskTriggersController {

  /** How many windows a read answers when it names no {@code limit}. */
  static final int DEFAULT_LIMIT = 20;

  /** The most a read may ask for. */
  static final int MAX_LIMIT = 100;

  @Inject DeskTriggerHistory history;

  @Inject ProjectService projects;

  /** The read's envelope. */
  @Schema(
      name = "DeskTriggerMessages",
      description = "A project's desk trigger messages, newest first by openedAt.")
  public record Messages(List<Message> messages) {}

  /** One window: the message it became, how its delivery went, and the lines it collected. */
  @Schema(
      name = "DeskTriggerMessage",
      description =
          "One desk trigger window of the project: what it collected, the message it became and"
              + " how its delivery to the delivery desk went.")
  public record Message(
      UUID id,
      @Schema(description = "OPEN, OWED, DELIVERED, QUEUED or FAILED") String state,
      @Schema(description = "When the first trigger opened the window") Instant openedAt,
      @Schema(description = "When it stops admitting; set at the open and never moved")
          Instant closesAt,
      @Schema(description = "When it was closed and its message rendered; null while OPEN",
          nullable = true)
          Instant closedAt,
      @Schema(description = "When the desk's daemon took the message; null until it did",
          nullable = true)
          Instant acceptedAt,
      @Schema(description = "How many deliveries were tried") int attempts,
      @Schema(description = "The HTTP status of the last attempt; null before the first",
          nullable = true)
          Integer lastStatus,
      @Schema(description = "Why the last attempt was refused or failed; null when it was not",
          nullable = true)
          String lastDetail,
      @Schema(description = "The desk command the message was given to; null until accepted",
          nullable = true)
          String commandId,
      @Schema(description = "Its place in the desk's prompt queue when QUEUED, else null",
          nullable = true)
          Integer queuePosition,
      @Schema(description = "The exact text sent to the desk; null while OPEN", nullable = true)
          String message,
      @Schema(description = "The lines it collected, by admittedAt then itemRef")
          List<Event> events) {

    static Message of(DeskTriggerHistory.Entry entry) {
      DeskTriggerWindow window = entry.window();
      return new Message(
          window.id,
          window.state.name(),
          window.openedAt,
          window.closesAt,
          window.closedAt,
          window.acceptedAt,
          window.attempts,
          window.lastStatus,
          window.lastDetail,
          window.commandId,
          window.queuePosition,
          window.message,
          entry.events().stream().map(Event::of).toList());
    }
  }

  /** One line of a window's message. */
  @Schema(
      name = "DeskTriggerEvent",
      description = "One item a desk trigger source admitted into a window.")
  public record Event(
      @Schema(description = "The source that admitted it: work-verified or approval-needed")
          String source,
      @Schema(description = "The item as the desk is shown it, e.g. qits-1580") String itemRef,
      @Schema(description = "The item's own id, when the source knows one", nullable = true)
          String itemId,
      @Schema(description = "What follows the item in brackets", nullable = true) String label,
      @Schema(description = "Who or what caused it", nullable = true) String cause,
      @Schema(description = "The bus event that carried it") UUID eventId,
      @Schema(description = "When it happened") Instant occurredAt) {

    static Event of(DeskTriggerEvent event) {
      return new Event(
          event.source,
          event.itemRef,
          event.itemId,
          event.label,
          event.cause,
          event.eventId,
          event.occurredAt);
    }
  }

  /**
   * @param limit how many windows, newest first: 20 when omitted, 1 to 100; anything else is a 400
   */
  @GET
  @Operation(
      operationId = "listDeskTriggerMessages",
      summary = "The messages the project's delivery desk was sent by its desk triggers",
      description =
          "Newest first by openedAt, every state: OPEN (collecting), OWED (closed, not yet taken —"
              + " attempts, lastStatus and lastDetail say why), DELIVERED or QUEUED (taken, with"
              + " acceptedAt and commandId) and FAILED (never to be delivered). Each carries the"
              + " lines it collected, by admittedAt then itemRef. limit defaults to 20 and may be"
              + " 1 to 100. An unknown project is a 404.")
  public Messages list(
      @PathParam("projectId") String projectId, @QueryParam("limit") Integer limit) {
    int n = limit == null ? DEFAULT_LIMIT : limit;
    if (n < 1 || n > MAX_LIMIT) {
      throw new BadRequestException(
          "limit must be between 1 and " + MAX_LIMIT + ", was " + limit);
    }
    projects.get(projectId); // 404 if the project does not exist
    return new Messages(history.recent(projectId, n).stream().map(Message::of).toList());
  }
}
