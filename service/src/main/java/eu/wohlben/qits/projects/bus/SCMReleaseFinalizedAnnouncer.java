package eu.wohlben.qits.projects.bus;

import eu.wohlben.qits.eventstream.QitsEventBus;
import eu.wohlben.qits.projects.control.ReleaseFinalizedAnnouncer;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;

/**
 * Turns a landed merge into {@link SCMReleaseFinalized} and hands it to the bus — the <b>fourth</b>
 * thing this service publishes, beside {@code RepositoryRenamedAnnouncer}, {@code
 * ReleaseRequestChangedAnnouncer} and {@link SCMReleaseAnnouncer}, and for the same structural
 * reasons.
 *
 * <p>It lives in {@code service/} because {@code domain} knows nothing of the bus; the seam it
 * implements is {@link ReleaseFinalizedAnnouncer} in {@code projects/control}, and zero
 * implementations is a supported configuration.
 *
 * <p><b>The cause is left to the bus.</b> {@code QitsEventBus.publish(event)} resolves the parent
 * from {@code CausationScope}, so a finalization made under a deployment's consumption inherits that
 * frame — which extends the chain the release already builds by its last hop: push → release request
 * changed → CI run → verdict → release → deploy → <em>main</em>.
 *
 * <p><b>{@code @DefaultBean}</b>, the posture every adapter here takes: the suite's recording fake
 * then wins the port's injection point simply by existing, so no test reaches the bus and none has
 * to arrange not to.
 */
@ApplicationScoped
@DefaultBean
public class SCMReleaseFinalizedAnnouncer implements ReleaseFinalizedAnnouncer {

  @Inject QitsEventBus bus;

  @Override
  public void onReleaseFinalized(
      String projectId,
      String repoId,
      String repoName,
      String target,
      String version,
      String mergedSha,
      Instant occurredAt) {
    bus.publish(
        new SCMReleaseFinalized(
            projectId, repoId, repoName, target, version, mergedSha, occurredAt));
  }
}
