package eu.wohlben.qits.projects.control;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The shipped {@link GitHostAddress}: {@code qits.githost.url} plus the {@code /git/<repoId>} route
 * qits-githost serves (projects-volume-decoupling-plan.md §2.3).
 *
 * <p><b>The git host is qits-githost, not qits-artifacts.</b> The byte-plane split moved the git
 * smart-HTTP server into a service of its own — a repository is not an artifact, it only shared the
 * storage layout — so both the key and the route segment moved with it. The old spelling was {@code
 * qits.artifacts.url} plus {@code /artifacts/git/<repoId>}; a deployment still passing {@code
 * QITS_ARTIFACTS_URL} configures nothing now, which is the loud failure rather than the quiet one
 * of aiming every mirror clone, fetch, push and lifecycle call at a service that no longer serves
 * git.
 *
 * <p><b>{@code @DefaultBean}.</b> It yields to any other bean of the type, which is what lets a test
 * double point every mirror at a local bare with no change on the production side. Keep the
 * annotation: dropping it makes the two an ambiguous dependency and the build fails at {@code
 * ArcProcessor#validate}, for every test at once.
 *
 * <p>The path segment is spelled here and not configured. It is the git host's contract, not a
 * deployment's choice — {@code GitHostRoutes.BASE} in qits-githost is the same literal —
 * and a second copy in a properties file would be a second place for it to drift.
 */
@ApplicationScoped
@DefaultBean
public class ConfiguredGitHostAddress implements GitHostAddress {

  @Inject Instance<GitHostBearer> bearer;

  /**
   * Scheme, host and port with <b>no path</b> — the shape {@code qits.observability.url} already
   * uses, so one value works whether the call goes direct on qits-net or through the gateway.
   *
   * <p>The value ships in {@code META-INF/microprofile-config.properties}: the git host's edge host,
   * {@code https://githost.qits.<domain>} (qits-1415), never the {@code <env>-qits-githost} wire
   * alias. No default here, so the one spelling is the shipped file's.
   */
  @ConfigProperty(name = "qits.githost.url")
  String gitHostUrl;

  @Override
  public String fetchUrl(String repoId) {
    String base = gitHostUrl == null ? "" : gitHostUrl.trim();
    while (base.endsWith("/")) {
      base = base.substring(0, base.length() - 1);
    }
    return base + "/git/" + repoId;
  }

  /** One address, so reads and writes cannot drift apart in a deployment. */
  @Override
  public String pushUrl(String repoId) {
    return fetchUrl(repoId);
  }

  /** The mirror turns this into git's one-request {@code Authorization} extra header. */
  @Override
  public Optional<String> httpExtraHeader() {
    if (!bearer.isResolvable()) {
      return Optional.empty();
    }
    return bearer
        .get()
        .token()
        .filter(token -> !token.isBlank())
        .filter(token -> token.indexOf('\r') < 0 && token.indexOf('\n') < 0)
        .map(token -> "Authorization: Bearer " + token);
  }
}
