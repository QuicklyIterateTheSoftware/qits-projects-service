package eu.wohlben.qits.projects.control;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The clone URL a person or a machine outside the platform uses: {@code
 * <git host>/git/<project slug>/<repository name>} on the git host's public origin — the one clone
 * URL the repository identity ruling allows. Built here, server-side, so no client composes a
 * hostname.
 *
 * <p>The origin is {@code qits.projects.githost-public-url} when set, else {@code
 * https://githost.qits.<QITS_DOMAIN>}: qits-deployments writes the bare domain into every service as
 * {@code QITS_DOMAIN}, and the git host answers on {@code githost.qits.<domain>}, the platform
 * project's host for it, which is also what every container's git helper and every service dials.
 * It was {@code githost.<domain>} until qits-2743, a name the edge reads as another project's and
 * answers 404. With neither, there is no public origin to name and the URL is null — an install
 * without a domain clones over its internal addresses only.
 *
 * <p>"Public" is the name, not the reach: the git host is served by the private edge (qits-2743),
 * so a workstation reaches this URL through {@code qits network-access up}.
 *
 * <p>Not {@link GitHostAddress}: that one is the internal, UUID-keyed storage address this service
 * talks to, which must never leave the projects↔githost seam.
 */
@ApplicationScoped
public class PublicCloneUrls {

  @ConfigProperty(name = "qits.projects.githost-public-url")
  Optional<String> publicUrl;

  @ConfigProperty(name = "qits.domain")
  Optional<String> domain;

  /** The public clone URL, or null when there is no public origin or no name to address. */
  public String cloneUrl(String projectSlug, String repositoryName) {
    if (projectSlug == null || projectSlug.isBlank()) return null;
    if (repositoryName == null || repositoryName.isBlank()) return null;
    return origin().map(base -> base + "/git/" + projectSlug + "/" + repositoryName).orElse(null);
  }

  private Optional<String> origin() {
    Optional<String> configured =
        publicUrl.map(String::trim).filter(url -> !url.isEmpty()).map(PublicCloneUrls::stripSlash);
    if (configured.isPresent()) return configured;
    return domain
        .map(String::trim)
        .filter(value -> !value.isEmpty())
        .map(value -> "https://githost.qits." + value);
  }

  private static String stripSlash(String url) {
    String base = url;
    while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
    return base;
  }
}
