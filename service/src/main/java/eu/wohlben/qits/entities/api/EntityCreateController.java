package eu.wohlben.qits.entities.api;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.entities.control.TransitionedEntity;
import eu.wohlben.qits.entities.control.WorkEntityService;
import eu.wohlben.qits.entities.entity.Archetype;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
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
 * <p>The body becomes an {@code EntityWrite} and goes to {@link WorkEntityService#create} with the
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
 *       parent (and a dependency) by its UUID or its qualified id, through {@code EntityIdResolver}.
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
 *
 * <p>Since qits-969 the whole of the door's logic is {@link WorkEntityDoors#create}, shared with
 * {@code POST /work}; this class is the {@code /entities} address and nothing else.
 */
@Path("/entities")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("qits:admin")
public class EntityCreateController {

  /** The create itself, shared with {@code POST /work}. */
  @Inject WorkEntityDoors doors;

  @Inject SecurityIdentity identity;

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
    return Response.status(Response.Status.CREATED).entity(doors.create(identity, body)).build();
  }
}
