package eu.wohlben.qits.entities.api;

import eu.wohlben.qits.auth.MachineIdentity;
import eu.wohlben.qits.auth.QitsClaims;
import eu.wohlben.qits.entities.control.Mover;
import eu.wohlben.qits.projects.security.AgentAccess;
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
 * <p><b>A project's delivery desk is a machine of its own kind</b> (epic qits-1440): a caller holding
 * {@link #DESK_ROLE} and neither {@code qits:admin} nor {@code qits:admin-agent} is {@link
 * Mover#desk}, bound to its token's {@code project} claim (null when it carried none, which the
 * dispatch door then refuses as another project's). A wider role wins, as {@code
 * AgentAccess.isBoundAgent} reads it.
 *
 * <p>The lifecycle doors build one per move ({@code WorkEntityDoors.move}, behind {@code POST
 * /work/{qualifiedId}/status} for every archetype, a campaign's included); the MCP tools and the platform's in-process callers never do — they are machines
 * by construction and say so with {@link Mover#machine}.
 */
@ApplicationScoped
public class EntityMovers {

  /** The role of a project's delivery desk token ({@code desk-delivery}, epic qits-1440). */
  public static final String DESK_ROLE = "qits:desk";

  @Inject PersonCheck persons;

  /** The mover behind {@code identity} on the current request. */
  public Mover of(SecurityIdentity identity) {
    return persons
        .verifiedAdmin(identity)
        .map(Mover::person)
        .orElseGet(() -> machineOf(identity));
  }

  private static Mover machineOf(SecurityIdentity identity) {
    String name = EntitiesPrincipal.changedBy(identity);
    if (identity != null
        && identity.hasRole(DESK_ROLE)
        && !identity.hasRole(AgentAccess.ADMIN_ROLE)
        && !identity.hasRole(AgentAccess.ADMIN_AGENT_ROLE)) {
      return Mover.desk(name, MachineIdentity.claim(identity, QitsClaims.PROJECT).orElse(null));
    }
    return Mover.machine(name);
  }
}
