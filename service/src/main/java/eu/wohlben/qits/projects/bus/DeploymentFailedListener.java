package eu.wohlben.qits.projects.bus;

import eu.wohlben.qits.eventstream.QitsDurableEventListener;
import eu.wohlben.qits.eventstream.control.CanonicalJson;
import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.projects.control.ReleaseFinalization;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * <b>A deployment ended without going live, so the release it deployed records that</b>
 * (qits-1690). The mirror of {@link DeploymentActiveListener}: qits-deployments announces {@code
 * DeploymentFailed} for every terminal outcome that is not {@code DeploymentActive} — a rollback,
 * a failure, an image it could not pull — and a release whose deployment was rolled back used to
 * stay RELEASED for ever, its deployment gate "waiting to go live".
 *
 * <p>What it does is one call — {@link ReleaseFinalization#onDeploymentFailed} — correlated exactly
 * as the active one is, by application name and version. The record makes the deployment gate
 * FAILED and the release red for the qits-1471 hold; a later {@code DeploymentActive} (a redeploy
 * that went live) clears it. <b>Nothing is redeployed from here.</b>
 *
 * <p>The event alone is not relied on: the rollback that prompted this never reached the events
 * log, so {@code ReleaseFinalization.reconcileDeployments} asks qits-deployments about every
 * release still waiting on its deployment on a schedule. This listener is the prompt path.
 *
 * <p>The payload is a local record for {@link DeploymentActiveListener}'s reason, and registered in
 * {@code EventWireReflection} for the same one. Failure handling is that class's: a payload that
 * will not parse or names no version is poison and settles with a WARN; a database that could not
 * answer throws, because the next attempt is what fixes it.
 *
 * <p>{@link #replayFromEpoch()} stays at its default, the head of the log: replaying every failed
 * deployment this platform ever made would mark ancient releases red. Anything stranded before
 * this consumer existed is the reconcile's.
 */
@ApplicationScoped
public class DeploymentFailedListener implements QitsDurableEventListener {

  private static final Logger LOG = Logger.getLogger(DeploymentFailedListener.class);

  /** qits-deployments' "this did not go live" — {@code DeploymentFailed}, as the wire spells it. */
  static final String SIGNATURE = "DeploymentFailed";

  /**
   * This consumer's storage key, in {@code consumed_event} and {@code consumer_watermark}.
   * <b>Never change it</b> — a new value is a brand-new consumer initializing at the head of the
   * log. It names the consumption, not the class.
   */
  static final String CONSUMER_ID = "projects-deployment-failure";

  /**
   * The fields this listener reads, as a local record bound by {@link CanonicalJson}; unknown
   * fields are ignored. {@code version} is the correlation, as on {@link
   * DeploymentActiveListener.DeploymentActivePayload}; {@code status} is the deployer's terminal
   * word ({@code ROLLED_BACK}, {@code FAILED}, {@code IMAGE_MISSING}) and {@code detail} its own
   * diagnosis, nullable and never parsed — only its first line is kept.
   *
   * <p>Public so {@code bus/EventWireReflection} and the wire test can name it.
   */
  public record DeploymentFailedPayload(
      String deploymentId,
      String applicationName,
      String environmentId,
      String environmentName,
      String version,
      String commitSha,
      String runId,
      String status,
      String detail) {}

  @Inject ReleaseFinalization finalization;

  @Override
  public String consumerId() {
    return CONSUMER_ID;
  }

  @Override
  public Set<String> signatures() {
    return Set.of(SIGNATURE);
  }

  @Override
  public void onFrame(EventFrame frame) {
    DeploymentFailedPayload deployment = decode(frame);
    if (deployment == null) {
      // Warned in decode; the same bytes would fail identically on every later offer.
      return;
    }
    if (isBlank(deployment.version())) {
      LOG.warnf(
          "%s %s carries no version, so no released tag can be marked failed for %s; it is skipped",
          frame.name(), frame.id(), deployment.applicationName());
      return;
    }
    finalization.onDeploymentFailed(
        deployment.applicationName(),
        deployment.version(),
        deployment.status(),
        deployment.detail());
  }

  /** Null on anything that will not read as this payload, warned about once, never thrown. */
  private DeploymentFailedPayload decode(EventFrame frame) {
    try {
      return CanonicalJson.payloadTo(frame.payload(), DeploymentFailedPayload.class);
    } catch (RuntimeException e) {
      LOG.warnf("%s %s has an unreadable payload: %s", frame.name(), frame.id(), e.getMessage());
      return null;
    }
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
