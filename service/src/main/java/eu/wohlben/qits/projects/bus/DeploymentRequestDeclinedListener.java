package eu.wohlben.qits.projects.bus;

import eu.wohlben.qits.eventstream.QitsDurableEventListener;
import eu.wohlben.qits.eventstream.control.CanonicalJson;
import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.projects.control.ReleaseFinalization;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * <b>A held deploy was declined, so the release it belongs to ends without shipping</b>
 * (qits-2271). qits-deployments announces {@code DeploymentRequestDeclined} for every deployment
 * request a person declined — the one they named, and each still-held sibling of the same release
 * request it settled with it — and a release request whose deploy was declined used to stay
 * RELEASED for ever, holding its repository's successor behind it.
 *
 * <p>What it does is one call — {@link ReleaseFinalization#onDeployDeclined} — correlated by the
 * payload's {@code releaseRequestQualifiedId}, which qits-deployments copied from the release
 * intake. The request ends WITHDRAWN and its tag leaves every fold; the method is idempotent, so
 * the siblings' events after the first change nothing.
 *
 * <p>The event alone is not relied on: {@code ReleaseFinalization.reconcileDeployments} reads
 * qits-deployments' newest request for every release still waiting on its deployment and ends a
 * declined one the same way. This listener is the prompt path.
 *
 * <p>The payload is a local record for {@link DeploymentActiveListener}'s reason — no jar of
 * qits-deployments' vocabulary is a dependency here — and registered in {@code
 * EventWireReflection}. A payload that will not parse or names no release request is poison and
 * settles with a WARN; a database that could not answer throws, because the next attempt is what
 * fixes it.
 *
 * <p>{@link #replayFromEpoch()} stays at its default, the head of the log: nothing was declined
 * before this consumer existed, and anything missed later is the reconcile's.
 */
@ApplicationScoped
public class DeploymentRequestDeclinedListener implements QitsDurableEventListener {

  private static final Logger LOG = Logger.getLogger(DeploymentRequestDeclinedListener.class);

  /** qits-deployments' "a held deploy was declined", as the wire spells it. */
  static final String SIGNATURE = "DeploymentRequestDeclined";

  /**
   * This consumer's storage key, in {@code consumed_event} and {@code consumer_watermark}.
   * <b>Never change it</b> — a new value is a brand-new consumer initializing at the head of the
   * log. It names the consumption, not the class.
   */
  static final String CONSUMER_ID = "projects-deployment-request-declined";

  /**
   * The fields this listener reads, as a local record bound by {@link CanonicalJson}; unknown
   * fields are ignored. {@code releaseRequestQualifiedId} is the correlation; {@code version} is
   * checked against the request's own tag; {@code environment} is the tier's name, and {@code note}
   * is absent when the decline gave none.
   *
   * <p>Public so {@code bus/EventWireReflection} and the wire test can name it.
   */
  public record DeploymentRequestDeclinedPayload(
      String deploymentRequestId,
      String releaseRequestQualifiedId,
      String appName,
      String environment,
      String version,
      String declinedBy,
      String note,
      Instant declinedAt) {}

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
    DeploymentRequestDeclinedPayload declined = decode(frame);
    if (declined == null) {
      // Warned in decode; the same bytes would fail identically on every later offer.
      return;
    }
    if (isBlank(declined.releaseRequestQualifiedId())) {
      LOG.warnf(
          "%s %s names no release request, so nothing can be ended for %s %s; it is skipped",
          frame.name(), frame.id(), declined.appName(), declined.version());
      return;
    }
    finalization.onDeployDeclined(
        declined.releaseRequestQualifiedId(),
        declined.version(),
        declined.declinedBy(),
        declined.note(),
        declined.deploymentRequestId());
  }

  /** Null on anything that will not read as this payload, warned about once, never thrown. */
  private DeploymentRequestDeclinedPayload decode(EventFrame frame) {
    try {
      return CanonicalJson.payloadTo(frame.payload(), DeploymentRequestDeclinedPayload.class);
    } catch (RuntimeException e) {
      LOG.warnf("%s %s has an unreadable payload: %s", frame.name(), frame.id(), e.getMessage());
      return null;
    }
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
