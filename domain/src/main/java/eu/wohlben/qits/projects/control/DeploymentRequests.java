package eu.wohlben.qits.projects.control;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * What qits-deployments has been asked to do about one released version — <b>the third phase of a
 * release pipeline, read</b>.
 *
 * <p><b>A phase is a unit of work with a state and a rerun, and the deployment is the third one.</b>
 * QA is a qits-ci run at the fold, publish is a qits-ci run at the tag, and deploy is a
 * qits-deployments <em>deployment request</em> for {@code (repository, version)}. Nothing else is a
 * phase: a step inside one of those runs is not one, and neither is any other part a run is split
 * into, because neither has a rerun of its own. The <b>gate</b> that follows this phase is a
 * different thing again and is not this port's business — {@code DEPLOYMENT} is closed by a {@code
 * DeploymentActive} arriving on the bus, {@code ReleaseFinalization} is where that is decided, and
 * nothing here re-decides it or is allowed to. A gate delays; this port reports.
 *
 * <p><b>This service holds no fact about a deployment and that is why the port exists.</b> Its one
 * stored fact is {@code ReleasedTagPendingMerge.deploymentActiveAt} — the gate's answer, written
 * when the event arrived — and reporting that as the phase as well would be one fact wearing two
 * hats, with no request id, no intermediate state and nothing to re-run. So the phase is read live
 * from the service that owns it, and <b>no project awareness is added over there</b>: the question
 * is asked in qits-deployments' own vocabulary, {@code (repoId, version)}, which is the pair its
 * listing is already keyed on.
 *
 * <p>A port in the house shape: the implementation is {@code service/…/deploymenthost} (one HTTP
 * read of {@code GET /deployments/api/deployment-requests?repoId=…&version=…}), resolved
 * through {@code Instance} with absent supported, and <b>it must not throw</b>. It decorates a read,
 * so it carries {@code HttpActiveBuilds}' failure contract and not the dispatch's.
 *
 * <p><b>{@code Optional.empty()} means "could not be asked" and NEVER "no deployment".</b> The port
 * unimplemented, the address unset, qits-deployments unreachable, a 401/403 at its door, any
 * non-200, an unreadable body — all one answer, and the assembler draws it as a phase in state
 * {@code UNKNOWN}. <b>A present, empty list is a different answer</b>: qits-deployments really was
 * asked and really has no request for that version yet, which is a deployment about to be asked for
 * rather than one that cannot be seen. Collapsing the two would put a released, deployable
 * repository on screen as though nothing were owed — the one reading that would make an outage of
 * the far side look like a finished release. It is the {@code DownstreamComponents} rule one seam
 * over, with the stakes of a screen rather than of a merge.
 */
public interface DeploymentRequests {

  /**
   * One deployment request, in the three fields a phase needs and no more.
   *
   * @param id the deployment request's own id — what the phase reports as its {@code runId}, because
   *     a caller does exactly one thing with that field and it is address the phase's own page
   * @param status qits-deployments' {@code deploymentStatus}, <b>verbatim and nullable</b>. Null is a
   *     real answer over there — a request whose deployment has not been created at all — and it is
   *     carried as null rather than as a word, so the fold that maps it has the same three-way
   *     choice this service has everywhere else: a word it knows, a word it does not, and no word.
   * @param createdAt when the request was made. It becomes the phase's {@code startedAt}; there is
   *     deliberately no {@code finishedAt} for a deployment phase, because qits-deployments carries
   *     no instant meaning "this deployment settled" — {@code gateSettledAt} is when its own quality
   *     gate answered, which is a different fact and must not be dressed as one.
   */
  record DeploymentRequestView(String id, String status, Instant createdAt) {}

  /**
   * Every deployment request for this released version, <b>newest first</b>.
   *
   * <p>The order is the far side's ({@code seq desc}) and the caller depends on it: the phase is the
   * newest request, and every older one is a request it superseded — a redeploy, or an earlier ask
   * that a later one replaced. This service does not re-sort, because the sequence a row was written
   * in is a fact only the writer holds.
   *
   * @param repoId the repository the version was released from — qits-deployments' own {@code repoId},
   *     which is this service's repository row id
   * @param version the released version, the tag's bare name and never a ref
   * @return the requests, or empty where the question could not be asked at all — see this
   *     interface's javadoc for why that is never the same answer as an empty list
   */
  Optional<List<DeploymentRequestView>> forRelease(String repoId, String version);
}
