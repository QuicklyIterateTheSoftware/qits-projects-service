package eu.wohlben.qits.projects.persistence;

import eu.wohlben.qits.projects.entity.AgentSurfaceExternalMcpAttachment;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

/**
 * Which catalog entries each surface attaches.
 *
 * <p>Its own repository rather than a cascaded collection, for the reason its built-in sibling gives:
 * a write replaces the whole set, and an explicit delete-flush-insert is what keeps that replacement
 * clear of the unique {@code (surface_key, catalog_key)} constraint.
 */
@ApplicationScoped
public class AgentSurfaceExternalMcpAttachmentRepository
    implements PanacheRepositoryBase<AgentSurfaceExternalMcpAttachment, String> {

  /** One surface's external attachments in render order. */
  public List<AgentSurfaceExternalMcpAttachment> forSurface(String surfaceKey) {
    return list("surfaceKey = ?1 order by position", surfaceKey);
  }

  /** Every attachment, so a listing reads the whole store without a query per surface. */
  public List<AgentSurfaceExternalMcpAttachment> allOrdered() {
    return list("order by surfaceKey, position");
  }

  /** The surfaces attaching one catalog entry — what a refused delete names. */
  public List<AgentSurfaceExternalMcpAttachment> forCatalogKey(String catalogKey) {
    return list("catalogKey = ?1 order by surfaceKey", catalogKey);
  }

  /** Drop a surface's whole set. Idempotent — a surface attaching nothing is not an error. */
  public void clearSurface(String surfaceKey) {
    delete("surfaceKey", surfaceKey);
  }
}
