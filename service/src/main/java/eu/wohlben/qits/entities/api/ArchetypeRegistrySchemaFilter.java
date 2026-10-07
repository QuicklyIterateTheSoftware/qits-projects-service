package eu.wohlben.qits.entities.api;

import io.quarkus.smallrye.openapi.OpenApiFilter;
import java.util.Map;
import org.eclipse.microprofile.openapi.OASFilter;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.media.Schema;

/**
 * <b>Descriptions for the archetype registry's lifecycle fields</b> in {@code docs/openapi.yml}:
 * {@code DeclaredArchetype.transitions}, {@code lifecycle} and {@code phases}, and the two records
 * {@code phases} is made of. A filter rather than {@code @Schema} on the records, because they live in the
 * {@code entities} module, which depends on no OpenAPI API.
 */
@OpenApiFilter(OpenApiFilter.RunStage.BUILD)
public class ArchetypeRegistrySchemaFilter implements OASFilter {

  static final Map<String, Map<String, String>> DESCRIPTIONS =
      Map.of(
          "DeclaredArchetype",
          Map.of(
              "transitions",
              "The legal moves out of each status, keyed by every status in lifecycle order:"
                  + " FORWARD first, then SKIP, BACK, DROP/REOPEN. DONE maps to an empty list. Empty"
                  + " for a kind with no lifecycle. The status door (setWorkStatus) allows exactly"
                  + " these.",
              "lifecycle",
              "The statuses in lifecycle order. Empty for a kind with no lifecycle.",
              "phases",
              "What a dispatch press (dispatchWork) runs from each status, keyed by every status"
                  + " in lifecycle order. Empty for a kind a dispatch runs no phases on: a feature, a"
                  + " task and a campaign (whose press is its start)."),
          "LegalMove",
          Map.of(
              "gates",
              "The quality gates the move has to pass, by name (ACCEPTANCE_CRITERIA: the entity"
                  + " carries acceptance criteria; PERSON_APPROVAL: a person makes the move — a"
                  + " browser session or a person's qits CLI, never an agent, a service client or"
                  + " asserted headers). Only a FORWARD or SKIP move has any, and the key is absent"
                  + " when there are none. A move that fails one is a 409 naming every failing"
                  + " gate."),
          "DispatchPhases",
          Map.of(
              "next",
              "The one phase a PHASE press runs, which is also the first phase of a FLOW press."
                  + " Null where a press starts nothing (VERIFIED, DONE, DROPPED).",
              "flow",
              "The phases a FLOW press runs, in order, until a status starts no phase. Empty where a"
                  + " press starts nothing. A block stops a flow early."),
          "DispatchPhase",
          Map.of(
              "phase",
              "The phase's word: refine, implement or verify — the word dispatchWork answers in"
                  + " phase.",
              "from",
              "The status the phase runs from.",
              "enters",
              "The status the platform moves the entity into when the phase starts (READY_FOR_DEV"
                  + " to IMPLEMENTING, IMPLEMENTED to VERIFYING). Null where it moves nothing.",
              "endsIn",
              "The status the phase's agent moves the entity to when the phase is done."));

  @Override
  public void filterOpenAPI(OpenAPI openAPI) {
    if (openAPI.getComponents() == null || openAPI.getComponents().getSchemas() == null) {
      return;
    }
    Map<String, Schema> schemas = openAPI.getComponents().getSchemas();
    DESCRIPTIONS.forEach(
        (name, fields) -> {
          Schema schema = schemas.get(name);
          if (schema == null || schema.getProperties() == null) {
            return;
          }
          fields.forEach(
              (field, description) -> {
                Schema property = schema.getProperties().get(field);
                if (property != null) {
                  property.setDescription(description);
                }
              });
        });
  }
}
