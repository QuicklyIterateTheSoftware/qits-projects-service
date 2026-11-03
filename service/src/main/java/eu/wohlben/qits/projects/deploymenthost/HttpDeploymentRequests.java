package eu.wohlben.qits.projects.deploymenthost;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.http.QitsHttpClients;
import eu.wohlben.qits.projects.control.DeploymentRequests;
import io.quarkus.arc.DefaultBean;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The {@link DeploymentRequests} port over qits-deployments' own listing — {@code GET
 * /deployments/api/deployment-requests?repoId=…&version=…}, answering {@code
 * {"deploymentRequests": [...]}}, newest first. A hand-rolled {@code java.net.http} client on
 * {@code releasehost/HttpPublishRuns}' shape, because the seam is one GET.
 *
 * <p><b>The pair is the key and the far side refuses half of one.</b> {@code repoId} and {@code
 * version} are asked together over there — a question naming only a repository is a 400 — which is
 * also why the deploy phase is on the single-request read path and on no list: see {@code
 * ReleasePipelineAssembler.DeployReach}. Both are sent url-encoded; a blank either side is not sent
 * at all, because a half pair is a refusal and this port would report it as "could not ask", which
 * would be true and useless.
 *
 * <p>{@code qits.projects.release-requests.deployments-url} ships a default derived from {@code
 * QITS_ENVIRONMENT} ({@code http://${QITS_ENVIRONMENT:dev}-qits-deployments:8080}), qits-deployments
 * being one of the nine platform services and this process already knowing its own environment; a
 * deployment may still blank it to switch the hop off explicitly. <b>Every failure answers the
 * same</b> — a blank or unset address, an unreachable service, a refusal, any non-200, a body without
 * a {@code deploymentRequests} array, an exception — and <b>none of them is ever an empty list</b>,
 * which is the one answer that would draw a released, deployable repository as though nothing were
 * owed. An empty list reaches the assembler only when qits-deployments really answered one.
 *
 * <h2>The credential</h2>
 *
 * <p><b>This hop presents the {@code qits} named client's machine bearer ({@link
 * IdpDeploymentsBearer}) and nothing else</b> — no forwarded {@code X-Qits-*} pair beside it or
 * instead of it (owner ruling 2026-10-18, epic qits-543). The listing at the far side, qits-deployments'
 * {@code PdDeploymentRequestController}, admits {@code qits:system}, which is exactly what this
 * service's client carries; the {@code qits:agent} pair this class once asserted was a service
 * acting with an agent's role, and is gone (qits-1332).
 *
 * <p>With no bearer — the named client disabled, the shipped default and any no-idp topology, or a
 * mint that failed — the hop <b>fails closed</b>: nothing is sent, one WARN names the missing client
 * ({@link IdpDeploymentsBearer#MISSING}) and the answer is the same "could not ask" as every other
 * failure above, never an empty list.
 *
 * <p>{@link HttpDeploymentRedeploys} holds the same bearer on the same address; the two stay two
 * classes because their failure contracts are opposite — this one answers "could not ask", that one
 * throws to the person who pressed the button.
 */
@ApplicationScoped
@DefaultBean
public class HttpDeploymentRequests implements DeploymentRequests {

  private static final Logger LOG = Logger.getLogger(HttpDeploymentRequests.class);

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final HttpClient client;

  @ConfigProperty(name = "qits.projects.release-requests.deployments-url")
  Optional<String> deploymentsUrl;

  @Inject IdpDeploymentsBearer bearer;

  @Inject
  public HttpDeploymentRequests(QitsHttpClients clients) {
    this.client = clients.wrap(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
  }

  /** Shuts the client down with the bean: a leaked SelectorManager thread per instance (qits-1149). */
  @PreDestroy
  void close() {
    client.shutdownNow();
  }

  @Override
  public Optional<List<DeploymentRequestView>> forRelease(String repoId, String version) {
    if (repoId == null || repoId.isBlank() || version == null || version.isBlank()) {
      return Optional.empty();
    }
    if (deploymentsUrl.isEmpty() || deploymentsUrl.get().isBlank()) {
      return Optional.empty();
    }
    Optional<String> authorization = bearer.authorization();
    if (authorization.isEmpty()) {
      LOG.warnf(
          "Not reading qits-deployments' deployment requests for %s %s: %s",
          repoId, version, IdpDeploymentsBearer.MISSING);
      return Optional.empty();
    }
    try {
      URI uri =
          URI.create(
              deploymentsUrl.get()
                  + "/deployments/api/deployment-requests?repoId="
                  + URLEncoder.encode(repoId, StandardCharsets.UTF_8)
                  + "&version="
                  + URLEncoder.encode(version, StandardCharsets.UTF_8));
      HttpRequest request =
          HttpRequest.newBuilder(uri)
              .timeout(Duration.ofSeconds(3))
              .header("Authorization", authorization.get())
              .GET()
              .build();
      HttpResponse<String> response =
          client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        LOG.debugf(
            "qits-deployments answered %d for the deployment requests of %s %s",
            response.statusCode(), repoId, version);
        return Optional.empty();
      }
      JsonNode requests = MAPPER.readTree(response.body()).path("deploymentRequests");
      if (!requests.isArray()) {
        // A 200 with no array in it says nothing about this version. It is not an empty listing.
        LOG.debugf(
            "qits-deployments answered the deployment requests of %s %s without an array",
            repoId, version);
        return Optional.empty();
      }
      List<DeploymentRequestView> views = new ArrayList<>();
      for (JsonNode node : requests) {
        views.add(
            new DeploymentRequestView(
                text(node, "id"), text(node, "deploymentStatus"), instant(node, "createdAt")));
      }
      // The far side orders by its own sequence, newest first, and that order is the answer: the
      // phase is the first entry. Nothing is re-sorted here — the order a row was written in is a
      // fact only the writer holds.
      return Optional.of(List.copyOf(views));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    } catch (Exception e) {
      LOG.debugf(
          "Could not read qits-deployments' deployment requests for %s %s: %s",
          repoId, version, e.toString());
      return Optional.empty();
    }
  }

  /** A string field, or null — a missing one and an explicit JSON null are one answer. */
  private static String text(JsonNode node, String field) {
    JsonNode value = node.path(field);
    return value.isTextual() ? value.asText() : null;
  }

  /** An instant field, or null where it is absent, null or unreadable. Never "now". */
  private static Instant instant(JsonNode node, String field) {
    String value = text(node, field);
    if (value == null) {
      return null;
    }
    try {
      return Instant.parse(value);
    } catch (RuntimeException e) {
      return null;
    }
  }
}
