package eu.wohlben.qits.projects.control;

/**
 * Asking qits-deployments to deploy a released version <b>again</b> — the rerun of the pipeline's
 * third phase.
 *
 * <p><b>It is the same door the release itself goes through, and that is deliberate rather than
 * convenient.</b> qits-deployments has never had a separate redeploy endpoint — there is no such
 * path, there never was one, and nothing is being retired here. Its intake, {@code POST
 * /deployments/api/events/software-released}, names "an operator redeploys a version" as one
 * of the two things it exists for, and it is deliberately exempt from the monotonic version collapse
 * that would otherwise swallow a repeat of a version already seen. A re-post is therefore honoured
 * by construction, and inventing a second door for the same ask would be this service teaching the
 * far side a concept it already has.
 *
 * <p><b>A separate port from {@link DeploymentRequests}, addressed the same way, because the failure
 * contracts are opposite.</b> That is this repository's standing split (see {@code workspacehost} in
 * CLAUDE.md): the listing decorates a read, so it never throws and "could not ask" is an honest
 * degraded answer; this one is a button somebody is waiting on, so a hop that did not happen has to
 * reach the caller as a status code rather than as a shrug. Two verbs with opposite failure
 * contracts do not share a class, however much they share an address.
 *
 * <p>So this one <b>throws</b> a {@code DomainException}: <b>503</b> where there is no address or no
 * credential to present — a platform with no qits-deployments cannot redeploy, and saying so is the
 * only useful answer — and <b>502</b> for the exchange itself, an unreachable far side, a refusal, a
 * validation error, any non-202. It is {@code WorkspaceAgentDispatch}'s contract, one seam over, for
 * the same reason: a person pressed something.
 *
 * <p><b>Nothing about the pipeline is changed by asking.</b> No row is written here, no release
 * request status moves — there is no new status and there must not be one — and the {@code
 * DEPLOYMENT} gate is still closed by a {@code DeploymentActive} reaching {@code
 * ReleaseFinalization} and by nothing else. A rerun re-asks a question; the answer arrives where it
 * always did.
 */
public interface DeploymentRedeploys {

  /**
   * What a deployment is asked for, in qits-deployments' own intake vocabulary.
   *
   * <p><b>{@code repoId} and {@code version} are the pair, and the far side requires exactly those
   * two.</b> The rest is naming: qits-deployments falls back {@code application} → {@code repoName}
   * → {@code repoId} when it labels the deployment, so the more this service can say the better the
   * far side's own screens read, and every one of them is taken off the release request's own row
   * rather than asked for by a caller.
   *
   * <p><b>The release's priority is deliberately not sent.</b> This service's priority words are a
   * release-queue vocabulary ({@code LOWEST} … {@code BLOCKING}) and qits-deployments' field of that
   * name is its own; passing one as the other would be two meanings on one wire with nothing to
   * notice the mismatch.
   */
  record Redeploy(
      String repoId, String projectId, String repoName, String application, String version) {}

  /**
   * Ask for {@code (repoId, version)} to be deployed.
   *
   * @throws eu.wohlben.qits.projects.error.DomainException 503 with no address and no credential,
   *     502 for everything the exchange itself can do
   */
  void deployAgain(Redeploy ask);
}
