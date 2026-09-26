package eu.wohlben.qits.projects.deploymenthost;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.projects.control.DeploymentRedeploys;
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
 * The {@link DeploymentRedeploys} port over qits-deployments' release intake — {@code POST
 * /deployments/api/events/software-released}, answering {@code 202} with no body.
 *
 * <p><b>This is the door a redeploy has always gone through and the only one there has ever been.</b>
 * qits-deployments has no separate redeploy path — it has never had one — and its intake names "an
 * operator redeploys a version" as one of the two things it exists for, being deliberately exempt
 * from the monotonic version collapse so that a re-post of a version already seen is honoured
 * rather than swallowed. Nothing is retired and nothing is invented: the rerun re-asks the same
 * question the release asked.
 *
 * <h2>The credential, which is {@link HttpDeploymentRequests}' reversed</h2>
 *
 * <p><b>The machine bearer is what this door wants, and the forwarded pair is the fallback.</b> The
 * intake is {@code @RolesAllowed({"qits:system"})} plus a machine-auth check, and {@code qits:system}
 * is exactly what a service client of this platform carries — so the bearer is the right caller here
 * where it is the wrong one for the listing next door, which wants {@code qits:admin}/{@code
 * qits:agent}. Same service, same address, two doors, two callers. That is the whole reason these are
 * two classes rather than two methods, on top of the failure contracts being opposite.
 *
 * <p>The forwarded pair is sent when the named client is off, which is the shipped default and any
 * no-idp topology — and it is a pair the far side accepts <b>only while its own machine-auth gate is
 * off</b>, which is the same topology. Where that gate is on and this service holds no credential,
 * the far side refuses and the refusal reaches the person who pressed the button, which is the
 * correct outcome and the point of the contract below.
 *
 * <h2>The failure contract</h2>
 *
 * <p><b>It throws, unlike every read hop in this service.</b> Somebody pressed a button and is
 * waiting: a hop that did not happen must reach them as a status code rather than as a shrug in a
 * log. <b>503</b> where there is no address at all — a platform with no qits-deployments cannot
 * redeploy, and that is a configuration fact rather than an outage. <b>502</b> for everything the
 * exchange itself can do: unreachable, refused, a validation error, any status that is not 202. The
 * message names what happened, because the caller's next move differs between "nobody is there" and
 * "they said no".
 */
@ApplicationScoped
@DefaultBean
public class HttpDeploymentRedeploys implements DeploymentRedeploys {

  private static final Logger LOG = Logger.getLogger(HttpDeploymentRedeploys.class);

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

  @ConfigProperty(name = "qits.projects.release-requests.deployments-url")
  Optional<String> deploymentsUrl;

  @Inject IdpDeploymentsBearer bearer;

  @Override
  public void deployAgain(Redeploy ask) {
    if (deploymentsUrl.isEmpty() || deploymentsUrl.get().isBlank()) {
      throw new DomainException(
          503,
          "No qits-deployments address is configured"
              + " (qits.projects.release-requests.deployments-url), so this version cannot be"
              + " deployed again from here.");
    }
    try {
      // A LinkedHashMap rather than a record: the far side binds five optional fields beside the
      // two it requires, and a null one is simply not sent rather than travelling as an explicit
      // null for it to fall back from.
      Map<String, String> body = new LinkedHashMap<>();
      body.put("repoId", ask.repoId());
      put(body, "projectId", ask.projectId());
      put(body, "repoName", ask.repoName());
      put(body, "application", ask.application());
      body.put("version", ask.version());
      HttpRequest.Builder builder =
          HttpRequest.newBuilder(
                  URI.create(
                      deploymentsUrl.get() + "/deployments/api/events/software-released"))
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
        LOG.warnf(
            "qits-deployments answered %d asking for %s %s to be deployed again",
            response.statusCode(), ask.repoId(), ask.version());
        throw new DomainException(
            502,
            "qits-deployments answered "
                + response.statusCode()
                + " asking for version "
                + ask.version()
                + " to be deployed again.");
      }
    } catch (DomainException e) {
      throw e;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new DomainException(502, "Interrupted asking qits-deployments to deploy again.");
    } catch (Exception e) {
      LOG.warnf(
          "Could not ask qits-deployments to deploy %s %s again: %s",
          ask.repoId(), ask.version(), e.toString());
      throw new DomainException(
          502, "Could not reach qits-deployments to ask for this version to be deployed again.");
    }
  }

  private static void put(Map<String, String> body, String key, String value) {
    if (value != null && !value.isBlank()) {
      body.put(key, value);
    }
  }
}
