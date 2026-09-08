package eu.wohlben.qits.epics.api;

import io.quarkus.security.identity.SecurityIdentity;

/**
 * Resolves the audit "changed-by" value from the request identity (null when anonymous).
 *
 * <p><b>Public because a second package in this module stamps through it.</b> {@code
 * projects.api.TicketDispatchController} writes a comment onto a ticket's thread and has to stamp it
 * from the caller exactly as {@link TicketController} does; a copy of these five lines over there
 * would be a second answer to "who is calling", free to drift from this one. It is still module-
 * internal — nothing outside {@code service} sees it, and the {@code epics} jar carries no identity.
 */
public final class EpicsPrincipal {

  private EpicsPrincipal() {}

  public static String changedBy(SecurityIdentity identity) {
    if (identity == null || identity.isAnonymous() || identity.getPrincipal() == null) {
      return null;
    }
    return identity.getPrincipal().getName();
  }
}
