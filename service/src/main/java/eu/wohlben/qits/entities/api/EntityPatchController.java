package eu.wohlben.qits.entities.api;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.entities.control.ArchetypeViolation;
import eu.wohlben.qits.entities.control.Archetypes;
import eu.wohlben.qits.entities.control.EntityCatalogService;
import eu.wohlben.qits.entities.control.EntityProperty;
import eu.wohlben.qits.entities.control.EntityWrite;
import eu.wohlben.qits.entities.control.Nested;
import eu.wohlben.qits.entities.control.TransitionedEntity;
import eu.wohlben.qits.entities.control.WorkEntityService;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.error.BadRequestException;
import eu.wohlben.qits.entities.error.NotFoundException;
import eu.wohlben.qits.projects.control.RepositoryService;
import eu.wohlben.qits.projects.entity.Repository;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * <b>The field edit of the merged model: {@code PATCH /projects/api/entities/{id}}.</b>
 *
 * <p>{@code PUT /epics/{id}} and {@code PUT /tickets/{id}} were retired with qits-399, which left
 * {@code POST /entities/transition} as the only HTTP door that edits a row — and that one is a PUT
 * in all but verb: an absent property is cleared ({@code EntityTransition}). A caller that wanted to
 * retitle a ticket had to restate everything else about it, and one that forgot the assignee
 * unassigned it. This is the partial edit the MCP tools {@code update_ticket}, {@code update_epic},
 * {@code update_feature} and {@code update_task} already perform, exposed over REST for any
 * archetype: it is {@link WorkEntityService#update} and nothing else, so the freeze, the dependency
 * rules and the registry's slots are the ones every other edit obeys. There is no second update
 * path here, only a translation from the wire.
 *
 * <h2>A JSON merge patch (RFC 7396)</h2>
 *
 * <p>An absent property is left alone and an explicit {@code null} clears it. That distinction is
 * the whole reason the body is read as a {@link JsonNode} rather than bound to a record: a record
 * cannot tell a property that was not sent from one sent as null. It maps onto {@link EntityWrite}
 * directly — a value is a value, a null is that property's {@code clear*} flag — and a property
 * with no clear flag ({@code title}, {@code ticketType}, {@code repositoryId}) is required wherever
 * it is permitted, so clearing it is a 400 rather than a flag that does not exist.
 *
 * <p><b>What this door does not write, and says so.</b> The status, the archetype, the membership
 * and a supersede are moves, not edits, and each has its door: the archetype's {@code
 * …/{id}/transition} for a lifecycle move, {@code POST /entities/transition} for a reshape, a
 * reparent or a supersede, and {@code POST /entities/{id}/blocked} for the block. {@code slug} and
 * {@code createdBy} are the server's. Naming any of those, or a property nobody has heard of, is a
 * 400 rather than a silent drop — a caller that thinks it moved a status and did not is worse off
 * than one that was told. Every such complaint comes back in <b>one</b> 400, joined with {@code "; "}
 * like the transition's.
 *
 * <p><b>The answer is the {@link TransitionedEntity}</b> — the flat, merged shape {@code
 * /entities/transition} and {@code list_entities} answer — built from the row the write handed
 * back ({@link TransitionedEntity#edited}). Not the per-archetype DTOs: those come wrapped in an
 * envelope named for the kind ({@code {"epic": …}}), so a generic door would answer a different
 * shape per row and a caller would have to know the archetype before it could read the result.
 * {@code statusBefore} and {@code changedBy} are null, as on every read.
 *
 * <p><b>The id is the entity's UUID.</b> A qualified id ({@code qits-547}) is not resolved here:
 * {@code /entities/transition} resolves none either, and the one reader of that form, {@code
 * CommitSubjectEntities}, reads it off a commit subject rather than a path.
 *
 * <p>The binding and the hints are {@code EntityTransitionController}'s: {@code qits:agent} is
 * granted because the four MCP update tools already serve this write, bound to the agent's own
 * project before anything is written; and both SSE topics are fired after the write returns, never
 * inside its retried body. No bus event, the same as every other field edit.
 */
@Path("/entities")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("qits:admin")
public class EntityPatchController {

  /** RFC 7396's media type. Plain {@code application/json} is accepted beside it. */
  public static final String MERGE_PATCH_JSON = "application/merge-patch+json";

  /**
   * The properties this door refuses, each with the door to use instead. The status's is blank
   * here because it depends on the archetype — see {@link #statusDoor}.
   */
  private static final Map<String, String> MOVES =
      Map.of(
          "status", "",
          "archetype", "a reshape — state it through POST /projects/api/entities/transition",
          "membership", "a reparent — state it through POST /projects/api/entities/transition",
          "supersededBy", "a supersede — state it through POST /projects/api/entities/transition",
          "blocked", "the block — set it through POST /projects/api/entities/{id}/blocked");

  /** What the server writes and a caller never does. */
  private static final List<String> SERVER_OWNED = List.of("slug", "createdBy");

  /**
   * The editable properties, as {@code EntityTransition} spells them, and their registry slot — read
   * off {@link EntityWireProperties}, the table the published update schema is built from, so the
   * schema a client is handed and the names this door accepts are one list (qits-548).
   */
  private static final Map<String, EntityProperty> EDITABLE = EntityWireProperties.editable();

  /**
   * Editable, but with no clear flag behind them: every kind that permits one requires it. The
   * table's {@code clearable} column, which is also what types these as non-nullable in the schema.
   */
  private static final List<String> NOT_CLEARABLE = EntityWireProperties.notClearable();

  @Inject WorkEntityService entities;

  /** The id's archetype, project and edge, read before the write. */
  @Inject EntityCatalogService catalog;

  /** A task's repository must be in the task's project — the check {@code EntityRoutes} makes. */
  @Inject RepositoryService repositories;

  @Inject SecurityIdentity identity;

  @Inject EpicsTopicHints epicHints;

  @Inject TicketsTopicHints ticketHints;

  @Inject eu.wohlben.qits.projects.api.QualifiedEntityIds qualifiedIds;

  /**
   * The documentation of the body, and only that: the body itself is read as a {@link JsonNode},
   * because this record could not tell an absent property from a null one.
   */
  @Schema(
      name = "EntityPatch",
      description =
          "A JSON merge patch (RFC 7396) of one entity. An absent property is left as it is; an"
              + " explicit null clears it. At least one property must be named. status, archetype,"
              + " membership, supersededBy and blocked are refused with the door that moves them;"
              + " slug and createdBy are server-owned; any other property is refused as unknown. A"
              + " property the entity's archetype has no slot for is refused (impetus on an epic).")
  public record EntityPatch(
      @Schema(description = "The label. Cannot be cleared.") String title,
      @Schema(nullable = true, description = "The long-form Markdown body; null clears it.")
          String description,
      @Schema(nullable = true, description = "A ticket's impetus; null clears it.") String impetus,
      @Schema(
              description =
                  "A ticket's type, BUG or IMPROVEMENT; MAINTENANCE marks a ticket the platform"
                      + " filed and will close itself, and retyping one away from it takes it over."
                      + " Cannot be cleared.")
          String ticketType,
      @Schema(nullable = true, description = "A ticket's assignee; null clears it.")
          String assignee,
      @Schema(description = "A task's repository, in the task's project. Cannot be cleared.")
          String repositoryId,
      @Schema(
              nullable = true,
              description = "A feature's or task's sibling dependency; null clears it.")
          String dependsOn,
      @Schema(
              nullable = true,
              description =
                  "A feature's or task's implemented marker (ISO-8601 instant); null clears it."
                      + " Moves only while the owning epic is REFINED.")
          Instant implementedAt) {}

  /**
   * Applies the patch and answers the entity as it now stands.
   *
   * <p>The order is the refusal order: the id first (404), then the binding (403), then the body
   * (400), then the write — whose own refusals, the scope freeze's 409 above all, are {@link
   * WorkEntityService#update}'s. So an agent reaching into another project is told 403 whatever it
   * sent, and nothing is written for it.
   */
  @PATCH
  @Path("/{id}")
  @Consumes({MERGE_PATCH_JSON, MediaType.APPLICATION_JSON})
  @RolesAllowed({"qits:admin", "qits:agent", "qits:system"})
  @Operation(
      summary = "Edit an entity's fields (JSON merge patch)",
      description =
          "Partial update of one entity of any archetype. An absent property is left unchanged and"
              + " an explicit null clears it. The status is never written here — a lifecycle move"
              + " goes through the archetype's /{id}/transition, and a reshape, reparent or"
              + " supersede through POST /projects/api/entities/transition. An epic's, feature's or"
              + " task's scope follows the epic's freeze: scope edits need the epic REPORTED, the"
              + " implemented marker needs it REFINED. Answers the entity in the merged shape.")
  @APIResponse(
      responseCode = "200",
      description = "The entity as it stands after the edit",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(implementation = TransitionedEntity.class)))
  @APIResponse(
      responseCode = "400",
      description =
          "Every complaint about the body in one message: not an object, empty, a property that is"
              + " a move or server-owned or unknown, one the archetype has no slot for, a null"
              + " where the property cannot be cleared, or a value of the wrong shape")
  @APIResponse(
      responseCode = "403",
      description = "An agent writing an entity outside its own project; nothing is written")
  @APIResponse(responseCode = "404", description = "No entity with this id")
  @APIResponse(
      responseCode = "409",
      description = "The owning epic's status freezes what the patch touches")
  public TransitionedEntity patch(
      @PathParam("id") String id,
      @RequestBody(
              required = true,
              content = {
                @Content(
                    mediaType = MERGE_PATCH_JSON,
                    schema = @Schema(implementation = EntityPatch.class)),
                @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = EntityPatch.class))
              })
          JsonNode body) {
    TransitionedEntity current = catalog.byIds(List.of(id)).get(id);
    if (current == null) {
      throw new NotFoundException("Entity not found: " + id);
    }
    EntitiesAgentAccess.requireProject(identity, current.projectId());

    EntityWrite write = read(current, body);
    if (write.repositoryId() != null) {
      Repository repo = repositories.get(write.repositoryId()); // 404 if absent
      if (repo.project == null || !current.projectId().equals(repo.project.id)) {
        throw new BadRequestException(
            "Repository " + write.repositoryId() + " is not in this entity's project");
      }
    }
    Nested updated =
        entities.update(current.archetype(), id, write, EntitiesPrincipal.changedBy(identity));

    // After the write and outside it, as the transition door fires them: its body is retried.
    epicHints.fire(current.projectId());
    ticketHints.fire(current.projectId());
    // The row the write handed back, never a re-read — see TransitionedEntity.edited for why a
    // re-read in this request answers the row as it was.
    return qualifiedIds.qualify(TransitionedEntity.edited(updated.entity(), current));
  }

  /**
   * The body as an {@link EntityWrite} for {@code current}'s archetype, or one 400 carrying every
   * complaint about it.
   */
  private static EntityWrite read(TransitionedEntity current, JsonNode body) {
    if (body == null || !body.isObject()) {
      throw new BadRequestException("a merge patch must be a JSON object");
    }
    if (body.isEmpty()) {
      throw new BadRequestException("a merge patch must name at least one property");
    }
    Archetype archetype = current.archetype();
    List<String> refused = new ArrayList<>();
    for (Iterator<String> names = body.fieldNames(); names.hasNext(); ) {
      String name = names.next();
      JsonNode value = body.get(name);
      if (MOVES.containsKey(name)) {
        String door = name.equals("status") ? statusDoor(archetype) : MOVES.get(name);
        refused.add(name + " is not written by a patch: it is " + door);
      } else if (SERVER_OWNED.contains(name)) {
        refused.add(name + " is server-owned and never written");
      } else if (!EDITABLE.containsKey(name)) {
        refused.add("unknown property: " + name);
      } else if (!Archetypes.spec(archetype).permits(EDITABLE.get(name))) {
        refused.add(
            new ArchetypeViolation(
                    archetype, EDITABLE.get(name), ArchetypeViolation.Reason.NOT_PERMITTED, null)
                .message());
      } else if (value.isNull()) {
        if (NOT_CLEARABLE.contains(name)) {
          refused.add(name + " cannot be cleared");
        }
      } else if (!value.isTextual()) {
        refused.add(name + " must be a string");
      } else if (name.equals("implementedAt")) {
        try {
          Instant.parse(value.textValue());
        } catch (DateTimeParseException e) {
          refused.add("implementedAt must be an ISO-8601 instant: " + value.textValue());
        }
      }
    }
    if (!refused.isEmpty()) {
      throw new BadRequestException(String.join("; ", refused));
    }

    String implementedAt = text(body, "implementedAt");
    return new EntityWrite(
        text(body, "title"),
        text(body, "description"),
        cleared(body, "description"),
        text(body, "impetus"),
        cleared(body, "impetus"),
        text(body, "ticketType"),
        text(body, "assignee"),
        cleared(body, "assignee"),
        text(body, "repositoryId"),
        text(body, "dependsOn"),
        cleared(body, "dependsOn"),
        implementedAt == null ? null : Instant.parse(implementedAt),
        cleared(body, "implementedAt"));
  }

  /** The lifecycle door of the archetype, or the registry's word for a kind with no status. */
  private static String statusDoor(Archetype archetype) {
    return switch (archetype) {
      case EPIC -> "a lifecycle move — POST /projects/api/epics/{id}/transition";
      case TICKET -> "a lifecycle move — POST /projects/api/tickets/{id}/transition";
      case CAMPAIGN -> "a lifecycle move — POST /projects/api/campaigns/{id}/transition";
      default -> "a lifecycle move, and a " + archetype + " has no status";
    };
  }

  /** The property's value when it was sent as text; null when absent or sent as null. */
  private static String text(JsonNode body, String name) {
    JsonNode value = body.get(name);
    return value == null || value.isNull() ? null : value.textValue();
  }

  /** Whether the property was sent, and sent as null — RFC 7396's "remove". */
  private static boolean cleared(JsonNode body, String name) {
    JsonNode value = body.get(name);
    return value != null && value.isNull();
  }
}
