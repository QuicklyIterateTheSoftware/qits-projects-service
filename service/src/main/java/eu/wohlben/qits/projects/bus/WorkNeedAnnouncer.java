package eu.wohlben.qits.projects.bus;

import eu.wohlben.qits.entities.control.NeedsMet;
import eu.wohlben.qits.eventstream.QitsEventBus;
import eu.wohlben.qits.projects.api.QualifiedEntityIds;
import eu.wohlben.qits.projects.control.ProjectService;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.inject.Inject;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * <b>Publishes {@link WorkNeedMet}</b> (qits-1903) — the one place it crosses the wire. The
 * {@code entities} module fires {@link NeedsMet} on the CDI bus after a transition committed
 * ({@code NeedService.announceMet}, from {@code EntityTransitionService} and {@code
 * WorkEntityService} alike), inside the read of who waits on the moved items; {@link #onMet} —
 * {@code during = AFTER_SUCCESS} — publishes one event per need once that read has committed.
 * Here the qualified ids are rendered, which {@code entities} cannot: the slug is {@code domain}'s.
 *
 * <p><b>Durable, never fire-and-forget</b>: {@code QitsEventBus.publish} attempts the PUT inline and
 * leaves the event to its outbox when qits-events is not there, exactly as {@link
 * MilestoneAnnouncer} publishes {@link MilestoneMet}. It never throws into the caller.
 *
 * <p>{@code @DefaultBean}, like every announcer here: the suite's {@code RecordingWorkNeedAnnouncer}
 * overrides {@link #publish} alone, so no test reaches the bus.
 */
@ApplicationScoped
@DefaultBean
public class WorkNeedAnnouncer {

  private static final Logger LOG = Logger.getLogger(WorkNeedAnnouncer.class);

  @Inject QitsEventBus bus;

  @Inject ProjectService projects;

  /** The needs a transition met, once the read that found them has committed. */
  void onMet(@Observes(during = TransactionPhase.AFTER_SUCCESS) NeedsMet met) {
    announce(met);
  }

  /** Publishes every need of {@code met}, in order; never throws. */
  public void announce(NeedsMet met) {
    if (met == null || met.met().isEmpty()) {
      return;
    }
    Map<String, String> slugs = Map.of();
    try {
      Set<String> projectIds = new LinkedHashSet<>();
      met.met().forEach(need -> projectIds.add(need.projectId()));
      slugs = projects.slugsByIds(projectIds);
    } catch (RuntimeException e) {
      LOG.warnf(e, "Could not read the slugs the met needs are named by; publishing without them");
    }
    for (NeedsMet.Met need : met.met()) {
      String slug = slugs.get(need.projectId());
      try {
        publish(
            new WorkNeedMet(
                need.projectId(),
                need.entityId(),
                slug == null ? null : QualifiedEntityIds.render(slug, need.number()),
                need.neededEntityId(),
                slug == null ? null : QualifiedEntityIds.render(slug, need.neededNumber()),
                need.status(),
                need.neededStatus(),
                need.allMet(),
                need.metAt()));
      } catch (RuntimeException e) {
        LOG.warnf(
            e,
            "Could not announce that %s's need of %s at %s is met",
            need.entityId(),
            need.neededEntityId(),
            need.status());
      }
    }
  }

  /** The bus half. Overridden by the suite's recording double, and by nothing else. */
  protected void publish(WorkNeedMet event) {
    bus.publish(event);
  }
}
