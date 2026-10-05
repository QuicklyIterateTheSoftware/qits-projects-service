package eu.wohlben.qits.projects.security;

import java.util.List;
import java.util.Optional;

/**
 * The seam {@link PersonCheck} asks a browser's {@code qits-session} cookie through: who, if
 * anyone, is behind this value <b>according to qits-idp</b>, asked by this service itself.
 *
 * <p>The implementation is {@code idphost/IdpSessionIntrospection}, a {@code @DefaultBean} HTTP
 * client; the suite replaces it with an ordinary bean. Its contract is one answer for every kind
 * of "no": an unknown, expired or revoked value, an idp that refuses this service, one that cannot
 * be reached, and a process holding no credential at all (the %dev/%test posture) are all {@link
 * Optional#empty()}. The caller refuses on empty and never needs to know which it was — the far
 * side's own 404 already folds four causes into one for the same reason.
 */
public interface SessionIntrospection {

  /**
   * The live session behind a {@code qits-session} cookie value, or empty.
   *
   * @param cookie the cookie's value exactly as the browser sent it; never logged
   */
  Optional<Session> introspect(String cookie);

  /**
   * What qits-idp says about a live session — the two of its {@code SessionView} fields a decision
   * needs. {@code username} is the same name the edge writes into {@code X-Qits-User} from the
   * same answer, so a decision recorded from it reads exactly like every other row a person wrote.
   */
  record Session(String username, List<String> roles) {

    public Session {
      roles = roles == null ? List.of() : List.copyOf(roles);
    }
  }
}
