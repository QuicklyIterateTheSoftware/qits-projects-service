package eu.wohlben.qits.entities.api;

import eu.wohlben.qits.entities.control.EntitySummary;
import eu.wohlben.qits.entities.entity.WorkOutcome;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * <b>A project's work of every archetype: {@code GET /projects/api/projects/{project}/work}</b>
 * (qits-969) — the work family's listing, the archetype a filter rather than a path segment.
 *
 * <p>{@code {project}} is the project's id or its slug ({@code qits}). The whole planning tree,
 * flat — each root oldest first, its descendants depth-first in position order — narrowed by the
 * optional {@code archetype}, {@code status} and {@code parent} (qualified id or UUID; the direct
 * children of one entity), and since qits-248 by {@code outcome} (why a DROPPED item was closed)
 * and {@code supersededBy} (qualified id or UUID; the items naming that entity as what answers for
 * them — the reverse link), and since qits-634 by {@code tag} (the items carrying it). Since qits-2595 a long-running
 * agent narrows it by {@code assignee}, {@code unassigned=true} (work it may take, {@link
 * eu.wohlben.qits.entities.control.Takeable}) and {@code active=true}, sorts it with {@code
 * order=priority} ({@link eu.wohlben.qits.entities.control.BacklogOrder}) and cuts it with {@code
 * limit}. The answer is {@code {"entities": [...]}}, each a qualified {@link
 * EntitySummary} without its description — a ticket and an epic carry its {@code excerpt} instead
 * (qits-1821), a short plain-text lead for the desk's cards, null on every other archetype. {@link
 * WorkEntityDoors#list} is the implementation,
 * shared with {@code GET /projects/{projectId}/entities}. A read: {@code qits:agent}, unbound.
 *
 * <p>{@code GET …/work/briefs?id=qits-12&id=qits-14} (qits-1897) is the same family's read of a few
 * named items, in brief ({@link WorkBriefs}): what a page holding references to work items asks to
 * label them, in one call and one query rather than one {@code getWork} each. {@link
 * WorkEntityDoors#briefs} is the implementation.
 */
@Path("/projects/{project}/work")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"qits:admin", "qits:admin-agent", "qits:desk-admin", "qits:agent"})
public class ProjectWorkController {

  @Inject WorkEntityDoors doors;

  @Schema(name = "WorkList", description = "A project's work entities, in tree order.")
  public record WorkList(List<EntitySummary> entities) {}

  @GET
  @Operation(
      operationId = "listProjectWork",
      summary = "List a project's work of every archetype",
      description =
          "The project's whole planning tree, flat: each root oldest first, its descendants"
              + " depth-first in position order. Optionally narrowed to one archetype, one status,"
              + " the direct children of one parent (qualified id or UUID), one drop outcome, and"
              + " the items naming one entity (qualified id or UUID) as their supersededBy — what"
              + " that entity closed as a duplicate, resolved elsewhere or superseded, and the items"
              + " carrying one tag; and, for a long-running agent, the items assigned to one name,"
              + " the work it may take (unassigned=true) or the unfinished ones (active=true),"
              + " and the tickets made from one error issue (errorIssue)."
              + " order=priority answers in the backlog order instead, and limit keeps the first"
              + " rows. The project is its id or its slug. No entry carries its"
              + " description: a ticket or an epic answers an excerpt instead, a short plain-text"
              + " lead of it, and every other archetype answers null.")
  @APIResponse(
      responseCode = "200",
      description = "The work entities",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(implementation = WorkList.class)))
  @APIResponse(
      responseCode = "400",
      description =
          "An archetype, a status, an outcome or a tag naming none; a blank assignee; assignee"
              + " together with unassigned; unassigned or active other than true; an order other"
              + " than priority; a limit outside 1 to 200; an errorIssue that is no fingerprint")
  @APIResponse(
      responseCode = "404",
      description = "No such project, no such parent, or no such supersededBy entity")
  public WorkList list(
      @PathParam("project") String project,
      @QueryParam("archetype") String archetype,
      @QueryParam("status") String status,
      @QueryParam("parent") String parent,
      @Parameter(
              description =
                  "Only the DROPPED items closed with this outcome: DUPLICATE, RESOLVED_ELSEWHERE,"
                      + " SUPERSEDED, OBSOLETE, INVALID or WONT_DO.",
              schema = @Schema(implementation = WorkOutcome.class))
          @QueryParam("outcome")
          String outcome,
      @Parameter(
              description =
                  "Only the items naming this entity (qualified id or UUID) as their supersededBy.")
          @QueryParam("supersededBy")
          String supersededBy,
      @Parameter(
              description =
                  "Only the items carrying this tag: RESERVED_FOR_HUMAN (any case, or its kebab"
                      + " form reserved-for-human).")
          @QueryParam("tag")
          String tag,
      @Parameter(
              description =
                  "Only the items assigned to this name, matched exactly after trimming both."
                      + " Excludes unassigned=true.")
          @QueryParam("assignee")
          String assignee,
      @Parameter(
              description =
                  "true: only the work an agent may take — an epic or a ticket with no assignee,"
                      + " in REPORTED, READY_FOR_DEV, IMPLEMENTING, IMPLEMENTED or VERIFYING, not"
                      + " blocked, tagged neither RESERVED_FOR_HUMAN nor CONDITION, with no unmet"
                      + " need. Excludes assignee.",
              schema = @Schema(type = SchemaType.BOOLEAN))
          @QueryParam("unassigned")
          String unassigned,
      @Parameter(
              description = "true: only the items not VERIFIED, DONE or DROPPED.",
              schema = @Schema(type = SchemaType.BOOLEAN))
          @QueryParam("active")
          String active,
      @Parameter(
              description =
                  "priority: the backlog order instead of tree order — bugs first, then priority"
                      + " (highest first), then oldest by createdAt, then id.",
              schema = @Schema(enumeration = {"priority"}))
          @QueryParam("order")
          String order,
      @Parameter(
              description =
                  "At most this many items (1 to "
                      + WorkEntityDoors.MAX_LIST_LIMIT
                      + "), the first after filtering and ordering.",
              schema = @Schema(type = SchemaType.INTEGER, minimum = "1", maximum = "200"))
          @QueryParam("limit")
          String limit,
      @Parameter(
              description =
                  "Only the tickets made from this qits-observability error issue: its fingerprint,"
                      + " 32 lower-case hex digits, matched exactly.",
              schema = @Schema(type = SchemaType.STRING, pattern = "^[0-9a-f]{32}$"))
          @QueryParam("errorIssue")
          String errorIssue) {
    return new WorkList(
        doors.list(
            project, archetype, status, parent, outcome, supersededBy, tag, assignee, unassigned,
            active, order, limit, errorIssue));
  }

  @GET
  @Path("/briefs")
  @Operation(
      operationId = "listProjectWorkBriefs",
      summary = "Read a project's work items in brief, by qualified id",
      description =
          "The named work items of the project, of any archetype, each as its id, qualified id,"
              + " archetype, status, title and updatedAt — no description, impetus or excerpt."
              + " Name each item with a repeated id parameter (id=qits-12&id=qits-14), or several"
              + " comma-separated in one. An id that names nothing in the project — unknown,"
              + " another project's, or malformed — is left out rather than refused; duplicates"
              + " collapse, and the answer keeps the order the ids were first named. No ids answers"
              + " an empty list. The project is its id or its slug.")
  @APIResponse(
      responseCode = "200",
      description = "The named work items, in brief",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(implementation = WorkBriefs.class)))
  @APIResponse(
      responseCode = "400",
      description = "More than " + WorkEntityDoors.MAX_BRIEF_IDS + " distinct ids")
  @APIResponse(responseCode = "404", description = "No such project")
  public WorkBriefs briefs(
      @PathParam("project") String project,
      @Parameter(
              description =
                  "A qualified id (qits-12), repeated for each item; a value may also hold several"
                      + " comma-separated. At most "
                      + WorkEntityDoors.MAX_BRIEF_IDS
                      + " distinct ids.")
          @QueryParam("id")
          List<String> id) {
    return doors.briefs(project, id);
  }
}
