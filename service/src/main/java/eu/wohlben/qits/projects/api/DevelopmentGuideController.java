package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.entities.api.EntitiesPrincipal;
import eu.wohlben.qits.projects.control.DevelopmentGuideDefaults;
import eu.wohlben.qits.projects.control.DevelopmentGuideService;
import eu.wohlben.qits.projects.error.BadRequestException;
import eu.wohlben.qits.projects.security.AgentAccess;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * The development guide (qits-1679): how to develop on the platform, as markdown, for the variant
 * the caller names or, with none named, the one its credential is. {@code qits help guide} reads
 * it.
 *
 * <p>A plain read for every caller that develops: a person, a workspace agent, the delivery desk and
 * a refinement room. It reads the store and nothing else, so it binds no caller to a project.
 *
 * <p><b>Detection</b>, when {@code for} is absent: the delivery desk's token carries {@code
 * qits:desk}; a refinement room's carries an {@code entity} claim; any other agent's carries {@code
 * qits:agent} or {@code qits:admin-agent}; everyone else is a person on a workstation. The answer
 * says which variant it chose.
 */
@Path("/guide")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({
  "qits:admin",
  "qits:admin-agent",
  "qits:desk-admin",
  "qits:desk",
  "qits:agent"
})
public class DevelopmentGuideController {

  @Inject DevelopmentGuideService guide;

  @Inject SecurityIdentity identity;

  /** One variant of the guide. */
  public record GuideResponse(
      @Schema(description = "workstation, agent, desk or refinement") String variant,
      @Schema(description = "True when the variant was chosen from the caller's credential.")
          boolean detected,
      @Schema(description = "The guide, as markdown.") String markdown,
      @Schema(
              description =
                  "The guide-hash: the first 16 lowercase hex characters of the SHA-256 of"
                      + " `markdown`'s UTF-8 bytes. A session re-reads the guide only when this"
                      + " differs from the hash it last read.")
          String hash) {

    /** The response for {@code markdown}, carrying its hash. */
    static GuideResponse of(String variant, boolean detected, String markdown) {
      return new GuideResponse(
          variant, detected, markdown, DevelopmentGuideService.hash(markdown));
    }
  }

  /** One variant, for the listing. */
  public record GuideVariantDto(String name, String description) {}

  /** The listing body. */
  public record GuideVariantsResponse(List<GuideVariantDto> variants) {}

  /** The guide for {@code for}, or for the caller's own variant when {@code for} is absent. */
  @GET
  @Operation(operationId = "getDevelopmentGuide")
  public GuideResponse get(@QueryParam("for") String wanted) {
    if (wanted == null || wanted.isBlank()) {
      String variant = detect(identity);
      return GuideResponse.of(variant, true, guide.render(variant));
    }
    String variant =
        DevelopmentGuideDefaults.variant(wanted)
            .orElseThrow(
                () ->
                    new BadRequestException(
                        "Unknown guide variant: "
                            + wanted
                            + " (known: "
                            + String.join(", ", DevelopmentGuideDefaults.VARIANTS)
                            + ")"));
    return GuideResponse.of(variant, false, guide.render(variant));
  }

  /** The variants there are. */
  @GET
  @Path("/variants")
  @Operation(operationId = "listDevelopmentGuideVariants")
  public GuideVariantsResponse variants() {
    return new GuideVariantsResponse(
        DevelopmentGuideDefaults.VARIANTS.stream()
            .map(v -> new GuideVariantDto(v, DevelopmentGuideDefaults.DESCRIPTIONS.get(v)))
            .toList());
  }

  /** The variant a caller is: see the class javadoc. */
  static String detect(SecurityIdentity caller) {
    if (caller == null || caller.isAnonymous()) {
      return DevelopmentGuideDefaults.WORKSTATION;
    }
    if (caller.hasRole(AgentAccess.DESK_ROLE)) {
      return DevelopmentGuideDefaults.DESK;
    }
    if (EntitiesPrincipal.entityOf(caller).isPresent()) {
      return DevelopmentGuideDefaults.REFINEMENT;
    }
    if (caller.hasRole(AgentAccess.AGENT_ROLE) || caller.hasRole(AgentAccess.ADMIN_AGENT_ROLE)) {
      return DevelopmentGuideDefaults.AGENT;
    }
    return DevelopmentGuideDefaults.WORKSTATION;
  }
}
