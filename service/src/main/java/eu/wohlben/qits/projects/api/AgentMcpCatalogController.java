package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.epics.api.EpicsPrincipal;
import eu.wohlben.qits.projects.control.AgentMcpCatalog;
import eu.wohlben.qits.projects.control.AgentMcpCatalogService;
import eu.wohlben.qits.projects.dto.AgentMcpCatalogEntryDto;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * <b>The external MCP server catalog</b>: servers this platform does not own, defined once and
 * attached per surface.
 *
 * <p>Beside the three servers a surface toggles ({@code repository}, {@code observability}, {@code
 * actions}) an operator may define others: a key, a display name, a URL, an optional header name
 * with the qits-configuration key holding its value, and the tools pre-approved on it. Defined once
 * and attached many times, from {@link AgentSurfaceConfigurationController}'s write body — a server
 * is not re-entered, with its token, for each of eight surfaces.
 *
 * <p><b>The credential is a reference and this route never sees a value.</b> The entry names a
 * qits-configuration key under the reserved {@code qits-agent-mcp} application; the value is read
 * once, when a container's document is built, and is not stored here, not answered here and never
 * logged. That is what makes the model survive secrets management arriving in qits-configuration
 * later: the same key is then served as a secret and nothing on this route changes.
 *
 * <p><b>Three refusals, each closing a silent failure:</b>
 *
 * <ul>
 *   <li>A <b>reserved key</b> — {@code repository}, {@code observability}, {@code actions} — is a
 *       400, because both harnesses render the attached servers into one {@code key → config} object
 *       and an external entry under one of those names would displace a platform server. The session
 *       would look entirely normal and be talking to somebody else's server.
 *   <li>A <b>non-http(s) URL</b> is a 400. Kimi carries servers over ACP as {@code (key, url,
 *       tools)} with nowhere to put a stdio command, and a stdio server would need its binary inside
 *       the workspace image anyway.
 *   <li>A <b>credential key qits-configuration does not hold</b> is a 400 naming it, so a typo is
 *       caught at this form rather than at the next container provision. If qits-configuration
 *       cannot be <em>asked</em>, the write is accepted with a warning — an outage of another service
 *       is not a validation error — and the strict gate stays where it protects a running agent:
 *       building a document fails loudly on an unresolvable reference.
 * </ul>
 *
 * <p><b>A delete is refused while any surface attaches the entry</b>, naming those surfaces. That is
 * why the attachment table carries no foreign key onto this one: an FK would make this message
 * unreachable and a 500 inevitable.
 *
 * <p>Admin-scoped, like every operator surface here.
 */
@Path("/agent-mcp-catalog")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("qits:admin")
public class AgentMcpCatalogController {

  @Inject AgentMcpCatalogService catalog;

  @Inject SecurityIdentity identity;

  /** The whole of what an operator sets on an entry. A replacement, not a patch. */
  public record CatalogEntryRequest(
      @Schema(description = "What an operator reads in the list. Never rendered into a command.")
          String displayName,
      @Schema(description = "An http(s) URL. URL transport only — see the class javadoc.")
          String url,
      @Schema(
              description =
                  "The header the credential is presented in, typically Authorization. Empty means"
                      + " the server takes no credential.")
          String headerName,
      @Schema(
              description =
                  "The qits-configuration key holding that header's value — `env.<VAR>` under the"
                      + " reserved `"
                      + AgentMcpCatalog.CREDENTIAL_APPLICATION
                      + "` application. Empty exactly when headerName is. The value lives there and"
                      + " never here.")
          String credentialKey,
      @Schema(
              description =
                  "The tools pre-approved on this server. Operator-editable, unlike the built-in"
                      + " servers' shipped lists: the platform ships no constant for a server it has"
                      + " never heard of. Empty pre-approves nothing.")
          List<String> allowedTools) {}

  /** The listing body. */
  public record CatalogListResponse(
      List<AgentMcpCatalogEntryDto> entries,
      @Schema(description = "Keys an entry may not claim — the platform's own servers.")
          List<String> reservedKeys,
      @Schema(
              description =
                  "The qits-configuration application every credentialKey lives under. Reserved:"
                      + " nothing deploys under it, so these keys are never rendered into any"
                      + " application's environment.")
          String credentialApplication) {}

  /** Every entry, by key, each saying which surfaces attach it. */
  @GET
  @RolesAllowed({"qits:admin", "qits:agent"})
  public CatalogListResponse list() {
    return new CatalogListResponse(
        catalog.listAll(),
        eu.wohlben.qits.projects.control.AgentSurfaceDefaults.BUILT_IN_SERVERS,
        AgentMcpCatalog.CREDENTIAL_APPLICATION);
  }

  /** One entry, or a 404 — unlike a surface, an entry that does not exist is not a default. */
  @GET
  @RolesAllowed({"qits:admin", "qits:agent"})
  @Path("/{key}")
  public AgentMcpCatalogEntryDto get(@PathParam("key") String key) {
    return catalog.get(key);
  }

  /** Create or replace one entry. 400 on a reserved key, a non-http(s) url or a missing reference. */
  @PUT
  @Path("/{key}")
  @Consumes(MediaType.APPLICATION_JSON)
  public AgentMcpCatalogEntryDto save(
      @PathParam("key") String key, CatalogEntryRequest request) {
    return catalog.save(
        key,
        request.displayName(),
        request.url(),
        request.headerName(),
        request.credentialKey(),
        request.allowedTools(),
        EpicsPrincipal.changedBy(identity));
  }

  /** Remove one entry. 400 while a surface still attaches it, naming the surfaces. */
  @DELETE
  @Path("/{key}")
  public Response delete(@PathParam("key") String key) {
    catalog.delete(key);
    return Response.noContent().build();
  }
}
