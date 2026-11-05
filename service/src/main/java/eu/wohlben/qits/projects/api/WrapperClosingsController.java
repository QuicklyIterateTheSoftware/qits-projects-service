package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.projects.control.ReleaseRequests;
import eu.wohlben.qits.projects.entitieshost.EntityIdResolver;
import eu.wohlben.qits.projects.entitieshost.TicketWrapperDone;
import eu.wohlben.qits.projects.entity.ReleaseRequest;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * <b>Where each VERIFIED ticket's closing stands</b> (qits-1873): one row per TICKET of the project
 * at VERIFIED, with what the wrapper ledger says of its {@code ticket/<qualified id>} branch and
 * the verdict {@link TicketWrapperDone}'s close rules hold of it now — {@code NO_REQUEST}, {@code
 * IN_FLIGHT}, {@code CLOSING} or {@code UNPROVEN} (see {@link TicketWrapperDone.Verdict}). The
 * question a desk asks of a VERIFIED column that does not empty — "which of these will the platform
 * close, and which are stuck" — answered without reading each ticket's branch and request in turn.
 *
 * <p><b>Read off the rules, never beside them.</b> The verdict is {@link TicketWrapperDone}'s own
 * classification of the {@code Facts} its arrival and its reconcile read, so a ticket listed {@code
 * CLOSING} is one the next reconcile moves to DONE, and one listed {@code IN_FLIGHT} is one it
 * leaves. No git host is asked: {@code UNPROVEN} is exactly the case only the git host can still
 * prove ({@code DELETED_MERGED}), which stays the reconcile's.
 *
 * <p>{@code approval} is the request's {@code approvalState} as the release-request read answers it
 * ({@link ReleaseRequests#approvalStates}), derived on every read and never stored.
 *
 * <p>The roles match {@link BranchDeliveriesController}'s and {@link
 * ProjectReleaseRequestsController}'s, {@code qits:agent} included: nothing here is more than those
 * two reads and the project's work already tell. {@code project} is its id or slug; an unknown one
 * is a 404. The schemas are <b>named</b> ({@code WrapperClosings}, {@code WrapperClosing}), for
 * {@link ProjectReleaseRequestsController.GetProjectReleaseRequest}'s reason; every member is
 * always present, and one with no value is {@code null}.
 */
@Path("/projects/{project}/wrapper-closings")
@Produces(MediaType.APPLICATION_JSON)
@jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:admin-agent", "qits:desk-admin", "qits:system", "qits:agent"})
public class WrapperClosingsController {

  @Inject TicketWrapperDone wrapperDone;

  @Inject ReleaseRequests releaseRequests;

  @Inject EntityIdResolver resolver;

  /** The read's envelope. */
  @Schema(
      name = "WrapperClosings",
      description = "Every VERIFIED ticket of a project, with where its wrapper closing stands.")
  public record Answer(
      @Schema(description = "One per VERIFIED ticket, ordered by ticket number")
          List<Closing> closings) {}

  /** One VERIFIED ticket. */
  @Schema(
      name = "WrapperClosing",
      description =
          "One VERIFIED ticket's wrapper branch, its ledger row and request, and the verdict the"
              + " platform's close rules hold of it now.")
  public record Closing(
      @Schema(description = "The ticket's qualified id") String ticket,
      @Schema(description = "Its wrapper branch, ticket/<qualified id>") String branch,
      @Schema(
              description =
                  "NO_REQUEST (no wrapper, no ledger row, or a PUSHED row naming no request),"
                      + " IN_FLIGHT (the row names a request whose delivery has not ended),"
                      + " CLOSING (LIVE, or withdrawn as the empty wrapper: the platform moves the"
                      + " ticket to DONE) or UNPROVEN (the row ended without the ledger proving an"
                      + " ending: only the git host can still close it)",
              enumeration = {"NO_REQUEST", "IN_FLIGHT", "CLOSING", "UNPROVEN"})
          String verdict,
      @Schema(
              description = "The wrapper ledger row's step; null when there is no row",
              nullable = true)
          String step,
      @Schema(description = "The request the row names; null when none", nullable = true)
          String requestId,
      @Schema(description = "That request's qualified id", nullable = true)
          String requestQualifiedId,
      @Schema(description = "That request's state", nullable = true) String requestState,
      @Schema(
              description =
                  "That request's approval state as the release-request read derives it:"
                      + " NOT_REQUIRED, WAITING, APPROVED or DECLINED; null when no request",
              nullable = true)
          String approval,
      @Schema(description = "The row's version (RELEASED, LIVE)", nullable = true)
          String version) {

    static Closing of(
        TicketWrapperDone.Closing read, Map<String, ReleaseRequest.ApprovalState> approvals) {
      ReleaseRequest request = read.request();
      ReleaseRequest.ApprovalState approval = request == null ? null : approvals.get(request.id);
      return new Closing(
          read.qualifiedId(),
          read.branch(),
          read.verdict().name(),
          read.step(),
          read.requestId(),
          request == null ? null : request.qualifiedId,
          request == null || request.state == null ? null : request.state.name(),
          approval == null ? null : approval.name(),
          read.version());
    }
  }

  /** @param project the project's id or slug; unknown is a 404 */
  @GET
  @Operation(
      operationId = "listWrapperClosings",
      summary = "Where each VERIFIED ticket's wrapper closing stands",
      description =
          "One row per ticket at VERIFIED, ordered by number: its wrapper branch, the wrapper"
              + " ledger row's step and version, the request the row names (id, qualified id,"
              + " state, approval state) and the verdict of the platform's close rules —"
              + " NO_REQUEST, IN_FLIGHT, CLOSING (the platform moves it to DONE) or UNPROVEN (ended"
              + " without proof on the ledger; only the git host can still close it). Asks no git"
              + " host. An unknown project is a 404.")
  public Answer list(@PathParam("project") String project) {
    String projectId = resolver.resolveProject(project).id;
    List<TicketWrapperDone.Closing> read = wrapperDone.closings(projectId);
    Map<String, ReleaseRequest.ApprovalState> approvals =
        releaseRequests.approvalStates(
            read.stream().map(TicketWrapperDone.Closing::request).filter(Objects::nonNull).toList());
    return new Answer(read.stream().map(closing -> Closing.of(closing, approvals)).toList());
  }
}
