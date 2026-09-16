package eu.wohlben.qits.projects.releasehost;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.projects.control.PublishRuns;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The {@link PublishRuns} port over qits-ci's release-phase read — {@code GET
 * /ci/api/repositories/{repoId}/release-phase?rev=refs/tags/<version>}, answering {@code
 * {"repositoryId","rev","declared","detail"}}. A hand-rolled {@code java.net.http} client on the
 * shape {@link HttpActiveBuilds} sets one class over, because the seam is one GET.
 *
 * <p><b>The composer is asked because only the composer knows.</b> The archetype a repository's
 * {@code .config/qits/release.yml} names lives at the wrapper's {@code main}, and the repository's
 * own file may override the {@code release:} slot wholesale — see {@link PublishRuns} for the whole
 * argument. This service reads the tag's tree to know whether to ask at all, and asks qits-ci for
 * the answer.
 *
 * <p>{@code qits.projects.release-requests.ci-url} is <b>unset shipped</b>: a deployment names its
 * tier's qits-ci ({@code http://dev-qits-ci:8080}), and unset answers {@code Optional.empty()} —
 * "could not ask". <b>Every failure answers the same</b>: an unset address, an unreachable service,
 * a 503 saying the question could not be answered yet, any other non-200, a body without a boolean
 * {@code declared}, an exception. <b>None of them is ever {@code false}</b>, which is the one answer
 * that would let a released tag reach {@code main} without its publish having been checked —
 * {@link HttpActiveBuilds}' rule about its own listing, sharpened: there the wrong answer waves a
 * release past a running build, here it waves one past a gate nobody asked about.
 *
 * <p>The credential is a machine bearer when the {@code ci} named client is enabled ({@link
 * IdpCiBearer}); while it is off (the shipped default, and any no-idp topology) the read falls back
 * to the forwarded pair, qits-net's standing posture and the same fallback the active listing takes
 * on the same address.
 */
@ApplicationScoped
@DefaultBean
public class HttpPublishRuns implements PublishRuns {

  private static final Logger LOG = Logger.getLogger(HttpPublishRuns.class);

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

  @ConfigProperty(name = "qits.projects.release-requests.ci-url")
  Optional<String> ciUrl;

  @jakarta.inject.Inject IdpCiBearer bearer;

  @Override
  public Optional<Boolean> declaredFor(String repoId, String rev) {
    if (repoId == null || repoId.isBlank() || rev == null || rev.isBlank()) {
      return Optional.empty();
    }
    if (ciUrl.isEmpty() || ciUrl.get().isBlank()) {
      return Optional.empty();
    }
    try {
      URI uri =
          URI.create(
              ciUrl.get()
                  + "/ci/api/repositories/"
                  + URLEncoder.encode(repoId, StandardCharsets.UTF_8)
                  + "/release-phase?rev="
                  + URLEncoder.encode(rev, StandardCharsets.UTF_8));
      HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3)).GET();
      Optional<String> authorization = bearer.authorization();
      if (authorization.isPresent()) {
        builder.header("Authorization", authorization.get());
      } else {
        builder.header("X-Qits-User", "qits-projects").header("X-Qits-Roles", "qits:system");
      }
      HttpResponse<String> response =
          client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        // 503 is the far side saying it could not answer yet and is part of the contract; every
        // other status is the same fact from here — nobody told us anything about this tag.
        LOG.debugf(
            "qits-ci answered %d for the release phase of %s at %s",
            response.statusCode(), repoId, rev);
        return Optional.empty();
      }
      JsonNode declared = MAPPER.readTree(response.body()).path("declared");
      if (!declared.isBoolean()) {
        LOG.debugf(
            "qits-ci answered the release phase of %s at %s without a boolean declared", repoId, rev);
        return Optional.empty();
      }
      return Optional.of(declared.asBoolean());
    } catch (Exception e) {
      LOG.debugf("Could not read qits-ci's release phase of %s at %s: %s", repoId, rev, e.toString());
      return Optional.empty();
    }
  }
}
