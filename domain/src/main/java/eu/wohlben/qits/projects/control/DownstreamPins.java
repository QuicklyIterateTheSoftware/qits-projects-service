package eu.wohlben.qits.projects.control;

import java.util.List;
import java.util.Optional;

/**
 * <b>Who consumes a repository, and what each consumer pins</b> — the two reads {@code
 * approval/ContractChangeSource} asks qits-maintenance (epic qits-1038, task qits-1291), as a port
 * in the house shape: the dependency graph is qits-maintenance's to hold, and the one implementation
 * is an HTTP client in {@code service/…/maintenancehost/}.
 *
 * <p>Two reads rather than one because the downstream closure names repositories and not the
 * coordinates they were reached through: a provider service's closure holds its golden-master
 * consumers <em>and</em> the providers that pin its consumer pacts, and only the consumer's own pins
 * tell the two apart.
 *
 * <p><b>Both are advisory.</b> {@link Optional#empty()} is "could not ask" — no address configured,
 * qits-maintenance unreachable or refusing, an answer that will not parse — and never throws; the
 * caller turns it into "no consumers known" and a WARN, never a guess.
 */
public interface DownstreamPins {

  /**
   * One repository downstream of the root, as qits-maintenance's closure names it.
   *
   * @param repository the catalog name
   * @param catalogId this service's repository row id for it, or null where qits-maintenance has no
   *     row id for it
   * @param depth hops from the root; 1 is a direct consumer
   */
  record Downstream(String repository, String catalogId, int depth) {}

  /**
   * One pin a repository's manifests declare.
   *
   * @param ecosystem {@code maven}, {@code npm}, {@code docker}, … as qits-maintenance spells it
   * @param name {@code <groupId>:<artifactId>} for maven, the package name for npm
   */
  record Pin(String ecosystem, String name) {}

  /**
   * {@code GET /maintenance/api/repositories/{repoId}/downstream}: the downstream closure of {@code
   * repoId}, nearest first. An unknown repository is a present empty list.
   */
  Optional<List<Downstream>> downstreamOf(String repoId);

  /**
   * {@code GET /maintenance/api/repositories/{repository}}: the pins {@code repository}'s manifests
   * declare. A repository qits-maintenance has no row for (404) is a present empty list — it pins
   * nothing anybody knows of.
   */
  Optional<List<Pin>> pinsOf(String repository);
}
