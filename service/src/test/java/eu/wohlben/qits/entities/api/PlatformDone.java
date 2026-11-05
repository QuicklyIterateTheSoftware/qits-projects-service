package eu.wohlben.qits.entities.api;

import eu.wohlben.qits.entities.control.Mover;
import eu.wohlben.qits.entities.control.WorkEntityService;
import eu.wohlben.qits.entities.entity.Archetype;
import eu.wohlben.qits.entities.entity.EntityStatus;
import eu.wohlben.qits.projects.entitieshost.EntityIdResolver;
import eu.wohlben.qits.projects.entitieshost.TicketWrapperDone;
import io.quarkus.arc.Arc;

/**
 * A VERIFIED ticket moved to DONE the way the platform moves it (qits-150), for a suite that needs a
 * DONE ticket and is not about how it gets there. No door moves a ticket to DONE any more — both
 * refuse it for every role — so a test that walked one there through {@code POST
 * /work/{id}/status} walks it to VERIFIED and calls this instead. In-process, as {@link
 * TicketWrapperDone#MOVER}.
 */
public final class PlatformDone {

  private PlatformDone() {}

  /**
   * Moves the VERIFIED ticket {@code idOrQualified} (a UUID or a qualified id) to DONE; answers
   * the id it was given.
   *
   * @throws IllegalStateException when the ticket is not VERIFIED
   */
  public static String of(String idOrQualified) {
    String id = Arc.container().instance(EntityIdResolver.class).get().resolve(idOrQualified).id;
    WorkEntityService entities = Arc.container().instance(WorkEntityService.class).get();
    entities
        .transitionFrom(
            Archetype.TICKET,
            id,
            EntityStatus.VERIFIED,
            EntityStatus.DONE,
            Mover.machine(TicketWrapperDone.MOVER))
        .orElseThrow(() -> new IllegalStateException("ticket " + idOrQualified + " is not VERIFIED"));
    return idOrQualified;
  }
}
