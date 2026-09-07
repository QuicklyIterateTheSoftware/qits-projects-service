package eu.wohlben.qits.projects.bus;

import eu.wohlben.qits.eventstream.QitsEventBus;
import eu.wohlben.qits.projects.control.ProjectAnnouncer;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;

/**
 * Turns a project's creation and its deletion into {@link ProjectCreated} / {@link ProjectDeleted}
 * and hands them to the bus — the fourth thing this service publishes, beside {@code
 * RepositoryRenamedAnnouncer}, {@code ReleaseRequestChangedAnnouncer} and {@code SCMReleaseAnnouncer},
 * and for the same structural reasons.
 *
 * <p>It lives in {@code service/} because {@code domain} knows nothing of the bus; the seam it
 * implements is {@link ProjectAnnouncer} in {@code projects/control}, and zero implementations is a
 * supported configuration (which is what {@code domain}'s own suite runs as).
 *
 * <p><b>One announcer for two verbs, and deliberately one.</b> They are the two ends of a single
 * lifecycle and a consumer subscribes to both or to neither — the platform edge learns which SANs a
 * project is owed from the first and when they stop being owed from the second.
 *
 * <p><b>The cause is left to the bus.</b> {@code QitsEventBus.publish(event)} resolves the parent
 * from {@code CausationScope}, which the REST filter has already restored from the request's {@code
 * X-Qits-Causation-Id} — and a create is made on the request thread, with no hop in between, so
 * there is nothing this class knows that the ambient scope does not. The backfill's publishes run on
 * a boot thread with no ambient cause, which is the honest answer: nothing asked for them but the
 * process starting.
 *
 * <p><b>The announcement is made AFTER the transaction</b> that created or deleted the project,
 * never inside it — the rule {@code RepositoryRenamedAnnouncer} states, and the reason {@code
 * ProjectService.create} and {@code ProjectService.delete} both drive their rows through an explicit
 * {@code QuarkusTransaction.requiringNew()} rather than wearing {@code @Transactional}.
 *
 * <p><b>{@code @DefaultBean}</b>, the posture every adapter here takes: the suite's {@code
 * RecordingProjectAnnouncer} then wins the port's injection point simply by existing, so no test
 * reaches the bus and none has to arrange not to.
 */
@ApplicationScoped
@DefaultBean
public class ProjectLifecycleAnnouncer implements ProjectAnnouncer {

  @Inject QitsEventBus bus;

  @Override
  public void onProjectCreated(String projectId, String slug, String name, Instant occurredAt) {
    bus.publish(new ProjectCreated(projectId, slug, name, occurredAt));
  }

  @Override
  public void onProjectDeleted(String projectId, String slug, Instant occurredAt) {
    bus.publish(new ProjectDeleted(projectId, slug, occurredAt));
  }
}
