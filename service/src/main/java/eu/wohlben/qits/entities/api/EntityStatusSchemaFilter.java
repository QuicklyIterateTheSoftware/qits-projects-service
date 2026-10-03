package eu.wohlben.qits.entities.api;

import eu.wohlben.qits.entities.control.EntityStateMachine;
import eu.wohlben.qits.entities.entity.EntityStatus;
import io.quarkus.smallrye.openapi.OpenApiFilter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.openapi.OASFilter;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.media.Schema;

/**
 * <b>The lifecycle's words as an enum on {@code EpicDto.status}, {@code TicketDto.status}, and
 * since qits-763 {@code FeatureDto.status} and {@code TaskDto.status}</b> (qits-749), so a client generated from {@code docs/openapi.yml} knows every status, IMPLEMENTING
 * included, instead of reading a bare {@code type: string}.
 *
 * <p>A filter rather than {@code @Schema(enumeration = …)} on the DTO components, for two reasons.
 * An annotation takes only a constant array, so the words would be a hand copy of {@link
 * EntityStatus} — the drift every list here is read off the state machine to avoid. And the DTOs
 * live in the {@code entities} module, which depends on no OpenAPI API at all and should stay that
 * way. The words are {@link EntityStateMachine#states()}, in lifecycle order, at build time.
 */
@OpenApiFilter(OpenApiFilter.RunStage.BUILD)
public class EntityStatusSchemaFilter implements OASFilter {

  /** The component schemas whose {@code status} is the one lifecycle's word. */
  static final List<String> SCHEMAS = List.of("EpicDto", "TicketDto", "FeatureDto", "TaskDto");

  @Override
  public void filterOpenAPI(OpenAPI openAPI) {
    if (openAPI.getComponents() == null || openAPI.getComponents().getSchemas() == null) {
      return;
    }
    Map<String, Schema> schemas = openAPI.getComponents().getSchemas();
    for (String name : SCHEMAS) {
      Schema dto = schemas.get(name);
      if (dto == null || dto.getProperties() == null) {
        continue;
      }
      Schema status = dto.getProperties().get("status");
      if (status != null) {
        status.setEnumeration(
            new ArrayList<>(EntityStateMachine.states().stream().map(Enum::name).toList()));
      }
    }
  }
}
