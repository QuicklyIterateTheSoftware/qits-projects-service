package eu.wohlben.qits.projects.api;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.projects.control.TagCollector;
import eu.wohlben.qits.projects.control.TagKeepRule;
import eu.wohlben.qits.projects.dto.TagCollectionReportDto;
import eu.wohlben.qits.projects.error.BadRequestException;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.Set;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * The orchestrator's door into tag cleanup: {@code POST /projects/api/gc/tags} decommissions old
 * release tags on the git host and on every backup twin.
 *
 * <p><b>The pins are an input, not a lookup</b> — the pattern qits-configuration's {@code POST
 * /configuration/api/gc/entries} set (qits-593): qits-platform-orchestrator holds a credential for
 * every peer, reads the six pin sources and embeds each answer verbatim under {@code pins}. Their
 * schemas are not modelled here. Every string anywhere in them that is a release version is a pin
 * ({@link TagKeepRule#pinnedVersions}), which over-keeps a version that appears in a field nobody
 * meant as one, and that is the safe direction.
 *
 * <p><b>Fail closed on the document's shape, though.</b> A missing source is not "that source pins
 * nothing", and reading it that way would delete every release it pins. So {@code pins} and every one
 * of its six members must be present and be an object or an array, or the answer is 400 and nothing
 * is judged.
 *
 * <p>What may be deleted is decided in {@link TagKeepRule} and done by {@link TagCollector}; a dry
 * run judges exactly as a real one does and deletes nothing. A run is long — a mirror fetch per
 * repository and pushes to a forge — and the caller is expected to wait for it.
 *
 * <p>{@code qits:system} beside {@code qits:admin}: the scheduled caller is a machine, and a person
 * may run the same collection by hand. Nothing needs registering for reflection: the method returns
 * its record type, so the native build indexes it, and the request's pins are a {@link JsonNode}.
 */
@Path("/gc")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class GcController {

  /** The six pin sources, by the member name the caller embeds each answer under. */
  static final List<String> PIN_SOURCES =
      List.of(
          "deployments",
          "ciDaemon",
          "dependencies",
          "configuredImages",
          "workspaceLaunches",
          "projectLaunches");

  @Inject TagCollector collector;

  /**
   * The request; unknown fields are tolerated, which is Quarkus' Jackson default.
   *
   * @param dryRun judge and report, delete nothing
   * @param pins the six pin answers, verbatim: {@code deployments} ({@code GET
   *     /deployments/api/pins}), {@code ciDaemon} ({@code GET /ci/api/daemon}), {@code dependencies}
   *     ({@code GET /maintenance/api/pins}), {@code configuredImages} ({@code GET
   *     /configuration/api/pins}), {@code workspaceLaunches} ({@code GET /workspaces/api/pins}) and
   *     {@code projectLaunches} ({@code GET /projects/api/pins})
   */
  public record CollectTagsRequest(
      boolean dryRun, @Schema(implementation = PinSources.class, required = true) JsonNode pins) {}

  /**
   * What {@code pins} carries, for the API document only: read as a {@link JsonNode}, never bound,
   * because no source's schema is modelled here. Each member is that source's answer as it was read.
   */
  @Schema(name = "TagCollectionPinSources")
  public record PinSources(
      @Schema(required = true, description = "GET /deployments/api/pins") Object deployments,
      @Schema(required = true, description = "GET /ci/api/daemon") Object ciDaemon,
      @Schema(required = true, description = "GET /maintenance/api/pins") Object dependencies,
      @Schema(required = true, description = "GET /configuration/api/pins") Object configuredImages,
      @Schema(required = true, description = "GET /workspaces/api/pins") Object workspaceLaunches,
      @Schema(required = true, description = "GET /projects/api/pins") Object projectLaunches) {}

  /** Judge every catalogued repository's release tags and delete the old ones, or say which. */
  @POST
  @Path("/tags")
  @Operation(summary = "Decommission release tags no pin, gitlink or release in flight keeps")
  @APIResponse(responseCode = "200", description = "What was judged, deleted, kept and failed")
  @APIResponse(responseCode = "400", description = "A pin source is missing or malformed")
  @RolesAllowed({"qits:admin", "qits:admin-agent", "qits:system"})
  public TagCollectionReportDto collectTags(CollectTagsRequest request) {
    return collector.collect(pinnedVersions(request), request.dryRun());
  }

  /** Every version the six sources name; refused whole if any source is absent or not a document. */
  static Set<String> pinnedVersions(CollectTagsRequest request) {
    if (request == null || request.pins() == null || !request.pins().isObject()) {
      throw new BadRequestException(
          "pins is required: an object embedding the six pin sources " + PIN_SOURCES);
    }
    for (String source : PIN_SOURCES) {
      JsonNode answer = request.pins().get(source);
      if (answer == null || !(answer.isObject() || answer.isArray())) {
        throw new BadRequestException(
            "pins."
                + source
                + " is required and must be the source's answer, an object or an array;"
                + " nothing was judged");
      }
    }
    return TagKeepRule.pinnedVersions(request.pins());
  }
}
