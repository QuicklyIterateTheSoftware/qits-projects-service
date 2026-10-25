package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.entities.api.EntitiesAgentAccess;
import eu.wohlben.qits.projects.entity.Refinement;
import eu.wohlben.qits.projects.refinementhost.RefinementService;
import io.quarkus.security.identity.SecurityIdentity;

/**
 * The one way a {@code /refinements/{id}/…} door resolves its row (qits-1482): the row, or 404,
 * and then {@link EntitiesAgentAccess#requireEntity} — so a bearer bound to one entity (a
 * refinement room's own token) answers 403 on every other room, and an id naming nothing still
 * answers 404 first. Written once so no door can forget the binding; the project binding the write
 * doors carry ({@link EntitiesAgentAccess#requireProject}) stays at each door, where it was.
 */
final class RefinementAccess {

  private RefinementAccess() {}

  /** The refinement {@code id} names, or 404; 403 when the caller is bound to another entity. */
  static Refinement reach(RefinementService refinements, SecurityIdentity identity, long id) {
    Refinement refinement = refinements.get(id);
    EntitiesAgentAccess.requireEntity(identity, refinement.entityId);
    return refinement;
  }

  /**
   * {@link #reach}, for a write: also refuses a bound agent or the project's delivery desk
   * (qits-1596) whose token's {@code project} is not the room's ({@link
   * EntitiesAgentAccess#requireProject}). A person and the coordinator pass untouched.
   */
  static Refinement reachWrite(RefinementService refinements, SecurityIdentity identity, long id) {
    Refinement refinement = reach(refinements, identity, id);
    EntitiesAgentAccess.requireProject(identity, refinement.projectId);
    return refinement;
  }
}
