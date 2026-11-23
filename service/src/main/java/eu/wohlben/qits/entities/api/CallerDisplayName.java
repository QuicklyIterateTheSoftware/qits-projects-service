package eu.wohlben.qits.entities.api;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.json.JsonString;
import java.util.List;
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * <b>Who a person or a machine is, for a sentence or an event</b>: the token's {@code name}, else
 * its {@code preferred_username}, else the principal name (a machine client's id), else {@code
 * someone}. One rule for the comment turn ({@code WorkEntityDoors.tellTheAgent}), {@code
 * EntityCommented.authorName} and {@code EntityAssigned.changedByName} (qits-2518), so the three
 * never name one caller two ways.
 */
public final class CallerDisplayName {

  /** What a caller with no usable name at all is called. */
  public static final String SOMEONE = "someone";

  private CallerDisplayName() {}

  /** {@code identity}'s display name — see the class javadoc. Never null. */
  public static String of(SecurityIdentity identity) {
    if (identity != null && identity.getPrincipal() instanceof JsonWebToken jwt) {
      for (String claim : List.of("name", "preferred_username")) {
        Object value = jwt.getClaim(claim);
        String text =
            value instanceof JsonString json
                ? json.getString()
                : value instanceof String plain ? plain : null;
        if (text != null && !text.isBlank()) {
          return text.trim();
        }
      }
    }
    String principal = EntitiesPrincipal.changedBy(identity);
    return principal == null || principal.isBlank() ? SOMEONE : principal;
  }
}
