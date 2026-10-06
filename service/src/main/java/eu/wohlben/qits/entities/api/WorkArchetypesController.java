package eu.wohlben.qits.entities.api;

import eu.wohlben.qits.entities.control.ArchetypeRegistryDocument;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.Map;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * <b>The archetype registry on the work family: {@code GET /projects/api/work/archetypes}</b> and
 * the JSON Schema of each write door's payload per archetype (qits-969). The same document {@code
 * /entities/archetypes} serves — {@link WorkEntityDoors#registry} and {@link
 * WorkEntityDoors#schema} are the one implementation — under the family's address.
 *
 * <p>A read, so the whole class is {@code qits:admin} and {@code qits:agent}: the answer holds no
 * row, no project and no identity, and an agent assembling a write is the caller it is for.
 */
@Path("/work/archetypes")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"qits:admin", "qits:agent"})
public class WorkArchetypesController {

  @Inject WorkEntityDoors doors;

  @GET
  @Operation(
      operationId = "listWorkArchetypes",
      summary = "The archetype registry: properties, lifecycles, legal moves and dispatch phases",
      description =
          "Every archetype's declaration, read off the registry and the state machine the doors"
              + " enforce: what it requires and permits, its lifecycle, the legal moves out of each"
              + " status (transitions, each naming the quality gates it has to pass) and what a"
              + " dispatch press runs from each status (phases).")
  public ArchetypeRegistryDocument archetypes() {
    return doors.registry();
  }

  @GET
  @Path("/{archetype}/schemas/{door}")
  @Operation(
      operationId = "getWorkArchetypeSchema",
      summary = "The JSON Schema of a work write door's payload, per archetype",
      description =
          "A JSON Schema (2020-12) object for the body of one write door and one archetype: create"
              + " (POST /projects/api/work, less the archetype), update (the merge patch of PATCH"
              + " /projects/api/work/{qualifiedId}) or transition (the body of PUT"
              + " /projects/api/work/{qualifiedId}, one entry of POST /projects/api/work/transition)."
              + " Built from the archetype registry and the same property table the doors validate"
              + " with. The archetype is case-insensitive.")
  @APIResponse(
      responseCode = "200",
      description = "The schema",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON,
              schema = @Schema(type = SchemaType.OBJECT)))
  @APIResponse(responseCode = "404", description = "No such archetype, or no such door")
  public Map<String, Object> schema(
      @PathParam("archetype") String archetype, @PathParam("door") String door) {
    return doors.schema(archetype, door);
  }
}
