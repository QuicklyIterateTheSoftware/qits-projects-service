package eu.wohlben.qits.projects.security;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The suite's {@link SessionIntrospection}: an ordinary bean, so it wins the injection over the
 * {@code @DefaultBean} idp client in every {@code @QuarkusTest}.
 *
 * <p><b>Stateless by design.</b> A session this fake knows is spelled in the cookie value itself —
 * {@link #cookie} builds one — so any test class can present a person without injecting, staging
 * or resetting anything, and two classes sharing the application cannot see each other's sessions.
 * Every other value is the idp's 404: unknown.
 */
@ApplicationScoped
public class FakeSessionIntrospection implements SessionIntrospection {

  private static final String PREFIX = "fake-session|";

  /** A {@code qits-session} value this fake answers as {@code username} holding {@code roles}. */
  public static String cookie(String username, String... roles) {
    return PREFIX + username + "|" + String.join(",", roles);
  }

  /** A {@code qits-session} value for {@code username} holding {@code qits:admin}: a person. */
  public static String admin(String username) {
    return cookie(username, AgentAccess.ADMIN_ROLE);
  }

  @Override
  public Optional<Session> introspect(String value) {
    if (value == null || !value.startsWith(PREFIX)) {
      return Optional.empty();
    }
    String[] parts = value.substring(PREFIX.length()).split("\\|", -1);
    if (parts.length != 2) {
      return Optional.empty();
    }
    List<String> roles =
        parts[1].isEmpty() ? List.of() : Arrays.asList(parts[1].split(","));
    return Optional.of(new Session(parts[0], roles));
  }
}
