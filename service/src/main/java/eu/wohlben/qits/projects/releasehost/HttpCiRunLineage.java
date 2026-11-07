package eu.wohlben.qits.projects.releasehost;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.http.QitsHttpClients;
import eu.wohlben.qits.projects.control.CiRunLineage;
import eu.wohlben.qits.projects.control.StaleOutputs;
import eu.wohlben.qits.projects.control.UncoveredOperations;
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
 * The {@link CiRunLineage} port over qits-ci's single-run read, {@code GET /ci/api/runs/{runId}} —
 * {@link HttpActiveBuilds}' shape one path over: the same {@code ci-url}, the same credential (the
 * {@code qits} named client's bearer alone, failing closed without one), the same short timeouts. The far side admits {@code qits:system}.
 *
 * <p>Only {@code retryOfRunId} is read off the run. Every failure — unset address, no bearer, 404, any other
 * non-200, unreachable, an unreadable body — is {@code Optional.empty()}: the retry walk stops there,
 * which is what it did before this port existed. A 404 is logged at DEBUG because it is ordinary (a
 * run qits-ci has since collected); everything else at WARN, since it silently costs a re-arm.
 *
 * <p>Since qits-1920 it also reads a red run's reports, {@code GET /ci/api/runs/{runId}/reports}
 * ({@code listRunReports}), for the {@code stale outputs: <kind>} highlights of its {@value
 * StaleOutputs#REPORT_KIND} summary, under the same rules: every failure is an empty list, a 404 at
 * DEBUG and the rest at WARN, and the rejection then reads as it did before. Since qits-1940 the
 * same response is also read for its {@value UncoveredOperations#REPORT_KIND} report — the same one
 * request, so its consumer pact row is unchanged — and {@link #findings} answers both at once.
 */
@ApplicationScoped
@DefaultBean
public class HttpCiRunLineage implements CiRunLineage {

  private static final Logger LOG = Logger.getLogger(HttpCiRunLineage.class);

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final HttpClient client;

  @ConfigProperty(name = "qits.projects.release-requests.ci-url")
  Optional<String> ciUrl;

  @jakarta.inject.Inject IdpCiBearer bearer;

  @jakarta.inject.Inject
  public HttpCiRunLineage(QitsHttpClients clients) {
    this.client = clients.wrap(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
  }

  /** Shuts the client down with the bean: a leaked SelectorManager thread per instance (qits-1149). */
  @jakarta.annotation.PreDestroy
  void close() {
    client.shutdownNow();
  }

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
      if (authorization.isEmpty()) {
        LOG.warnf("Not reading run %s from qits-ci; the retry chain stops there: %s", runId,
            IdpCiBearer.MISSING);
        return Optional.empty();
      }
      builder.header("Authorization", authorization.get());
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

  @Override
  public RunFindings findings(String runId) {
    if (runId == null || runId.isBlank() || ciUrl.isEmpty() || ciUrl.get().isBlank()) {
      return RunFindings.NONE;
    }
    try {
      Optional<String> authorization = bearer.authorization();
      if (authorization.isEmpty()) {
        LOG.warnf("Not reading run %s's reports from qits-ci: %s", runId, IdpCiBearer.MISSING);
        return RunFindings.NONE;
      }
      HttpRequest request =
          HttpRequest.newBuilder(
                  URI.create(
                      ciUrl.get()
                          + "/ci/api/runs/"
                          + URLEncoder.encode(runId, StandardCharsets.UTF_8)
                          + "/reports"))
              .timeout(Duration.ofSeconds(3))
              .header("Authorization", authorization.get())
              .GET()
              .build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() == 404) {
        LOG.debugf("qits-ci knows no run %s; its rejection names no findings", runId);
        return RunFindings.NONE;
      }
      if (response.statusCode() != 200) {
        LOG.warnf(
            "qits-ci answered %d for run %s's reports; its rejection names no findings",
            response.statusCode(),
            runId);
        return RunFindings.NONE;
      }
      JsonNode reports = MAPPER.readTree(response.body()).path("reports");
      if (!reports.isArray()) {
        LOG.warnf("qits-ci answered no reports[] for run %s; its rejection names no findings",
            runId);
        return RunFindings.NONE;
      }
      List<String> kinds = new ArrayList<>();
      UncoveredOperations uncovered = null;
      for (JsonNode report : reports) {
        String reportKind = report.path("kind").asText(null);
        if (StaleOutputs.REPORT_KIND.equals(reportKind)) {
          for (JsonNode highlight : report.path("highlights")) {
            String kind = StaleOutputs.kindOf(highlight.path("text").asText(null));
            if (kind != null && !kinds.contains(kind)) {
              kinds.add(kind);
            }
          }
        } else if (UncoveredOperations.REPORT_KIND.equals(reportKind) && uncovered == null) {
          List<String> texts = new ArrayList<>();
          for (JsonNode highlight : report.path("highlights")) {
            texts.add(highlight.path("text").asText(null));
          }
          uncovered = UncoveredOperations.of(texts);
        }
      }
      return new RunFindings(kinds, uncovered);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return RunFindings.NONE;
    } catch (Exception e) {
      LOG.warnf("Could not read run %s's reports from qits-ci; its rejection names no findings:"
          + " %s", runId, e);
      return RunFindings.NONE;
    }
  }
}
