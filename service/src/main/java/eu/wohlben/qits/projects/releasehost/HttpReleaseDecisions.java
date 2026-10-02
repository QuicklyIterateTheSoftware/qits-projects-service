package eu.wohlben.qits.projects.releasehost;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.projects.control.ReleaseDecisions;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The {@link ReleaseDecisions} port over qits-ci's decision record — {@code GET
 * /ci/api/repositories/{repoId}/releases/{version}/artifacts}, answering {@code
 * {"repository","version","artifacts":[{"type","name","publish","decision","unchangedSince",…}]}}.
 * Built exactly like {@link HttpPublishRuns}: the same address ({@code
 * qits.projects.release-requests.ci-url}), the same credential ({@link IdpCiBearer}, else the
 * forwarded pair) and the same timeouts.
 *
 * <p><b>Every failure is a sentence and never an empty record.</b> An unset address, nobody
 * listening, any non-200, a body without an {@code artifacts} list or an entry without a {@code
 * type}, {@code name} and {@code decision} — each answers {@link ReleaseDecisions.Answer#failed}.
 * An empty list is qits-ci saying nothing was owed at that version, and an outage read as that
 * would tell the release view the release published nothing.
 */
@ApplicationScoped
@DefaultBean
public class HttpReleaseDecisions implements ReleaseDecisions {

  private static final Logger LOG = Logger.getLogger(HttpReleaseDecisions.class);

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

  @ConfigProperty(name = "qits.projects.release-requests.ci-url")
  Optional<String> ciUrl;

  @jakarta.inject.Inject IdpCiBearer bearer;

  @Override
  public Answer of(String repoId, String version) {
    if (repoId == null || repoId.isBlank() || version == null || version.isBlank()) {
      return Answer.failed("no repository or no version to ask about");
    }
    if (ciUrl.isEmpty() || ciUrl.get().isBlank()) {
      return Answer.failed("no qits-ci address is configured");
    }
    try {
      URI uri =
          URI.create(
              ciUrl.get()
                  + "/ci/api/repositories/"
                  + URLEncoder.encode(repoId, StandardCharsets.UTF_8)
                  + "/releases/"
                  + URLEncoder.encode(version, StandardCharsets.UTF_8)
                  + "/artifacts");
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
        LOG.debugf(
            "qits-ci answered %d for the release record of %s at %s",
            response.statusCode(), repoId, version);
        return Answer.failed("qits-ci answered " + response.statusCode());
      }
      JsonNode artifacts = MAPPER.readTree(response.body()).path("artifacts");
      if (!artifacts.isArray()) {
        return Answer.failed("qits-ci's answer carries no artifacts list");
      }
      List<Decision> decisions = new ArrayList<>();
      for (JsonNode entry : artifacts) {
        String type = text(entry.path("type"));
        String name = text(entry.path("name"));
        String decision = text(entry.path("decision"));
        if (type == null || name == null || decision == null) {
          return Answer.failed("qits-ci's answer has an artifact without a type, name or decision");
        }
        decisions.add(new Decision(type, name, decision, text(entry.path("unchangedSince"))));
      }
      return Answer.of(decisions);
    } catch (Exception e) {
      LOG.debugf(
          "Could not read qits-ci's release record of %s at %s: %s", repoId, version, e.toString());
      return Answer.failed(
          "qits-ci could not be reached (" + e.getClass().getSimpleName() + ")");
    }
  }

  private static String text(JsonNode node) {
    if (node == null || !node.isTextual()) {
      return null;
    }
    String text = node.asText().trim();
    return text.isEmpty() ? null : text;
  }
}
