package eu.wohlben.qits.entities.api;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.entities.control.IdeaService;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.Idea;
import eu.wohlben.qits.entities.entity.IdeaStatus;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.entities.error.BadRequestException;
import eu.wohlben.qits.projects.api.QualifiedEntityIds;
import eu.wohlben.qits.projects.entitieshost.EntityIdResolver;
import eu.wohlben.qits.projects.entity.Project;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * <b>Ideas: {@code /projects/api/ideas}</b> (qits-1387, epic qits-888).
 *
 * <p>An idea is a short, titled note a project keeps, which an epic, a ticket or a campaign can
 * name as its inspiration ({@code inspiredBy} on {@code /work}). It is <b>not a work archetype</b>:
 * no lifecycle a caller moves, no dispatch, no thread, no children. Its {@code status} — PLANNED,
 * QUEUED, DONE — is derived from the work naming it and stored. There is <b>no delete</b>: an idea is
 * archived with a reason and can be unarchived.
 *
 * <p>Every rule is {@link IdeaService}'s; this class reads bodies, resolves the project (its id or
 * its slug), binds an agent to its own project before a write, and shapes the answer. <b>The roles
 * mirror {@link WorkController}'s</b>: the writes admit {@code qits:admin}, {@code
 * qits:admin-agent}, {@code qits:desk-admin} and {@code qits:agent} (bound to its project); the
 * reads admit those and {@code qits:system}.
 */
@Path("/ideas")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"qits:admin", "qits:admin-agent", "qits:desk-admin", "qits:agent"})
public class IdeaController {

  /** What a patch may name. */
  private static final Set<String> PATCHABLE = Set.of("title", "description", "enabledBy");

  /** What the server writes on an idea, refused by a patch with the door that moves it. */
  private static final Map<String, String> NOT_PATCHED =
      Map.of(
          "status", "derived from the work naming the idea, never written",
          "archived", "moved by POST /projects/api/ideas/{id}/archive and …/unarchive",
          "archiveReason", "stated with POST /projects/api/ideas/{id}/archive",
          "archivedAt", "stamped by POST /projects/api/ideas/{id}/archive",
          "id", "server-owned",
          "projectId", "server-owned: an idea never moves between projects",
          "createdAt", "server-owned",
          "updatedAt", "server-owned");

  @Inject IdeaService ideas;

  @Inject EntityIdResolver ids;

  @Inject SecurityIdentity identity;

  // --- the wire --------------------------------------------------------------------------------

  @Schema(name = "Idea", description = "An idea of a project. Its status is derived, never written.")
  public record IdeaDto(
      String id,
      String projectId,
      @Schema(description = "One line: no line break, at most one '.', fewer than 20 whitespace"
              + " characters.")
          String title,
      @Schema(nullable = true, description = "At most 3 paragraphs and 600 characters.")
          String description,
      @Schema(nullable = true, description = "The one idea of the same project enabling this one.")
          String enabledBy,
      @Schema(
              description =
                  "PLANNED (no counted work names it), QUEUED (some does, not all DONE) or DONE"
                      + " (every counted item DONE). DROPPED work is not counted.",
              enumeration = {"PLANNED", "QUEUED", "DONE"})
          String status,
      boolean archived,
      @Schema(nullable = true) String archiveReason,
      @Schema(nullable = true) Instant archivedAt,
      Instant createdAt,
      Instant updatedAt) {

    static IdeaDto of(Idea idea) {
      return new IdeaDto(
          idea.id,
          idea.projectId,
          idea.title,
          idea.description,
          idea.enabledBy,
          idea.status == null ? null : idea.status.name(),
          idea.archived,
          idea.archiveReason,
          idea.archivedAt,
          idea.createdAt,
          idea.updatedAt);
    }
  }

  @Schema(name = "IdeaList", description = "A project's ideas, oldest first.")
  public record IdeaList(List<IdeaDto> ideas) {}

  @Schema(name = "IdeaRef", description = "Another idea, in brief.")
  public record IdeaRef(String id, String title, String status, boolean archived) {

    static IdeaRef of(Idea idea) {
      return new IdeaRef(
          idea.id, idea.title, idea.status == null ? null : idea.status.name(), idea.archived);
    }
  }

  @Schema(
      name = "IdeaInspiredWork",
      description =
          "A work item naming the idea as its inspiration. DROPPED items are listed and are not"
              + " counted by the idea's status.")
  public record InspiredWork(
      String id, String qualifiedId, String title, String status, Archetype archetype) {}

  @Schema(
      name = "IdeaDetail",
      description =
          "One idea, with the work it inspired, the ideas it enables, and the idea enabling it.")
  public record IdeaDetail(
      String id,
      String projectId,
      String title,
      @Schema(nullable = true) String description,
      @Schema(nullable = true) String enabledBy,
      @Schema(
              nullable = true,
              description =
                  "The enabling idea in brief, so a client can say when it is archived; null when"
                      + " enabledBy is.")
          IdeaRef enabledByIdea,
      @Schema(enumeration = {"PLANNED", "QUEUED", "DONE"}) String status,
      boolean archived,
      @Schema(nullable = true) String archiveReason,
      @Schema(nullable = true) Instant archivedAt,
      Instant createdAt,
      Instant updatedAt,
      @Schema(description = "Every work item naming this idea as inspiredBy, oldest first.")
          List<InspiredWork> inspired,
      @Schema(description = "The ideas naming this one as their enabledBy, oldest first.")
          List<IdeaRef> enables) {}

  @Schema(name = "IdeaCreate", description = "A new idea.")
  public record IdeaCreate(
      @Schema(description = "The project: its id or its slug. Or name it as projectId.")
          String project,
      @Schema(description = "The project's id, when project is not given.") String projectId,
      @Schema(required = true, description = "One line: no line break, at most one '.', fewer"
              + " than 20 whitespace characters.")
          String title,
      @Schema(description = "At most 3 paragraphs (blocks between blank lines) and 600"
              + " characters.")
          String description,
      @Schema(description = "The one idea of the same project enabling this one; it must not"
              + " itself be enabled by another.")
          String enabledBy) {}

  @Schema(
      name = "IdeaPatch",
      description =
          "A JSON merge patch (RFC 7396) of one idea: title, description, enabledBy. An absent"
              + " property is left alone; null clears description or enabledBy. The status is"
              + " derived and the archive has its own doors.")
  public record IdeaPatch(
      @Schema(description = "Cannot be cleared.") String title,
      @Schema(nullable = true) String description,
      @Schema(nullable = true) String enabledBy) {}

  @Schema(name = "IdeaArchive", description = "Why the idea is archived.")
  public record IdeaArchive(@Schema(required = true) String reason) {}

  // --- reads -----------------------------------------------------------------------------------

  @GET
  @RolesAllowed({"qits:admin", "qits:admin-agent", "qits:desk-admin", "qits:agent", "qits:system"})
  @Operation(
      operationId = "listIdeas",
      summary = "List a project's ideas",
      description =
          "The project's ideas, oldest first. The archived ones are left out unless archived=true,"
              + " which INCLUDES them beside the rest (it does not narrow to them); status narrows"
              + " to one derived status. The project is its id or its slug.")
  @APIResponse(
      responseCode = "200",
      description = "The ideas",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(implementation = IdeaList.class)))
  @APIResponse(responseCode = "400", description = "No project named, or a status naming none")
  @APIResponse(responseCode = "404", description = "No such project")
  public IdeaList list(
      @Parameter(description = "The project: its id or its slug.", required = true)
          @QueryParam("project")
          String project,
      @Parameter(description = "true includes the archived ideas beside the others.")
          @QueryParam("archived")
          boolean archived,
      @Parameter(
              description = "Only the ideas at this status.",
              schema = @Schema(enumeration = {"PLANNED", "QUEUED", "DONE"}))
          @QueryParam("status")
          String status) {
    if (project == null || project.isBlank()) {
      throw new BadRequestException("project is required: its id or its slug");
    }
    IdeaStatus wanted = statusOf(status);
    Project named = ids.resolveProject(project);
    return new IdeaList(ideas.list(named.id, archived, wanted).stream().map(IdeaDto::of).toList());
  }

  @GET
  @Path("/{id}")
  @RolesAllowed({"qits:admin", "qits:admin-agent", "qits:desk-admin", "qits:agent", "qits:system"})
  @Operation(
      operationId = "getIdea",
      summary = "Read one idea",
      description =
          "The idea, the work items naming it as inspiredBy (DROPPED ones included, with their"
              + " status), the ideas it enables, and the idea enabling it in brief.")
  @APIResponse(
      responseCode = "200",
      description = "The idea",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(implementation = IdeaDetail.class)))
  @APIResponse(responseCode = "404", description = "No idea with this id")
  public IdeaDetail get(@PathParam("id") String id) {
    Idea idea = ideas.get(id);
    IdeaRef enabler =
        idea.enabledBy == null ? null : ideas.lookup(idea.enabledBy).map(IdeaRef::of).orElse(null);
    List<WorkEntity> work = ideas.inspired(idea.id);
    String slug = work.isEmpty() ? null : ids.resolveProject(idea.projectId).slug;
    List<InspiredWork> inspired = new ArrayList<>(work.size());
    for (WorkEntity row : work) {
      inspired.add(
          new InspiredWork(
              row.id,
              QualifiedEntityIds.render(slug, row.number),
              row.title,
              row.status,
              row.archetype));
    }
    return new IdeaDetail(
        idea.id,
        idea.projectId,
        idea.title,
        idea.description,
        idea.enabledBy,
        enabler,
        idea.status == null ? null : idea.status.name(),
        idea.archived,
        idea.archiveReason,
        idea.archivedAt,
        idea.createdAt,
        idea.updatedAt,
        List.copyOf(inspired),
        ideas.enables(idea.id).stream().map(IdeaRef::of).toList());
  }

  // --- writes ----------------------------------------------------------------------------------

  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  @Operation(
      operationId = "createIdea",
      summary = "Create an idea",
      description =
          "A new idea of the project (id or slug, as project or projectId), PLANNED. The title is"
              + " one line (no line break, at most one '.', fewer than 20 whitespace characters);"
              + " the description at most 3 paragraphs and 600 characters; enabledBy one idea of the"
              + " same project that is not itself enabled by another.")
  @APIResponse(
      responseCode = "201",
      description = "The idea as written",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(implementation = IdeaDto.class)))
  @APIResponse(
      responseCode = "400",
      description = "No project, a title, description or enabledBy breaking its rule")
  @APIResponse(
      responseCode = "403",
      description = "An agent creating in a project other than its own; nothing is written")
  @APIResponse(responseCode = "404", description = "No such project")
  public Response create(IdeaCreate body) {
    if (body == null) {
      throw new BadRequestException("an idea create must be a JSON object");
    }
    String named = body.project() != null && !body.project().isBlank() ? body.project() : body.projectId();
    if (named == null || named.isBlank()) {
      throw new BadRequestException("project is required: its id or its slug");
    }
    Project project = ids.resolveProject(named);
    EntitiesAgentAccess.requireProject(identity, project.id);
    Idea created =
        ideas.create(
            project.id,
            body.title(),
            body.description(),
            body.enabledBy(),
            EntitiesPrincipal.changedBy(identity));
    return Response.status(Response.Status.CREATED).entity(IdeaDto.of(created)).build();
  }

  @PATCH
  @Path("/{id}")
  @Consumes({WorkEntityDoors.MERGE_PATCH_JSON, MediaType.APPLICATION_JSON})
  @Operation(
      operationId = "patchIdea",
      summary = "Edit an idea (JSON merge patch)",
      description =
          "Partial update of title, description and enabledBy. An absent property is left alone;"
              + " null clears description or enabledBy. The rules are the create's; setting"
              + " enabledBy on an idea that already enables others is refused.")
  @APIResponse(
      responseCode = "200",
      description = "The idea after the edit",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(implementation = IdeaDto.class)))
  @APIResponse(
      responseCode = "400",
      description =
          "Not an object, empty, an unknown or server-owned property, a null title, or a value"
              + " breaking its rule")
  @APIResponse(
      responseCode = "403",
      description = "An agent editing an idea outside its own project; nothing is written")
  @APIResponse(responseCode = "404", description = "No idea with this id")
  public IdeaDto patch(
      @PathParam("id") String id,
      @RequestBody(
              required = true,
              content = {
                @Content(
                    mediaType = WorkEntityDoors.MERGE_PATCH_JSON,
                    schema = @Schema(implementation = IdeaPatch.class)),
                @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = IdeaPatch.class))
              })
          JsonNode body) {
    Idea current = ideas.get(id);
    EntitiesAgentAccess.requireProject(identity, current.projectId);
    IdeaService.IdeaEdit edit = readPatch(body);
    return IdeaDto.of(ideas.update(id, edit, EntitiesPrincipal.changedBy(identity)));
  }

  @POST
  @Path("/{id}/archive")
  @Consumes(MediaType.APPLICATION_JSON)
  @Operation(
      operationId = "archiveIdea",
      summary = "Archive an idea",
      description =
          "Hides the idea from the default listing, with the reason. Allowed at every status, and"
              + " the status does not change. Archiving an archived idea restates the reason.")
  @APIResponse(
      responseCode = "200",
      description = "The idea, archived",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(implementation = IdeaDto.class)))
  @APIResponse(responseCode = "400", description = "No reason")
  @APIResponse(
      responseCode = "403",
      description = "An agent archiving an idea outside its own project; nothing is written")
  @APIResponse(responseCode = "404", description = "No idea with this id")
  public IdeaDto archive(@PathParam("id") String id, IdeaArchive body) {
    Idea current = ideas.get(id);
    EntitiesAgentAccess.requireProject(identity, current.projectId);
    return IdeaDto.of(
        ideas.archive(id, body == null ? null : body.reason(), EntitiesPrincipal.changedBy(identity)));
  }

  @POST
  @Path("/{id}/unarchive")
  @Operation(
      operationId = "unarchiveIdea",
      summary = "Take an idea out of the archive",
      description =
          "Clears archived, archiveReason and archivedAt. An idea that is not archived is answered"
              + " as it is.")
  @APIResponse(
      responseCode = "200",
      description = "The idea, not archived",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(implementation = IdeaDto.class)))
  @APIResponse(
      responseCode = "403",
      description = "An agent unarchiving an idea outside its own project; nothing is written")
  @APIResponse(responseCode = "404", description = "No idea with this id")
  public IdeaDto unarchive(@PathParam("id") String id) {
    Idea current = ideas.get(id);
    EntitiesAgentAccess.requireProject(identity, current.projectId);
    return IdeaDto.of(ideas.unarchive(id, EntitiesPrincipal.changedBy(identity)));
  }

  // --- the bodies ------------------------------------------------------------------------------

  /** The patch as an {@link IdeaService.IdeaEdit}, or one 400 carrying every complaint. */
  static IdeaService.IdeaEdit readPatch(JsonNode body) {
    if (body == null || !body.isObject()) {
      throw new BadRequestException("a merge patch must be a JSON object");
    }
    if (body.isEmpty()) {
      throw new BadRequestException("a merge patch must name at least one property");
    }
    List<String> refused = new ArrayList<>();
    Map<String, JsonNode> values = new HashMap<>();
    for (Iterator<String> names = body.fieldNames(); names.hasNext(); ) {
      String name = names.next();
      JsonNode value = body.get(name);
      if (NOT_PATCHED.containsKey(name)) {
        refused.add(name + " is not written by a patch: it is " + NOT_PATCHED.get(name));
      } else if (!PATCHABLE.contains(name)) {
        refused.add("unknown property: " + name);
      } else if (value.isNull()) {
        if (name.equals("title")) {
          refused.add("title cannot be cleared");
        }
        values.put(name, value);
      } else if (!value.isTextual()) {
        refused.add(name + " must be a string");
      } else {
        values.put(name, value);
      }
    }
    if (!refused.isEmpty()) {
      throw new BadRequestException(String.join("; ", refused));
    }
    return new IdeaService.IdeaEdit(
        text(values.get("title")),
        text(values.get("description")),
        isNull(values.get("description")),
        text(values.get("enabledBy")),
        isNull(values.get("enabledBy")));
  }

  private static String text(JsonNode value) {
    return value == null || value.isNull() ? null : value.textValue();
  }

  private static boolean isNull(JsonNode value) {
    return value != null && value.isNull();
  }

  private static IdeaStatus statusOf(String status) {
    if (status == null || status.isBlank()) {
      return null;
    }
    try {
      return IdeaStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new BadRequestException(
          "Unknown idea status: " + status + " (PLANNED, QUEUED or DONE)");
    }
  }
}
