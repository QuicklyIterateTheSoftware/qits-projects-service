package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.entities.api.EntitiesPrincipal;
import eu.wohlben.qits.projects.control.AgentMcpCatalog;
import eu.wohlben.qits.projects.control.AgentMcpCatalogService;
import eu.wohlben.qits.projects.control.AgentMcpCredentialStore;
import eu.wohlben.qits.projects.dto.AgentMcpCatalogEntryDto;
import eu.wohlben.qits.projects.dto.AgentMcpCredentialDto;
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
 * <p>Beside the one server a surface toggles ({@code qits}) an operator may define others: a key, a display name, a URL, an optional header name
 * with the credential key holding its value, and the tools pre-approved on it. Defined once
 * and attached many times, from {@link AgentSurfaceConfigurationController}'s write body — a server
 * is not re-entered, with its token, for each of eight surfaces.
 *
 * <p><b>The credential is a reference and an entry never carries a value.</b> The entry names a
 * credential key ({@code env.<VAR>}); the value lives in this service's own store, written through
 * the write-only doors under {@code /agent-mcp-catalog/credentials} (qits-1394, which replaced the
 * read from qits-configuration), read once when a container's document is built, and never answered,
 * logged or snapshotted. The listing of those credentials answers keys and who set them, never a
 * value.
 *
 * <p><b>Three refusals, each closing a silent failure:</b>
 *
 * <ul>
 *   <li>A <b>reserved key</b> — {@code qits}, and the retired {@code repository}, {@code
 *       observability}, {@code actions} — is a 400, because both harnesses render the attached
 *       servers into one {@code key → config} object and an external entry under one of those names
 *       would displace a platform server, or be refused by a harness that still reserves it.
 *   <li>A <b>non-http(s) URL</b> is a 400. Kimi carries servers over ACP as {@code (key, url,
 *       tools)} with nowhere to put a stdio command, and a stdio server would need its binary inside
 *       the workspace image anyway.
 *   <li>A <b>credential key with no credential set</b> is a 400 naming it, so a typo is caught at
 *       this form rather than at the next container provision. Building a document checks again and
 *       fails loudly, because a credential can be deleted after the entry was saved.
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
@RolesAllowed({"qits:admin", "qits:admin-agent", "qits:desk-admin"})
public class AgentMcpCatalogController {

  @Inject AgentMcpCatalogService catalog;

  @Inject AgentMcpCredentialStore credentialStore;

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
                  "The credential key holding that header's value — `env.<VAR>`, set through"
                      + " PUT /agent-mcp-catalog/credentials/{key}. Empty exactly when headerName"
                      + " is. The value lives in the credential store and never here.")
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
      @Schema(
              description =
                  "Keys an entry may not claim: the platform's own server and the retired ones.")
          List<String> reservedKeys) {}

  /** The credential write's body: the value, and nothing else. */
  public record CredentialRequest(
      @Schema(
              description =
                  "The header value, verbatim. Write-only: no door answers it back.")
          String value) {}

  /** The credentials listing: keys and who set them, never a value. */
  public record CredentialListResponse(List<AgentMcpCredentialDto> credentials) {}

  /** Every entry, by key, each saying which surfaces attach it. */
  @GET
  @RolesAllowed({"qits:admin", "qits:admin-agent", "qits:desk-admin", "qits:agent"})
  public CatalogListResponse list() {
    return new CatalogListResponse(
        catalog.listAll(),
        eu.wohlben.qits.projects.control.AgentSurfaceDefaults.RESERVED_SERVER_KEYS);
  }

  // ------------------------------------------------------------------------------- credentials
  //
  // The literal `credentials` segment wins over `/{key}` (JAX-RS prefers the longer literal), and
  // AgentMcpCatalog.requireCatalogKey refuses `credentials` as a catalog key, so the two never meet.

  /** Every credential that is set, by key — <b>never a value</b>. */
  @GET
  @Path("/" + AgentMcpCatalog.CREDENTIALS_SEGMENT)
  @RolesAllowed({"qits:admin", "qits:admin-agent", "qits:desk-admin", "qits:agent"})
  public CredentialListResponse listCredentials() {
    return new CredentialListResponse(credentialStore.list());
  }

  /**
   * Set the value behind a credential key, creating or replacing it. Answers the key and who set it,
   * never the value. 400 on a key outside {@code env.<VAR>} or a blank value.
   */
  @PUT
  @Path("/" + AgentMcpCatalog.CREDENTIALS_SEGMENT + "/{key}")
  @Consumes(MediaType.APPLICATION_JSON)
  @RolesAllowed({"qits:admin", "qits:admin-agent"})
  public AgentMcpCredentialDto setCredential(
      @PathParam("key") String key, CredentialRequest request) {
    return credentialStore.set(
        key, request == null ? null : request.value(), EntitiesPrincipal.changedBy(identity));
  }

  /** Remove the value behind a credential key. 204, or 404 when none is set. */
  @DELETE
  @Path("/" + AgentMcpCatalog.CREDENTIALS_SEGMENT + "/{key}")
  @RolesAllowed({"qits:admin", "qits:admin-agent"})
  public Response deleteCredential(@PathParam("key") String key) {
    credentialStore.delete(key);
    return Response.noContent().build();
  }

  /** One entry, or a 404 — unlike a surface, an entry that does not exist is not a default. */
  @GET
  @RolesAllowed({"qits:admin", "qits:admin-agent", "qits:desk-admin", "qits:agent"})
  @Path("/{key}")
  public AgentMcpCatalogEntryDto get(@PathParam("key") String key) {
    return catalog.get(key);
  }

  /** Create or replace one entry. 400 on a reserved key, a non-http(s) url or a missing reference. */
  @PUT
  @Path("/{key}")
  @Consumes(MediaType.APPLICATION_JSON)
  @RolesAllowed({"qits:admin", "qits:admin-agent"})
  public AgentMcpCatalogEntryDto save(
      @PathParam("key") String key, CatalogEntryRequest request) {
    return catalog.save(
        key,
        request.displayName(),
        request.url(),
        request.headerName(),
        request.credentialKey(),
        request.allowedTools(),
        EntitiesPrincipal.changedBy(identity));
  }

  /** Remove one entry. 400 while a surface still attaches it, naming the surfaces. */
  @DELETE
  @Path("/{key}")
  @RolesAllowed({"qits:admin", "qits:admin-agent"})
  public Response delete(@PathParam("key") String key) {
    catalog.delete(key);
    return Response.noContent().build();
  }
}
