package eu.wohlben.qits.projects.targethost;

import java.util.Optional;

/**
 * The machine credential this service presents to qits-system's lease doors (epic qits-1481), as a
 * seam — {@code workspacehost/WorkspacesBearer}'s shape and reason. qits-system names a lease's
 * owner ({@code qits-projects}) from it and admits it as the DESK tenant.
 *
 * <p>Empty means no header is sent (the client switched off: dev, test).
 */
public interface SystemBearer {

  /** {@code Bearer <token>}, or empty when this hop has no credential to present. */
  Optional<String> authorization();
}
