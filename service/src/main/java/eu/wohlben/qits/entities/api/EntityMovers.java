package eu.wohlben.qits.entities.api;

import eu.wohlben.qits.entities.control.Mover;
import eu.wohlben.qits.projects.security.PersonCheck;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * <b>Who a REST door's caller is, as a {@link Mover}</b> (qits-887): a person when qits-891's {@link
 * PersonCheck} verified one — a browser session this service introspected, or a person's {@code
 * qits} CLI bearer — under the name that proof carries; a machine otherwise, under the caller's
 * principal. Nothing else here decides "is this a person", and no forwarded identity header ever
 * makes one: asserted headers with or without a machine bearer are a machine.
 *
 * <p>The lifecycle doors build one per move ({@code EntityRoutes.move}, {@code CampaignController
 * .transition}); the MCP tools and the platform's in-process callers never do — they are machines
 * by construction and say so with {@link Mover#machine}.
 */
@ApplicationScoped
public class EntityMovers {

  @Inject PersonCheck persons;

  /** The mover behind {@code identity} on the current request. */
  public Mover of(SecurityIdentity identity) {
    return persons
        .verifiedAdmin(identity)
        .map(Mover::person)
        .orElseGet(() -> Mover.machine(EntitiesPrincipal.changedBy(identity)));
  }
}
