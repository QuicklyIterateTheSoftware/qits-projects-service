package eu.wohlben.qits.entities.api;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.entities.control.Archetypes;
import eu.wohlben.qits.entities.control.EntityCatalogService;
import eu.wohlben.qits.entities.control.EntityWrite;
import eu.wohlben.qits.entities.control.Nested;
import eu.wohlben.qits.entities.control.TransitionedEntity;
import eu.wohlben.qits.entities.control.WorkEntityService;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.WorkEntity;
import eu.wohlben.qits.entities.error.BadRequestException;
import eu.wohlben.qits.entities.error.NotFoundException;
import eu.wohlben.qits.projects.api.ProjectChangeHint;
import eu.wohlben.qits.projects.api.ProjectChangePublisher;
import eu.wohlben.qits.projects.api.QualifiedEntityIds;
import eu.wohlben.qits.projects.control.RepositoryService;
import eu.wohlben.qits.projects.entitieshost.EntityIdResolver;
import eu.wohlben.qits.projects.entity.Repository;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * <b>The create of the merged model: {@code POST /projects/api/entities}</b> (qits-548) — a new row
 * of any archetype, from one body shape.
 *
 * <p>There were five create doors and they disagree on the wire: {@code POST /projects/{p}/tickets}
 * calls the type {@code type}, a feature's dependency is {@code dependsOnFeatureId}, a task's {@code
 * dependsOnTaskId}, and each lives under a different path. A client that files work of every kind had
 * to carry a route table and a field map per archetype, which is the copy that let {@code qits ticket
 * new} go on sending a body with no impetus to a door that required one. This door takes the create
 * schema {@link EntitySchemas} publishes — the wire names of {@link EntityWireProperties} — plus
 * {@code archetype}. The five doors stay: the released SPA files through them.
 *
 * <h2>No second write path</h2>
 *
 * <p>The body becomes an {@link EntityWrite} and goes to {@link WorkEntityService#create} with the
 * one argument that says where a row goes — the project's id for a root, the parent's for a node —
 * exactly as {@code EntityRoutes.createRoot} and {@code createChild} hand it over. The registry's
 * intake rules, the slug, the number, the REPORTED status and the audit row are that method's. What
 * is decided here is only what the wire needs deciding:
 *
 * <ul>
 *   <li><b>The body is validated against the schema this service serves</b> ({@link
 *       EntitySchemas#createRefusals}) — an unknown or unslotted property, a missing required one, a
 *       value of the wrong shape — every complaint in one 400, joined with {@code "; "}.
 *   <li><b>A parent must be of the kind the row hangs under</b>: a feature under an EPIC, a task
 *       under a FEATURE ({@link WorkEntityService#parentKindOf}). The service would look a task's id
 *       up as an epic and answer "Epic not found"; a caller that named a real row of the wrong kind
 *       is told that instead, as a 400.
 *   <li><b>A task's repository must be in the task's project</b> — the check {@code
 *       EntityRoutes.createChild} makes, for its reason: it crosses into {@code domain}.
 *   <li><b>{@code project} and {@code parent} are typed ids</b>: a project by its id or its slug, a
 *       parent (and a dependency) by its UUID or its qualified id, through {@link EntityIdResolver}.
 * </ul>
 *
 * <p><b>The order is the other entity doors' — 404, 403, 400, 409.</b> The placement is resolved
 * first (a project or a parent naming nothing is a 404), then an agent is bound to the project it
 * names ({@link EntitiesAgentAccess}; every create is a tool the {@code repository} MCP server already
 * serves an agent), then the body, then the write — whose own refusals, the epic's scope freeze
 * above all, are {@link WorkEntityService#create}'s. The hint of the archetype fires after the write,
 * never inside its retried body.
 *
 * <p><b>The answer is 201 and the {@link TransitionedEntity}</b>, qualified — the merged shape {@code
 * PATCH /entities/{id}} answers, so a generic caller reads every archetype's answer the same way.
 */
@Path("/entities")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("qits:admin")
public class EntityCreateController {

  @Inject WorkEntityService entities;

  /** The answer's read: the row just written, with its edge. */
  @Inject EntityCatalogService catalog;

  /** A project by id or slug, a parent and a dependency by UUID or qualified id. */
  @Inject EntityIdResolver ids;

  @Inject RepositoryService repositories;

  @Inject SecurityIdentity identity;

  @Inject ProjectChangePublisher publisher;

  @Inject QualifiedEntityIds qualifiedIds;

  /**
   * The documentation of the body, and only that: it is read as a {@link JsonNode} so that it can
   * be judged against the archetype's schema, property by property, rather than bound to a record
   * that would drop what it does not know.
   */
  @Schema(
      name = "EntityCreate",
      description =
          "A new entity of any archetype: the archetype, plus exactly the body GET"
              + " /projects/api/entities/archetypes/{archetype}/schemas/create describes for it. A"
              + " root (EPIC, TICKET, CAMPAIGN) names its project, a node (FEATURE, TASK) its parent.")
  public record EntityCreate(
      @Schema(required = true, description = "EPIC, TICKET, FEATURE, TASK or CAMPAIGN")
          Archetype archetype,
      @Schema(description = "A root's project: its id or its slug.") String project,
      @Schema(
              description =
                  "A node's parent — an EPIC for a FEATURE, a FEATURE for a TASK — by UUID or"
                      + " qualified id.")
          String parent,
      @Schema(description = "The label.") String title,
      @Schema(description = "The long-form Markdown body.") String description,
      @Schema(
              description =
                  "A ticket's kind, BUG or IMPROVEMENT. MAINTENANCE is reserved for the tickets the"
                      + " platform files about its own stuck release requests, and it closes those"
                      + " itself.")
          String ticketType,
      @Schema(description = "Why a ticket came about. Required for a ticket.") String impetus,
      @Schema(description = "An epic's or a ticket's assignee.") String assignee,
      @Schema(description = "A task's repository, in the task's project.") String repositoryId,
      @Schema(description = "A feature's or task's sibling dependency.") String dependsOn) {}

  @POST
  @RolesAllowed({"qits:admin", "qits:agent", "qits:system"})
  @Operation(
      summary = "Create an entity of any archetype",
      description =
          "Files a new epic, ticket, campaign, feature or task from one body shape: the archetype"
              + " plus the archetype's create schema (GET"
              + " /projects/api/entities/archetypes/{archetype}/schemas/create). A root names its"
              + " project (id or slug), a node its parent (UUID or qualified id): a feature's parent"
              + " is an EPIC, a task's a FEATURE. The status starts REPORTED. Answers the entity in"
              + " the merged shape, with its qualified id.")
  @APIResponse(
      responseCode = "201",
      description = "The entity as written",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(implementation = TransitionedEntity.class)))
  @APIResponse(
      responseCode = "400",
      description =
          "Every complaint about the body in one message: not an object, no or an unknown"
              + " archetype, a property the create schema does not list, a missing required one, a"
              + " value of the wrong shape, a parent of the wrong kind, or a repository outside the"
              + " project")
  @APIResponse(
      responseCode = "403",
      description = "An agent creating in a project other than its own; nothing is written")
  @APIResponse(
      responseCode = "404",
      description = "The project, the parent or the repository names nothing")
  @APIResponse(
      responseCode = "409",
      description = "The owning epic's status freezes its scope: a node needs the epic REPORTED")
  public Response create(
      @RequestBody(
              required = true,
              content =
                  @Content(
                      mediaType = MediaType.APPLICATION_JSON,
                      schema = @Schema(implementation = EntityCreate.class)))
          JsonNode body) {
    if (body == null || !body.isObject()) {
      throw new BadRequestException("a create must be a JSON object");
    }
    Archetype archetype = archetypeOf(body.get("archetype"));

    // The placement, resolved before anything is judged: its 404, then the binding's 403.
    String projectId = null;
    String under = null;
    WorkEntity parent = null;
    if (Archetypes.mayBeRoot(archetype)) {
      String project = text(body, EntitySchemas.PROJECT);
      if (project != null && !project.isBlank()) {
        projectId = ids.resolveProject(project).id;
        under = projectId;
      }
    } else {
      String named = text(body, EntitySchemas.PARENT);
      if (named != null && !named.isBlank()) {
        parent = ids.resolve(named);
        projectId = parent.projectId;
        under = parent.id;
      }
    }
    if (projectId != null) {
      EntitiesAgentAccess.requireProject(identity, projectId);
    }

    List<String> refused = new ArrayList<>(EntitySchemas.createRefusals(archetype, body));
    Archetype parentKind = WorkEntityService.parentKindOf(archetype);
    if (parent != null && parent.archetype != parentKind) {
      refused.add(
          "a "
              + archetype
              + "'s parent must be "
              + (parentKind == Archetype.EPIC ? "an " : "a ")
              + parentKind
              + ": "
              + text(body, EntitySchemas.PARENT)
              + " is a "
              + parent.archetype);
    }
    if (!refused.isEmpty()) {
      throw new BadRequestException(String.join("; ", refused));
    }

    EntityWrite write =
        new EntityWrite(
            text(body, "title"),
            text(body, "description"),
            false,
            text(body, "impetus"),
            false,
            text(body, "ticketType"),
            text(body, "assignee"),
            false,
            text(body, "repositoryId"),
            dependency(text(body, "dependsOn")),
            false,
            null,
            false,
            null,
            EntitySchemas.strings(body.get("acceptanceCriteria")).orElse(null));
    if (write.repositoryId() != null) {
      Repository repo = repositories.get(write.repositoryId()); // 404 if absent
      if (repo.project == null || !projectId.equals(repo.project.id)) {
        throw new BadRequestException(
            "Repository " + write.repositoryId() + " is not in this entity's project");
      }
    }

    Nested created = entities.create(archetype, under, write, EntitiesPrincipal.changedBy(identity));
    publisher.fire(projectId, ProjectChangeHint.Topic.of(archetype));
    String id = created.entity().id;
    TransitionedEntity answer = qualifiedIds.qualify(catalog.byIds(List.of(id)).get(id));
    return Response.status(Response.Status.CREATED).entity(answer).build();
  }

  /** The body's archetype, or a 400 naming what is wrong with it. */
  private static Archetype archetypeOf(JsonNode value) {
    if (value == null || value.isNull() || !value.isTextual() || value.textValue().isBlank()) {
      throw new BadRequestException(
          "archetype is required: EPIC, TICKET, FEATURE, TASK or CAMPAIGN");
    }
    try {
      return Archetype.valueOf(value.textValue().trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new BadRequestException("Unknown archetype: " + value.textValue());
    }
  }

  /**
   * A dependency named either way, as the UUID the writer compares: a qualified id resolved, and a
   * value naming nothing handed on unchanged, for the writer's own 400 about a dependency that is
   * not a sibling — the refusal the per-archetype doors give it.
   */
  private String dependency(String named) {
    if (named == null) {
      return null;
    }
    try {
      return ids.resolve(named).id;
    } catch (NotFoundException unknown) {
      return named;
    }
  }

  /** The property's value when it was sent as text; null when absent, null or not text. */
  private static String text(JsonNode body, String name) {
    JsonNode value = body.get(name);
    return value == null || !value.isTextual() ? null : value.textValue();
  }
}
