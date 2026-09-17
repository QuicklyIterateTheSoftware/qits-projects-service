package eu.wohlben.qits.projects.releasehost;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.projects.control.PipelinePhaseReruns;
import eu.wohlben.qits.projects.error.DomainException;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The {@link PipelinePhaseReruns} port over qits-ci's rerun door — {@code POST /ci/api/runs/rerun}
 * with {@code {repoId, releaseRequestId, phase}}, answering {@code 202 {"runId": …}}. The fourth hop
 * on {@code qits.projects.release-requests.ci-url}, beside the active listing, the cancellation and
 * the release-phase read, and the first of them that is a person's button.
 *
 * <h2>Propagating the refusal is the job</h2>
 *
 * <p><b>qits-ci's 409 reaches the caller with its message intact, and that message is the whole
 * point of the door.</b> A phase cannot always be asked again, and the reasons are facts about this
 * release that the person pressing the button does not have: the QA phase's newest run <em>succeeded</em>,
 * so its verdict was spent on cutting the tag and the fold it built no longer exists; the publish
 * phase's succeeded, so it already published what the release names; the phase has never run at all;
 * or it is running right now and the question is still being answered. Each of those is a sentence
 * qits-ci composes naming the run and the request, and a 404 names a repository it does not know. So
 * this class reads {@code {"message": …}} off the body and rethrows the far side's own status
 * carrying the far side's own words. <b>A rewritten refusal is a refusal with the reason filed
 * off</b>, and "could not re-run that phase" would be strictly less than what was already known.
 *
 * <p>Statuses it composes itself are only the ones qits-ci said nothing about: <b>503</b> with no
 * address configured — a platform with no qits-ci cannot re-run anything — and <b>502</b> for the
 * exchange, an unreachable service or a body that carries no run id. A refusal with no readable
 * message still travels with its status, and gains a neutral sentence rather than a guessed one.
 *
 * <p>The credential is {@link IdpCiBearer}, this service's machine bearer, with the same forwarded
 * {@code X-Qits-*} fallback its three neighbours on this address take — the rerun door is {@code
 * @RolesAllowed({"qits:admin", "qits:system"})} over there, and {@code qits:system} is exactly what
 * this service's client carries.
 */
@ApplicationScoped
@DefaultBean
public class HttpPipelinePhaseReruns implements PipelinePhaseReruns {

  private static final Logger LOG = Logger.getLogger(HttpPipelinePhaseReruns.class);

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

  @ConfigProperty(name = "qits.projects.release-requests.ci-url")
  Optional<String> ciUrl;

  @Inject IdpCiBearer bearer;

  @Override
  public String rerun(String repoId, String releaseRequestId, String ciPhase) {
    if (ciUrl.isEmpty() || ciUrl.get().isBlank()) {
      throw new DomainException(
          503,
          "No qits-ci address is configured (qits.projects.release-requests.ci-url), so no run of"
              + " this release can be asked for again.");
    }
    try {
      Map<String, String> body = new LinkedHashMap<>();
      body.put("repoId", repoId);
      body.put("releaseRequestId", releaseRequestId);
      body.put("phase", ciPhase);
      HttpRequest.Builder builder =
          HttpRequest.newBuilder(URI.create(ciUrl.get() + "/ci/api/runs/rerun"))
              .timeout(Duration.ofSeconds(5))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)));
      Optional<String> authorization = bearer.authorization();
      if (authorization.isPresent()) {
        builder.header("Authorization", authorization.get());
      } else {
        builder.header("X-Qits-User", "qits-projects").header("X-Qits-Roles", "qits:system");
      }
      HttpResponse<String> response =
          client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 202 && response.statusCode() != 200) {
        throw new DomainException(response.statusCode(), messageOf(response, ciPhase));
      }
      JsonNode runId = MAPPER.readTree(response.body()).path("runId");
      if (!runId.isTextual() || runId.asText().isBlank()) {
        // Accepted with nothing named. The run may well have been queued, so this is the exchange
        // having failed rather than the ask having been refused.
        throw new DomainException(
            502, "qits-ci accepted the rerun but named no run, so it cannot be followed.");
      }
      return runId.asText();
    } catch (DomainException e) {
      throw e;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new DomainException(502, "Interrupted asking qits-ci to run this phase again.");
    } catch (Exception e) {
      LOG.warnf(
          "Could not ask qits-ci to re-run the %s phase of release request %s: %s",
          ciPhase, releaseRequestId, e.toString());
      throw new DomainException(
          502, "Could not reach qits-ci to ask for this phase to be run again.");
    }
  }

  /**
   * What qits-ci said, verbatim where it said anything. Its exception mapper answers {@code
   * {"message": …}} for every refusal, so the sentence is one field away; a body that carries none
   * gets a neutral fallback naming the status, never a guess at the reason.
   */
  private static String messageOf(HttpResponse<String> response, String ciPhase) {
    try {
      JsonNode message = MAPPER.readTree(response.body()).path("message");
      if (message.isTextual() && !message.asText().isBlank()) {
        return message.asText();
      }
    } catch (Exception e) {
      // Fall through to the neutral sentence: an unreadable body is not a reason.
    }
    return "qits-ci answered "
        + response.statusCode()
        + " asking for the "
        + ciPhase
        + " phase to be run again.";
  }
}
