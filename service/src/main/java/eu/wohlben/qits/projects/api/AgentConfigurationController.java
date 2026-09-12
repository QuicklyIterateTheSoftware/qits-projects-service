package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.projects.control.AgentSurfaceConfigurationService;
import eu.wohlben.qits.projects.dto.AgentConfigurationDocumentDto;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * <b>The container's door</b>: the whole resolved agent configuration a container is created with.
 *
 * <p>One GET, one body, no parameters. This is the read qits-workspaces' container factory makes
 * when it provisions a workspace container, and the read this service makes for its own agent
 * container; what comes back is written to a file and mounted, and the daemon inside validates it at
 * boot the way it already reads the hook port and the claude mount.
 *
 * <p><b>A snapshot, not a subscription.</b> There is no long poll here and no event: a container
 * keeps what it was born with for its whole life, and an edit reaches the next container. That is
 * the epic's decision, and the trade it buys is that the launch path inside a container stays a pure
 * local render with no runtime dependency on this service.
 *
 * <p><b>Every surface, not the caller's guess at its own.</b> Which surfaces a given container will
 * serve is the caller's knowledge and it changes as the product grows; the document is a handful of
 * kilobytes, and a container that turns out to serve a surface the fetcher did not predict is better
 * off holding a configuration for it than silently falling back to the library's constants. It also
 * keeps this door free of a parameter every caller would have to be taught to send correctly.
 *
 * <p>Beside {@link AgentContainerController} and {@link AgentSurfaceConfigurationController} — the
 * three are the same subject from three sides: stand the container up, decide what it runs, hand it
 * the answer.
 *
 * <p><b>Two roles, and the second is the point.</b> {@code qits:system} is what a peer service
 * presents; {@code qits:admin} is here so an operator can read the same document the containers get,
 * which is the only way to check what a container was actually handed. The document carries no
 * credential today; when the external MCP catalog lands it will carry resolved header values, and
 * this door's scoping is where that has to be reconsidered rather than inherited.
 */
@Path("/agent-configuration")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"qits:admin", "qits:system", "qits:agent"})
public class AgentConfigurationController {

  @Inject AgentSurfaceConfigurationService surfaces;

  /** The whole document, resolved as of now. */
  @GET
  public AgentConfigurationDocumentDto document() {
    return surfaces.document();
  }
}
