package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.entities.api.EntityMovers;
import eu.wohlben.qits.entities.control.Mover;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * <b>The dispatch press and its read</b> (qits-970, epic qits-965), behind {@code
 * /work/{qualifiedId}/dispatch} ({@link WorkDispatchController}), which resolves its path to an
 * entity id and hands it here — the one dispatch path, moved out of the {@code
 * /entities/{id}/dispatch} controller deleted in qits-976. Nothing here names a controller class;
 * the door wraps the dispatch in its own answer record.
 */
@ApplicationScoped
public class DispatchDoors {

  @Inject EntityDispatch dispatch;

  /** Who presses, person or machine (qits-1075) — qits-891's one definition of a person. */
  @Inject EntityMovers movers;

  /**
   * The press, on the one dispatch path. The mode is read first, so a missing or unknown one is the
   * 400 it always was, then the entity's 404.
   */
  public EntityDispatchDto press(SecurityIdentity identity, String entityId, String mode) {
    return press(identity, entityId, mode, null);
  }

  /**
   * The press, naming the rule it is made under — read only for a delivery desk (epic qits-1440),
   * whose refusals are {@link EntityDispatch}'s desk refusals, in their own order, before anything
   * else is decided.
   */
  public EntityDispatchDto press(
      SecurityIdentity identity, String entityId, String mode, String rule) {
    DispatchMode parsed = DispatchMode.parse(mode);
    // Who presses, as qits-891's person check says (qits-1075): a person's FLOW press pre-approves
    // the scheduling. Its name is the changedBy a person's press always recorded; a machine's is the
    // caller's principal, as before.
    Mover mover = movers.of(identity);
    dispatch.get(entityId); // 404
    if (mover.isDesk()) {
      return dispatch.dispatch(entityId, parsed, mover, rule).toDto();
    }
    return dispatch.dispatch(entityId, parsed, mover).toDto();
  }

  /** The read: what a press would start now. */
  public EntityDispatchStateDto state(String entityId) {
    return dispatch.state(entityId);
  }
}
