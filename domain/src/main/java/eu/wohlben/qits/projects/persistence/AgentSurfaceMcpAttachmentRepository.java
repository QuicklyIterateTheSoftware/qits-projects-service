package eu.wohlben.qits.projects.persistence;

import eu.wohlben.qits.projects.entity.AgentSurfaceMcpAttachment;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

/**
 * The MCP servers each surface attaches.
 *
 * <p>Its own repository rather than a cascaded collection on {@code AgentSurfaceConfiguration}: a
 * write replaces the whole set, and an explicit delete-then-flush-then-insert is what keeps that
 * replacement clear of the unique {@code (surface_key, server_key)} constraint. A cascaded orphan
 * removal is free to flush its inserts before its deletes, which turns a no-op edit into a
 * constraint violation.
 */
@ApplicationScoped
public class AgentSurfaceMcpAttachmentRepository
    implements PanacheRepositoryBase<AgentSurfaceMcpAttachment, String> {

  /** One surface's attachments in render order. */
  public List<AgentSurfaceMcpAttachment> forSurface(String surfaceKey) {
    return list("surfaceKey = ?1 order by position", surfaceKey);
  }

  /** Every attachment, so a listing reads the whole store without a query per surface. */
  public List<AgentSurfaceMcpAttachment> allOrdered() {
    return list("order by surfaceKey, position");
  }

  /** Drop a surface's whole set. Idempotent — a surface attaching nothing is not an error. */
  public void clearSurface(String surfaceKey) {
    delete("surfaceKey", surfaceKey);
  }
}
