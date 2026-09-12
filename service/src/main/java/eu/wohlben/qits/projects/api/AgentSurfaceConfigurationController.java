package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.epics.api.EpicsPrincipal;
import eu.wohlben.qits.projects.control.AgentSurfaceConfigurationService;
import eu.wohlben.qits.projects.control.AgentSurfaceDefaults;
import eu.wohlben.qits.projects.dto.AgentMcpAttachmentDto;
import eu.wohlben.qits.projects.dto.AgentSurfaceConfigurationDto;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.time.Instant;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * <b>The editor's door</b> onto what each agent session surface runs as: list the surfaces, read
 * one, replace one, read one's history.
 *
 * <p>It sits beside {@link AgentContainerController} because it configures the same thing from the
 * other end — that controller stands the container up, this one decides what the sessions inside it
 * will be. Admin-scoped like every operator surface here.
 *
 * <p><b>A read never 404s.</b> A surface with no row answers its shipped default, flagged {@code
 * shipped: true}, and so does a surface outside the vocabulary entirely — which is what lets a
 * daemon that knows a surface this store has not been told about still launch. The flag is the
 * editor's cue that it is looking at what ships rather than at what somebody chose.
 *
 * <p><b>The write is a replacement, not a patch</b>, and it takes only what an operator may set. The
 * MCP pre-approval lists are shipped constants in v1 and are deliberately absent from {@link
 * SurfaceUpdateRequest}: sending them would make the one thing this epic says is not
 * operator-editable look editable, and the write would then have to decide whose list wins.
 *
 * <p>What is validated on write is exactly what the render path cannot survive being wrong about: an
 * unknown harness, an unknown permission mode, and an MCP attachment naming a server that does not
 * exist. Each is a 400 naming the known values rather than a row that fails at somebody's next
 * launch.
 *
 * <p><b>An edit applies to the next container.</b> A running container keeps the document it was
 * created with; nothing here pushes, and no staleness is reported. That is the epic's decision
 * rather than an omission, and what makes it safe is that a launch records what it actually ran
 * with.
 */
@Path("/agent-surfaces")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("qits:admin")
public class AgentSurfaceConfigurationController {

  @Inject AgentSurfaceConfigurationService surfaces;

  @Inject SecurityIdentity identity;

  /** One MCP attachment as the editor sets it — the server and its narrowing, and nothing else. */
  public record McpAttachmentRequest(
      @Schema(description = "repository, observability or actions") String server,
      boolean narrowProject,
      boolean narrowRepository,
      boolean narrowWorkspace,
      @Schema(description = "Append agentReadOnly=true — the autonomous fence.") boolean readOnly) {}

  /**
   * The whole configuration an operator is setting.
   *
   * <p>Strings for {@code harness} and {@code permissionMode} rather than enums, on purpose: an
   * unknown value must come back as a 400 naming what is known, and a body Jackson refused to bind
   * cannot say that.
   */
  public record SurfaceUpdateRequest(
      String harness,
      @Schema(description = "Empty means the harness's own default.") String model,
      @Schema(description = "Empty renders no --effort.") String effort,
      boolean remoteControl,
      @Schema(description = "SKIP_PERMISSIONS or PROMPT") String permissionMode,
      boolean activityTracking,
      @Schema(description = "Appended to the harness's own. Empty is a value.") String systemPrompt,
      @Schema(description = "A turn pushed at session start. Empty pushes none.")
          String initialPrompt,
      List<McpAttachmentRequest> mcpServers,
      @Schema(
              description =
                  "Catalog keys of the external MCP servers this surface attaches. Keys only —"
                      + " everything about a server (url, header, pre-approved tools) belongs to"
                      + " the catalog entry, which is defined once at /agent-mcp-catalog and"
                      + " attached here many times.")
          List<String> externalMcpServers) {}

  /** The listing body. */
  public record SurfaceListResponse(
      @Schema(description = "Every known surface, resolved; shipped defaults where no row exists.")
          List<AgentSurfaceConfigurationDto> surfaces,
      @Schema(description = "The MCP servers this platform can attach — the reserved keys.")
          List<String> builtInServers) {}

  /** One revision of a surface's configuration. */
  public record RevisionDto(
      String id, String surface, String changedBy, Instant changedAt, String snapshot) {}

  /** A surface's history, newest first. */
  public record RevisionListResponse(List<RevisionDto> revisions) {}

  /** Every surface, in the vocabulary's own order, with anything stored beyond it after. */
  @GET
  @RolesAllowed({"qits:admin", "qits:agent"})
  public SurfaceListResponse list() {
    return new SurfaceListResponse(surfaces.listAll(), AgentSurfaceDefaults.BUILT_IN_SERVERS);
  }

  /** One surface. Answers its shipped default rather than 404ing when no row exists. */
  @GET
  @RolesAllowed({"qits:admin", "qits:agent"})
  @Path("/{surface}")
  public AgentSurfaceConfigurationDto get(@PathParam("surface") String surface) {
    return surfaces.get(surface);
  }

  /** Replace one surface's configuration. 400 on an unknown harness, mode or MCP server. */
  @PUT
  @Path("/{surface}")
  @Consumes(MediaType.APPLICATION_JSON)
  public AgentSurfaceConfigurationDto update(
      @PathParam("surface") String surface, SurfaceUpdateRequest request) {
    AgentSurfaceConfigurationDto wanted =
        surfaces.validated(
            surface,
            request.harness(),
            request.model(),
            request.effort(),
            request.remoteControl(),
            request.permissionMode(),
            request.activityTracking(),
            request.systemPrompt(),
            request.initialPrompt(),
            request.mcpServers() == null
                ? List.of()
                : request.mcpServers().stream()
                    .map(
                        a ->
                            new AgentMcpAttachmentDto(
                                a.server(),
                                a.narrowProject(),
                                a.narrowRepository(),
                                a.narrowWorkspace(),
                                a.readOnly(),
                                List.of()))
                    .toList(),
            request.externalMcpServers());
    return surfaces.save(surface, wanted, EpicsPrincipal.changedBy(identity));
  }

  /**
   * One surface's revision trail, newest first — who changed it, when, and the whole configuration
   * as it stood afterwards.
   *
   * <p>The snapshot travels as an opaque JSON string rather than a nested object: it is the shape
   * the configuration had <em>then</em>, and binding it to today's record would quietly rewrite
   * history the first time a field is added.
   */
  @GET
  @RolesAllowed({"qits:admin", "qits:agent"})
  @Path("/{surface}/revisions")
  public RevisionListResponse revisions(@PathParam("surface") String surface) {
    return new RevisionListResponse(
        surfaces.history(surface).stream()
            .map(r -> new RevisionDto(r.id, r.surfaceKey, r.changedBy, r.changedAt, r.snapshot))
            .toList());
  }
}
