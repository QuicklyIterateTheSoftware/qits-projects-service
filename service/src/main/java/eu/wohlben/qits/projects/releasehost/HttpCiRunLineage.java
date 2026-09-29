package eu.wohlben.qits.projects.releasehost;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.projects.control.CiRunLineage;
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
 * The {@link CiRunLineage} port over qits-ci's single-run read, {@code GET /ci/api/runs/{runId}} —
 * {@link HttpActiveBuilds}' shape one path over: the same {@code ci-url}, the same credential (the
 * {@code ci} named client's bearer when enabled, the forwarded {@code qits:system} pair otherwise),
 * the same short timeouts. The far side admits {@code qits:system}.
 *
 * <p>Only {@code retryOfRunId} is read off the run. Every failure — unset address, 404, any other
 * non-200, unreachable, an unreadable body — is {@code Optional.empty()}: the retry walk stops there,
 * which is what it did before this port existed. A 404 is logged at DEBUG because it is ordinary (a
 * run qits-ci has since collected); everything else at WARN, since it silently costs a re-arm.
 */
@ApplicationScoped
@DefaultBean
public class HttpCiRunLineage implements CiRunLineage {

  private static final Logger LOG = Logger.getLogger(HttpCiRunLineage.class);

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

  @ConfigProperty(name = "qits.projects.release-requests.ci-url")
  Optional<String> ciUrl;

  @jakarta.inject.Inject IdpCiBearer bearer;

  @Override
  public Optional<String> retryOfRunId(String runId) {
    if (runId == null || runId.isBlank() || ciUrl.isEmpty() || ciUrl.get().isBlank()) {
      return Optional.empty();
    }
    try {
      HttpRequest.Builder builder =
          HttpRequest.newBuilder(
                  URI.create(
                      ciUrl.get()
                          + "/ci/api/runs/"
                          + URLEncoder.encode(runId, StandardCharsets.UTF_8)))
              .timeout(Duration.ofSeconds(3))
              .GET();
      Optional<String> authorization = bearer.authorization();
      if (authorization.isPresent()) {
        builder.header("Authorization", authorization.get());
      } else {
        builder.header("X-Qits-User", "qits-projects").header("X-Qits-Roles", "qits:system");
      }
      HttpResponse<String> response =
          client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() == 404) {
        LOG.debugf("qits-ci knows no run %s; the retry chain stops there", runId);
        return Optional.empty();
      }
      if (response.statusCode() != 200) {
        LOG.warnf(
            "qits-ci answered %d for run %s; the retry chain stops there",
            response.statusCode(),
            runId);
        return Optional.empty();
      }
      JsonNode parent = MAPPER.readTree(response.body()).path("retryOfRunId");
      if (!parent.isTextual() || parent.asText().isBlank()) {
        return Optional.empty();
      }
      return Optional.of(parent.asText());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    } catch (Exception e) {
      LOG.warnf("Could not read run %s from qits-ci; the retry chain stops there: %s", runId, e);
      return Optional.empty();
    }
  }
}
