package eu.wohlben.qits.projects.refinementhost;

import eu.wohlben.qits.containers.client.ContainersAnswer;
import eu.wohlben.qits.containers.client.ContainersClient;
import eu.wohlben.qits.containers.client.ContainersWire.DeleteOutcome;
import eu.wohlben.qits.containers.client.ContainersWire.VolumeEnvelope;
import eu.wohlben.qits.projects.control.GitMirrorRegistry;
import eu.wohlben.qits.projects.control.ProjectService;
import eu.wohlben.qits.projects.entity.Project;
import eu.wohlben.qits.projects.gitmirror.RepoMirror;
import eu.wohlben.qits.projects.idphost.IdpRunnerCommissioner;
import eu.wohlben.qits.projects.persistence.ProjectRepository;
import eu.wohlben.qits.projects.persistence.RefinementRepository;
import eu.wohlben.qits.projects.persistence.RepositoryNameRepository;
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
 * Clears what the retired refinement containers left outside this database (epic qits-770,
 * qits-1316): once per boot, NORMAL launch mode only, on a virtual thread off the startup path, and
 * idempotent — a pass with nothing left deletes nothing, and a failure is a WARN the next boot
 * retries. The sibling of {@code deskhost/LegacyAgentPlaces}, for the same reason and in the same
 * shape.
 *
 * <ol>
 *   <li>For every refinement row, the place {@code owner/refinement/<rowId>} at qits-containers is
 *       deleted, then its volume {@code qits_refinement_<rowId>} — the order the containers' own
 *       teardown used. A 404 is done. The rows are KEPT: every room is a session room now, and its
 *       designs, prompt attachments and draft are keyed by its id.
 *   <li>Every qits-idp <b>client</b> of kind {@code refinement} this service commissioned — the
 *       containers' per-room credential pair — is deleted. <b>Clients, never tokens</b>: the room's
 *       own token (qits-1482, {@link RefinementTokens}) carries the same kind word, and a session
 *       runs on it. The two live in different stores behind different doors — a client is listed
 *       at {@code GET <idp>/api/clients} and deleted at {@code DELETE <idp>/api/clients/{clientId}},
 *       a token at {@code <idp>/api/tokens} — and this class reaches only the first, through {@link
 *       IdpRunnerCommissioner#liveClients} and {@link IdpRunnerCommissioner#deleteClient}.
 *   <li>Every {@code refining/*} branch on every project's wrapper is deleted, by a push through the
 *       git host like any other.
 * </ol>
 */
@ApplicationScoped
public class LegacyRefinementContainers {

  private static final Logger LOG = Logger.getLogger(LegacyRefinementContainers.class);

  /** The refinement containers' workload at qits-containers. */
  public static final String WORKLOAD = "refinement";

  /** The containers' checkout volume: this prefix and the row id. */
  public static final String VOLUME_PREFIX = "qits_refinement_";

  /** The kind word of the containers' commissioned idp clients (and of the rooms' live tokens). */
  public static final String CLIENT_KIND = "refinement";

  /** The containers' branches on the wrapper: this prefix and the entity's slug. */
  public static final String BRANCH_PREFIX = "refining/";

  @Inject ContainersClient containers;

  @Inject IdpRunnerCommissioner idp;

  @Inject RefinementRepository refinements;

  @Inject ProjectRepository projects;

  @Inject RepositoryNameRepository repositoryNames;

  @Inject GitMirrorRegistry mirrors;

  @ConfigProperty(name = "qits.projects.containers.owner")
  String owner;

  /** What one pass did. */
  public record Outcome(
      List<Long> placesRemoved,
      List<Long> placesFailed,
      List<String> volumesRemoved,
      List<String> clientsRemoved,
      List<String> branchesRemoved,
      List<String> branchesFailed) {}

  void onStart(@Observes StartupEvent event) {
    if (LaunchMode.current() != LaunchMode.NORMAL) {
      return;
    }
    Thread.ofVirtual().name("qits-legacy-refinement-containers").start(this::runQuietly);
  }

  void runQuietly() {
    try {
      List<Long> rows =
          QuarkusTransaction.requiringNew()
              .call(() -> refinements.listAll().stream().map(row -> row.id).toList());
      List<String> wrappers = QuarkusTransaction.requiringNew().call(this::wrapperRepositoryIds);
      Outcome outcome = run(rows, wrappers);
      LOG.infof(
          "The legacy refinement container clean-up removed %d places, %d volumes, %d"
              + " refinement clients and %d refining branches (%d places and %d branches failed,"
              + " retried on the next boot)",
          outcome.placesRemoved().size(),
          outcome.volumesRemoved().size(),
          outcome.clientsRemoved().size(),
          outcome.branchesRemoved().size(),
          outcome.placesFailed().size(),
          outcome.branchesFailed().size());
    } catch (RuntimeException e) {
      LOG.warn("The legacy refinement container clean-up failed — retried on the next boot.", e);
    }
  }

  /** Every project's wrapper repository id, where the project has one. Inside the caller's tx. */
  private List<String> wrapperRepositoryIds() {
    List<String> ids = new ArrayList<>();
    for (Project project : projects.listAll()) {
      repositoryNames
          .findRepositoryByProjectAndName(project.id, ProjectService.wrapperName(project))
          .ifPresent(wrapper -> ids.add(wrapper.id));
    }
    return ids;
  }

  /** One pass over the rows {@code refinementIds} and the wrappers {@code wrapperRepoIds}. */
  public Outcome run(List<Long> refinementIds, List<String> wrapperRepoIds) {
    List<Long> removed = new ArrayList<>();
    List<Long> failed = new ArrayList<>();
    List<String> volumes = new ArrayList<>();
    for (Long id : refinementIds) {
      String ref = Long.toString(id);
      ContainersAnswer<DeleteOutcome> place = containers.delete(owner, WORKLOAD, ref, false, false);
      if (place.succeeded()) {
        LOG.infof("Removed the legacy refinement container place %s/%s/%s", owner, WORKLOAD, ref);
        removed.add(id);
      } else if (!isGone(place)) {
        LOG.warnf(
            "Could not remove the legacy refinement container place %s/%s/%s (the next boot"
                + " retries): %s",
            owner, WORKLOAD, ref, place.detail());
        failed.add(id);
        continue;
      }
      String volume = VOLUME_PREFIX + id;
      ContainersAnswer<VolumeEnvelope> gone = containers.deleteVolume(owner, volume);
      if (gone.succeeded()) {
        LOG.infof("Removed the legacy refinement volume %s", volume);
        volumes.add(volume);
      } else if (!isGone(gone)) {
        LOG.warnf(
            "Could not remove the legacy refinement volume %s (the next boot retries): %s",
            volume, gone.detail());
        failed.add(id);
      }
    }
    List<String> clients = new ArrayList<>();
    for (IdpRunnerCommissioner.LiveClient client :
        idp.liveClients(CLIENT_KIND).orElse(List.of())) {
      if (idp.deleteClient(client.clientId())) {
        LOG.infof(
            "Removed the legacy refinement client %s (refinement %s)",
            client.clientId(), client.contextId());
        clients.add(client.clientId());
      }
    }
    List<String> branches = new ArrayList<>();
    List<String> branchesFailed = new ArrayList<>();
    for (String repoId : wrapperRepoIds) {
      List<String> refining;
      try {
        refining = branchesOf(repoId).stream().filter(b -> b.startsWith(BRANCH_PREFIX)).toList();
      } catch (RuntimeException e) {
        LOG.warnf(
            "Could not list the branches of wrapper %s (the next boot retries): %s",
            repoId, e.getMessage());
        branchesFailed.add(repoId);
        continue;
      }
      for (String branch : refining) {
        boolean deleted;
        try {
          deleted = deleteBranch(repoId, branch);
        } catch (RuntimeException e) {
          deleted = false;
        }
        if (deleted) {
          LOG.infof("Removed the legacy refinement branch %s of wrapper %s", branch, repoId);
          branches.add(repoId + ":" + branch);
        } else {
          LOG.warnf(
              "Could not remove the legacy refinement branch %s of wrapper %s (the next boot"
                  + " retries)",
              branch, repoId);
          branchesFailed.add(repoId + ":" + branch);
        }
      }
    }
    return new Outcome(
        List.copyOf(removed),
        List.copyOf(failed),
        List.copyOf(volumes),
        List.copyOf(clients),
        List.copyOf(branches),
        List.copyOf(branchesFailed));
  }

  /** The branches the git host holds for {@code repoId}, short-named. A seam for the test. */
  List<String> branchesOf(String repoId) {
    return mirrors.of(repoId).remoteBranches();
  }

  /**
   * Delete {@code branch} on the git host; answers whether it is gone. The mirror is brought up to
   * date first, because the push runs in it. A seam for the test.
   */
  boolean deleteBranch(String repoId, String branch) {
    RepoMirror mirror = mirrors.of(repoId);
    mirror.refresh();
    return mirror.deleteBranch(branch).accepted() || !mirror.remoteHasBranch(branch);
  }

  private static boolean isGone(ContainersAnswer<?> answer) {
    return answer instanceof ContainersAnswer.Refused<?> refused && refused.status() == 404;
  }
}
