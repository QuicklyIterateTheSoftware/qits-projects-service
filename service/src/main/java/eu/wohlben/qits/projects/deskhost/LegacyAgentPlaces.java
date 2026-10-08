package eu.wohlben.qits.projects.deskhost;

import eu.wohlben.qits.containers.client.ContainersAnswer;
import eu.wohlben.qits.containers.client.ContainersClient;
import eu.wohlben.qits.containers.client.ContainersWire.DeleteOutcome;
import eu.wohlben.qits.containers.client.ContainersWire.VolumeEnvelope;
import eu.wohlben.qits.projects.idphost.IdpRunnerCommissioner;
import eu.wohlben.qits.projects.persistence.ProjectRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Clears what the retired direct agent path left behind (qits-767): once per boot, NORMAL launch
 * mode only, on a virtual thread off the startup path, and idempotent — a pass with nothing left
 * deletes nothing and a failure is retried by the next boot.
 *
 * <ol>
 *   <li>For every project, the place {@code owner/project-agent/<projectId>} at qits-containers is
 *       deleted with its volumes ({@code withVolumes=true}). 404 is success; any other failure is a
 *       WARN.
 *   <li>Every qits-idp client of kind {@code agent-container} this service commissioned (the
 *       direct path's per-container credential) is deleted. 404 is success.
 *   <li>A {@code qits_project_<projectId>} owner volume that survives the place's delete is named in
 *       a WARN for a person to remove: there is deliberately no volume door for this.
 * </ol>
 *
 * <p><b>This is the one production caller left that names the workload {@code project-agent}</b>,
 * and {@code DirectAgentPathRetiredTest} keeps it so.
 */
@ApplicationScoped
public class LegacyAgentPlaces {

  private static final Logger LOG = Logger.getLogger(LegacyAgentPlaces.class);

  /** The direct path's workload at qits-containers. */
  public static final String WORKLOAD = "project-agent";

  /** The direct path's idp client kind. */
  public static final String CLIENT_KIND = "agent-container";

  /** The direct path's per-project checkout volume: this prefix and the project id. */
  public static final String VOLUME_PREFIX = "qits_project_";

  @Inject ContainersClient containers;

  @Inject IdpRunnerCommissioner idp;

  @Inject ProjectRepository projects;

  @ConfigProperty(name = "qits.projects.containers.owner")
  String owner;

  /** What one pass did. */
  public record Outcome(
      List<String> placesRemoved,
      List<String> placesFailed,
      List<String> clientsRemoved,
      List<String> volumesSurviving) {}

  void onStart(@Observes StartupEvent event) {
    if (LaunchMode.current() != LaunchMode.NORMAL) {
      return;
    }
    Thread.ofVirtual().name("qits-legacy-agent-places").start(this::runQuietly);
  }

  void runQuietly() {
    try {
      List<String> ids =
          QuarkusTransaction.requiringNew()
              .call(() -> projects.listAll().stream().map(p -> p.id).toList());
      run(ids);
    } catch (RuntimeException e) {
      LOG.warn("The legacy agent place clean-up failed — retried on the next boot.", e);
    }
  }

  /** One pass over {@code projectIds}. */
  public Outcome run(List<String> projectIds) {
    List<String> removed = new ArrayList<>();
    List<String> failed = new ArrayList<>();
    List<String> surviving = new ArrayList<>();
    for (String projectId : projectIds) {
      ContainersAnswer<DeleteOutcome> answer =
          containers.delete(owner, WORKLOAD, projectId, true, false);
      if (answer.succeeded()) {
        LOG.infof("Removed the legacy agent container place %s/%s/%s", owner, WORKLOAD, projectId);
        removed.add(projectId);
      } else if (!isGone(answer)) {
        LOG.warnf(
            "Could not remove the legacy agent container place %s/%s/%s (the next boot retries): %s",
            owner, WORKLOAD, projectId, answer.detail());
        failed.add(projectId);
        continue;
      }
      String volume = VOLUME_PREFIX + projectId;
      ContainersAnswer<VolumeEnvelope> left = containers.volume(owner, volume);
      if (left.succeeded() && left.value() != null) {
        LOG.warnf(
            "The legacy agent checkout volume %s survives its place; a person removes it (there is"
                + " no volume door for this)",
            volume);
        surviving.add(volume);
      }
    }
    List<String> clients = new ArrayList<>();
    for (IdpRunnerCommissioner.LiveClient client :
        idp.liveClients(CLIENT_KIND).orElse(List.of())) {
      if (idp.deleteClient(client.clientId())) {
        LOG.infof(
            "Removed the legacy agent-container client %s (project %s)",
            client.clientId(), client.contextId());
        clients.add(client.clientId());
      }
    }
    return new Outcome(List.copyOf(removed), List.copyOf(failed), List.copyOf(clients), surviving);
  }

  private static boolean isGone(ContainersAnswer<?> answer) {
    return answer instanceof ContainersAnswer.Refused<?> refused && refused.status() == 404;
  }
}
